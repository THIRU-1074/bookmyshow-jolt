package dev.thiru.bms.features.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.features.payments.ProcessWebhook.Event;
import dev.thiru.bms.features.payments.ProcessWebhook.Kind;
import dev.thiru.bms.features.payments.ProcessWebhook.Result;
import dev.thiru.bms.features.payments.ProcessWebhook.Type;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.testing.SliceKit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Ported from {@code ProcessPaymentWebhookTest}, on SQLite with the V2 seed. */
class ProcessWebhookTest {

    final PaymentFlow flow = PaymentFlow.boot();
    final SliceKit kit = flow.kit;
    final ProcessWebhook webhook =
            new ProcessWebhook(kit.db(), kit.gateway(), kit.events(), kit.clock());
    final String ada = kit.audience("ada@example.com");
    final String[] pending = flow.pending(ada, "A2", "B1");
    final String booking = pending[0];
    final String payment = pending[1];

    {
        kit.events().published.clear();
    }

    @AfterEach
    void close() {
        kit.close();
    }

    private Event event(String id, Type type, long amount, String currency) {
        return new Event(id, type, payment, "gw", amount, currency, kit.clock().instant());
    }

    private Event ok(String id) {
        return event(id, Type.PAYMENT_SUCCEEDED, 36000, "INR");
    }

    @Test
    void successConfirmsBookingBooksSeatsAndPublishes() {
        Result r = webhook.execute(ok("e1"));
        assertEquals(Kind.PROCESSED, r.kind());
        assertEquals(BookingStatus.CONFIRMED, r.bookingStatus());
        assertEquals("CONFIRMED", flow.bookingStatus(booking));
        assertEquals("SUCCEEDED", flow.paymentStatus(payment));
        assertEquals(List.of("A2:BOOKED:null", "B1:BOOKED:null"), flow.locks());
        assertEquals(
                List.of(
                        new SeatEvent.Booked(
                                flow.show, List.of(SeatId.parse("A2"), SeatId.parse("B1")))),
                kit.events().published);
    }

    @Test
    void duplicateEventHasNoSideEffects() {
        webhook.execute(ok("e1"));
        long version = flow.version(booking);
        kit.events().published.clear();
        Result r = webhook.execute(ok("e1"));
        assertEquals(Kind.DUPLICATE, r.kind());
        assertNull(r.bookingStatus());
        assertEquals(version, flow.version(booking));
        assertTrue(kit.events().published.isEmpty());
        assertTrue(kit.gateway().refunds.isEmpty());
    }

    @Test
    void failureReturnsBookingToHeldSoTheUserCanRetry() {
        Result r = webhook.execute(event("e1", Type.PAYMENT_FAILED, 36000, "INR"));
        assertEquals(BookingStatus.HELD, r.bookingStatus());
        assertEquals("FAILED", flow.paymentStatus(payment));
        assertEquals(List.of("A2:HELD:set", "B1:HELD:set"), flow.locks(), "kept for the retry");
        assertTrue(kit.events().published.isEmpty());
    }

    @Test
    void aSecondFailureForASettledPaymentChangesNothing() {
        webhook.execute(event("e1", Type.PAYMENT_FAILED, 36000, "INR"));
        long version = flow.version(booking);
        Result r = webhook.execute(event("e2", Type.PAYMENT_FAILED, 36000, "INR"));
        assertEquals(Kind.PROCESSED, r.kind());
        assertEquals(BookingStatus.HELD, r.bookingStatus());
        assertEquals(version, flow.version(booking));
    }

    @Test
    void amountMismatchFailsThePayment() {
        Result r = webhook.execute(event("m1", Type.PAYMENT_SUCCEEDED, 35999, "INR"));
        assertEquals(Kind.AMOUNT_MISMATCH, r.kind());
        assertEquals("FAILED", flow.paymentStatus(payment));
        assertEquals("HELD", flow.bookingStatus(booking));
        assertTrue(kit.gateway().refunds.isEmpty());
    }

    @Test
    void currencyMismatchFailsThePayment() {
        Result r = webhook.execute(event("m2", Type.PAYMENT_SUCCEEDED, 36000, "USD"));
        assertEquals(Kind.AMOUNT_MISMATCH, r.kind());
        assertEquals(BookingStatus.HELD, r.bookingStatus());
        assertEquals("FAILED", flow.paymentStatus(payment));
        assertTrue(kit.gateway().refunds.isEmpty());
    }

