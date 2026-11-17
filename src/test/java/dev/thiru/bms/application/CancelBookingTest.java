package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import dev.thiru.bms.ports.SeatEvent;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CancelBookingTest {

    FakeWorld w;
    CancelBooking cancel;
    CreateBooking createBooking;
    Checkout checkout;
    ProcessPaymentWebhook webhook;

    @BeforeEach
    void setUp() {
        w = FakeWorld.seeded();
        cancel = new CancelBooking(w, w, w, w.clock);
        createBooking = new CreateBooking(w, w, w, w.clock);
        checkout = new Checkout(w, w, w, w, w.clock, w.policy);
        webhook = new ProcessPaymentWebhook(w, w, w, w.clock);
    }

    private Booking held(long user, String... seats) {
        return createBooking.execute(user, w.putHold(user, seats).id());
    }

    private Booking confirmed(long user, String... seats) {
        Booking b = held(user, seats);
        Checkout.Result c = checkout.execute(user, b.id());
        webhook.execute(
                new ProcessPaymentWebhook.Event(
                        "e-" + b.id(),
                        ProcessPaymentWebhook.Type.PAYMENT_SUCCEEDED,
                        c.paymentId(),
                        "gw",
                        c.amount(),
                        "INR",
                        w.clock.instant()));
        return w.booking(b.id());
    }

    @Test
    void cancellingAHeldBookingFreesSeatsWithNoRefund() {
        Booking b = held(7, "A2");
        w.published.clear();
        Booking out = cancel.execute(7, b.id());
        assertEquals(BookingStatus.CANCELLED, out.status());
        assertEquals(0, out.refundAmount());
        assertTrue(w.allLocks().isEmpty());
        assertTrue(
                w.published.stream()
                        .anyMatch(
                                e ->
                                        e instanceof SeatEvent.Released r
                                                && r.reason() == SeatEvent.Reason.CANCELLED));
    }

    @Test
    void paymentPendingCannotBeCancelled() {
        Booking b = held(7, "A2");
        checkout.execute(7, b.id());
        assertEquals(
                Code.PAYMENT_IN_PROGRESS,
                assertThrows(DomainException.class, () -> cancel.execute(7, b.id())).code());
        assertEquals(BookingStatus.PAYMENT_PENDING, w.booking(b.id()).status());
    }

    @Test
    void confirmedMoreThan24hAheadRefundsInFull() {
        Booking b = confirmed(7, "A2", "B1");
        Booking out = cancel.execute(7, b.id());
        assertEquals(BookingStatus.REFUNDED, out.status());
        assertEquals(40000, out.refundAmount());
        assertEquals(1, w.refunds.size());
        assertTrue(w.refunds.get(0).endsWith(":40000"));
        assertEquals(PaymentStatus.REFUNDED, w.allPayments().get(0).status());
        assertEquals(40000, w.allPayments().get(0).refundedAmount());
        assertTrue(w.allLocks().isEmpty());
    }

    @Test
    void confirmedWithinTheHalfRefundBandRoundsDown() {
        Booking b = confirmed(7, "A2", "B1");
        // Show is 3 days out; jump to exactly 24 h before it.
        w.clock.set(w.show(FakeWorld.SHOW_ID).startsAt().minus(Duration.ofHours(24)));
        // BOOKED locks never expire, so the clock jump does not disturb them.
        Booking out = cancel.execute(7, b.id());
        assertEquals(BookingStatus.REFUNDED, out.status());
        assertEquals(20000, out.refundAmount());
    }

    @Test
    void confirmedUnderFourHoursIsCancelledWithoutRefundAndSeatsAreReleased() {
        Booking b = confirmed(7, "A2");
        w.clock.set(w.show(FakeWorld.SHOW_ID).startsAt().minus(Duration.ofHours(3)));
        Booking out = cancel.execute(7, b.id());
        assertEquals(BookingStatus.CANCELLED, out.status());
        assertEquals(0, out.refundAmount());
        assertTrue(w.refunds.isEmpty());
        assertTrue(w.allLocks().isEmpty());
        assertEquals(PaymentStatus.SUCCEEDED, w.allPayments().get(0).status());
    }

    @Test
    void afterStartIsShowStartedAndNothingChanges() {
        Booking b = confirmed(7, "A2");
        w.clock.set(w.show(FakeWorld.SHOW_ID).startsAt());
        assertEquals(
                Code.SHOW_STARTED,
                assertThrows(DomainException.class, () -> cancel.execute(7, b.id())).code());
        assertEquals(BookingStatus.CONFIRMED, w.booking(b.id()).status());
        assertEquals(1, w.allLocks().size());
    }

    @Test
    void foreignAndTerminalBookingsAreRejected() {
        Booking b = held(7, "A2");
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> cancel.execute(8, b.id())).code());
        cancel.execute(7, b.id());
        assertEquals(
                Code.BOOKING_STATE,
                assertThrows(DomainException.class, () -> cancel.execute(7, b.id())).code());
    }

    @Test
    void expiredBookingIsExpiredNotCancelled() {
        Booking b = held(7, "A2");
        w.clock.advance(Duration.ofMinutes(8));
        assertEquals(
                Code.BOOKING_STATE,
                assertThrows(DomainException.class, () -> cancel.execute(7, b.id())).code());
        assertEquals(BookingStatus.EXPIRED, w.booking(b.id()).status());
        assertTrue(w.allLocks().isEmpty());
    }

    @Test
    void gatewayRefundFailureRollsBackTheCancellation() {
        Booking b = confirmed(7, "A2");
        w.failRefunds = true;
        assertThrows(IllegalStateException.class, () -> cancel.execute(7, b.id()));
        assertEquals(BookingStatus.CONFIRMED, w.booking(b.id()).status());
        assertEquals(1, w.allLocks().size());
    }
}
