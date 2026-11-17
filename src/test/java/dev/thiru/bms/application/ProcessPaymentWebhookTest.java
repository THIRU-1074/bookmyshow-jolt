package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.ProcessPaymentWebhook.Event;
import dev.thiru.bms.application.ProcessPaymentWebhook.Kind;
import dev.thiru.bms.application.ProcessPaymentWebhook.Type;
import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.booking.SeatLock;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import dev.thiru.bms.ports.SeatEvent;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessPaymentWebhookTest {

    FakeWorld w;
    ProcessPaymentWebhook webhook;
    Booking booking;
    Checkout.Result checkout;

    @BeforeEach
    void setUp() {
        w = FakeWorld.seeded();
        webhook = new ProcessPaymentWebhook(w, w, w, w.clock);
        Booking held =
                new CreateBooking(w, w, w, w.clock).execute(7, w.putHold(7, "A2", "B1").id());
        checkout = new Checkout(w, w, w, w, w.clock, w.policy).execute(7, held.id());
        booking = w.booking(held.id());
        w.published.clear();
    }

    private Event event(String id, Type type, long amount, String currency) {
        return new Event(id, type, checkout.paymentId(), "gw", amount, currency, w.clock.instant());
    }

    private Event ok(String id) {
        return event(id, Type.PAYMENT_SUCCEEDED, 40000, "INR");
    }

    @Test
    void successConfirmsBookingBooksSeatsAndPublishes() {
        ProcessPaymentWebhook.Result r = webhook.execute(ok("e1"));
        assertEquals(Kind.PROCESSED, r.kind());
        assertEquals(BookingStatus.CONFIRMED, r.bookingStatus());
        assertEquals(BookingStatus.CONFIRMED, w.booking(booking.id()).status());
        assertEquals(PaymentStatus.SUCCEEDED, w.payment(checkout.paymentId()).status());
        assertTrue(
                w.allLocks().stream()
                        .allMatch(
                                l -> l.state() == SeatLock.State.BOOKED && l.expiresAt() == null));
        assertEquals(1, w.published.size());
        assertTrue(w.published.get(0) instanceof SeatEvent.Booked);
    }

    @Test
    void duplicateEventHasNoSideEffects() {
        webhook.execute(ok("e1"));
        long version = w.booking(booking.id()).version();
        w.published.clear();
        ProcessPaymentWebhook.Result r = webhook.execute(ok("e1"));
        assertEquals(Kind.DUPLICATE, r.kind());
        assertNull(r.bookingStatus());
        assertEquals(version, w.booking(booking.id()).version());
        assertTrue(w.published.isEmpty());
        assertTrue(w.refunds.isEmpty());
    }

    @Test
    void failureReturnsBookingToHeldSoTheUserCanRetry() {
        ProcessPaymentWebhook.Result r =
                webhook.execute(event("e1", Type.PAYMENT_FAILED, 40000, "INR"));
        assertEquals(BookingStatus.HELD, r.bookingStatus());
        assertEquals(PaymentStatus.FAILED, w.payment(checkout.paymentId()).status());
        assertEquals(2, w.allLocks().size(), "seats stay held for the retry");
    }

    @Test
    void amountOrCurrencyMismatchFailsThePayment() {
        for (Event bad :
                new Event[] {
                    event("m1", Type.PAYMENT_SUCCEEDED, 39999, "INR"),
                    event("m2", Type.PAYMENT_SUCCEEDED, 40000, "USD")
                }) {
            setUp();
            ProcessPaymentWebhook.Result r = webhook.execute(bad);
            assertEquals(Kind.AMOUNT_MISMATCH, r.kind());
            assertEquals(PaymentStatus.FAILED, w.payment(checkout.paymentId()).status());
            assertEquals(BookingStatus.HELD, w.booking(booking.id()).status());
            assertTrue(w.refunds.isEmpty());
        }
    }

    @Test
    void successAfterExpiryExpiresTheBookingAndRefundsInFull() {
        w.clock.advance(Duration.ofMinutes(8)); // past the 7 min hold expiry the booking inherited
        ProcessPaymentWebhook.Result r = webhook.execute(ok("e1"));
        assertEquals(BookingStatus.EXPIRED, r.bookingStatus());
        Payment p = w.payment(checkout.paymentId());
        assertEquals(PaymentStatus.REFUNDED, p.status());
        assertEquals(40000, p.refundedAmount());
        assertEquals(1, w.refunds.size());
        assertTrue(w.allLocks().isEmpty());
        assertTrue(
                w.published.stream()
                        .anyMatch(
                                e ->
                                        e instanceof SeatEvent.Released rel
                                                && rel.reason() == SeatEvent.Reason.EXPIRED));
    }

    @Test
    void successOnACancelledBookingRefundsInFull() {
        new CancelShow(w, w, w, w.clock).execute(FakeWorld.SHOW_ID);
        assertEquals(BookingStatus.CANCELLED, w.booking(booking.id()).status());
        ProcessPaymentWebhook.Result r = webhook.execute(ok("e1"));
        assertEquals(BookingStatus.CANCELLED, r.bookingStatus());
        assertEquals(PaymentStatus.REFUNDED, w.payment(checkout.paymentId()).status());
    }

    @Test
    void lostLocksRefundAndExpireInsteadOfConfirming() {
        w.inTransaction(r -> r.locks().deleteByHold(booking.holdId()));
        ProcessPaymentWebhook.Result r = webhook.execute(ok("e1"));
        assertEquals(BookingStatus.EXPIRED, r.bookingStatus());
        assertEquals(PaymentStatus.REFUNDED, w.payment(checkout.paymentId()).status());
    }

    @Test
    void failedGatewayRefundRollsBackSoTheEventCanBeRedelivered() {
        w.clock.advance(Duration.ofMinutes(8));
        w.failRefunds = true;
        assertThrows(IllegalStateException.class, () -> webhook.execute(ok("e1")));
        assertEquals(BookingStatus.PAYMENT_PENDING, w.booking(booking.id()).status());
        w.failRefunds = false;
        assertEquals(Kind.PROCESSED, webhook.execute(ok("e1")).kind());
        assertEquals(PaymentStatus.REFUNDED, w.payment(checkout.paymentId()).status());
    }

    @Test
    void unknownPaymentIsNotFound() {
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () ->
                                webhook.execute(
                                        new Event(
                                                "e9",
                                                Type.PAYMENT_SUCCEEDED,
                                                "nope",
                                                "gw",
                                                1,
                                                "INR",
                                                w.clock.instant())));
        assertEquals(DomainException.Code.NOT_FOUND, e.code());
    }
}