    @Test
    void successAfterExpiryExpiresTheBookingAndRefundsInFull() {
        kit.mutableClock().advance(Duration.ofMinutes(8)); // past the 7 min hold expiry
        Result r = webhook.execute(ok("e1"));
        assertEquals(BookingStatus.EXPIRED, r.bookingStatus());
        assertEquals("REFUNDED", flow.paymentStatus(payment));
        assertEquals(36000, flow.refunded(payment));
        assertEquals(List.of("gw_" + payment + ":36000"), kit.gateway().refunds);
        assertTrue(flow.locks().isEmpty());
        assertEquals(
                List.of(
                        new SeatEvent.Released(
                                flow.show,
                                List.of(SeatId.parse("A2"), SeatId.parse("B1")),
                                SeatEvent.Reason.EXPIRED)),
                kit.events().published);
    }

    @Test
    void failureAfterExpiryExpiresTheBookingAndFailsThePayment() {
        kit.mutableClock().advance(Duration.ofMinutes(8));
        Result r = webhook.execute(event("e1", Type.PAYMENT_FAILED, 36000, "INR"));
        assertEquals(BookingStatus.EXPIRED, r.bookingStatus());
        assertEquals("FAILED", flow.paymentStatus(payment));
        assertTrue(flow.locks().isEmpty());
        assertTrue(kit.gateway().refunds.isEmpty());
    }

    @Test
    void successOnACancelledBookingRefundsInFull() {
        // What a show cancel leaves behind (the admin slice owns it).
        flow.sql(
                "UPDATE booking SET status = 'CANCELLED', version = version + 1 WHERE id = ?",
                booking);
        flow.sql("DELETE FROM seat_lock WHERE booking_id = ?", booking);
        Result r = webhook.execute(ok("e1"));
        assertEquals(BookingStatus.CANCELLED, r.bookingStatus());
        assertEquals("REFUNDED", flow.paymentStatus(payment));
        assertEquals(List.of("gw_" + payment + ":36000"), kit.gateway().refunds);
        assertTrue(kit.events().published.isEmpty());
    }

    @Test
    void lostLocksRefundAndExpireInsteadOfConfirming() {
        flow.sql("DELETE FROM seat_lock WHERE booking_id = ? AND seat_id = 'B1'", booking);
        Result r = webhook.execute(ok("e1"));
        assertEquals(BookingStatus.EXPIRED, r.bookingStatus());
        assertEquals("REFUNDED", flow.paymentStatus(payment));
        assertTrue(flow.locks().isEmpty(), "the seat it still had is freed");
        assertEquals(
                List.of(
                        new SeatEvent.Released(
                                flow.show, List.of(SeatId.parse("A2")), SeatEvent.Reason.EXPIRED)),
                kit.events().published);
    }

    @Test
    void aLockNoLongerAttachedToTheBookingIsNotOwned() {
        flow.sql("UPDATE seat_lock SET booking_id = NULL WHERE seat_id = 'B1'");
        Result r = webhook.execute(ok("e1"));
        assertEquals(BookingStatus.EXPIRED, r.bookingStatus());
        assertEquals("REFUNDED", flow.paymentStatus(payment));
    }

    @Test
    void failedGatewayRefundRollsBackSoTheEventCanBeRedelivered() {
        kit.mutableClock().advance(Duration.ofMinutes(8));
        kit.gateway().failRefunds = true;
        assertThrows(IllegalStateException.class, () -> webhook.execute(ok("e1")));
        assertEquals("PAYMENT_PENDING", flow.bookingStatus(booking));
        assertEquals(List.of("A2:HELD:set", "B1:HELD:set"), flow.locks());
        assertTrue(kit.events().published.isEmpty(), "nothing is announced on rollback");
        kit.gateway().failRefunds = false;
        assertEquals(Kind.PROCESSED, webhook.execute(ok("e1")).kind());
        assertEquals("REFUNDED", flow.paymentStatus(payment));
        assertEquals(1, kit.events().published.size());
    }

    @Test
    void aLateSuccessForAFailedAttemptRefundsInFullAndLeavesTheBookingRetryable() {
        webhook.execute(event("e1", Type.PAYMENT_FAILED, 36000, "INR"));
        long version = flow.version(booking);
        kit.events().published.clear();

        Result r = webhook.execute(ok("e2"));

        assertEquals(Kind.PROCESSED, r.kind());
        assertEquals(BookingStatus.HELD, r.bookingStatus(), "the booking is untouched");
        assertEquals(version, flow.version(booking));
        assertEquals("REFUNDED", flow.paymentStatus(payment));
        assertEquals(36000, flow.refunded(payment));
        assertEquals(List.of("gw_" + payment + ":36000"), kit.gateway().refunds);
        assertEquals(List.of("A2:HELD:set", "B1:HELD:set"), flow.locks(), "seats still held");
        assertTrue(kit.events().published.isEmpty(), "no seat event: nothing changed for viewers");
    }

    @Test
    void aSecondSuccessEventForTheSamePaymentNeverRefundsTwice() {
        webhook.execute(event("e1", Type.PAYMENT_FAILED, 36000, "INR"));
        webhook.execute(ok("e2"));
        Result again = webhook.execute(ok("e3")); // a different event id for the same payment
        assertEquals(Kind.PROCESSED, again.kind());
        assertEquals(1, kit.gateway().refunds.size());
        assertEquals("REFUNDED", flow.paymentStatus(payment));
        assertEquals(36000, flow.refunded(payment));
    }

    @Test
    void redeliveringTheLateSuccessEventRefundsOnce() {
        webhook.execute(event("e1", Type.PAYMENT_FAILED, 36000, "INR"));
        webhook.execute(ok("e2"));
        assertEquals(Kind.DUPLICATE, webhook.execute(ok("e2")).kind());
        assertEquals(1, kit.gateway().refunds.size());
    }

    @Test
    void aSuccessForAPaymentAlreadySucceededOrRefundedRefundsNothing() {
        webhook.execute(ok("e1"));
        webhook.execute(ok("e2"));
        assertEquals("SUCCEEDED", flow.paymentStatus(payment));
        assertTrue(kit.gateway().refunds.isEmpty());
        assertEquals("CONFIRMED", flow.bookingStatus(booking));
    }

    @Test
    void aLateSuccessWithTheWrongAmountIsStillAMismatchAndRefundsNothing() {
        webhook.execute(event("e1", Type.PAYMENT_FAILED, 36000, "INR"));
        Result r = webhook.execute(event("e2", Type.PAYMENT_SUCCEEDED, 1, "INR"));
        assertEquals(Kind.AMOUNT_MISMATCH, r.kind());
        assertEquals("FAILED", flow.paymentStatus(payment));
        assertTrue(kit.gateway().refunds.isEmpty());
    }

    @Test
    void aFailingRefundOfALateSuccessRollsBackSoRedeliveryWorks() {
        webhook.execute(event("e1", Type.PAYMENT_FAILED, 36000, "INR"));
        kit.gateway().failRefunds = true;
        assertThrows(IllegalStateException.class, () -> webhook.execute(ok("e2")));
        assertEquals("FAILED", flow.paymentStatus(payment));
        kit.gateway().failRefunds = false;
        assertEquals(Kind.PROCESSED, webhook.execute(ok("e2")).kind());
        assertEquals("REFUNDED", flow.paymentStatus(payment));
        assertEquals(1, kit.gateway().refunds.size());
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
                                                kit.clock().instant())));
        assertEquals(DomainException.Code.NOT_FOUND, e.code());
        assertEquals("Payment not found", e.getMessage());
    }

    @Test
    void aVersionMissIsRetriedOnce() {
        // Another writer moves the booking between our read and our write: the update misses,
        // re-reads, and applies the transition to the fresh row.
        Result r =
                kit.db()
                        .inTransaction(
                                tx -> {
                                    var loaded = PaymentSql.booking(tx, booking).orElseThrow();
                                    tx.update(
                                            "UPDATE booking SET version = version + 1 WHERE id = ?",
                                            booking);
                                    var moved =
                                            BookingOps.apply(
                                                    tx,
                                                    loaded,
                                                    b -> b.paymentFailed(kit.clock().instant()));
                                    return new Result(Kind.PROCESSED, moved.status());
                                });
        assertEquals(BookingStatus.HELD, r.bookingStatus());
        assertEquals(3, flow.version(booking), "checkout 1, the other writer 2, ours 3");
    }
}
