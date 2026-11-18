package dev.thiru.bms.features.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import dev.thiru.bms.ports.SeatEvent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Cancellation with the refund bands of SPEC 4, on a show three days out. */
class CancelBookingTest {

    @RegisterExtension final BookingWorld w = new BookingWorld();
    final long show = w.show(3);
    final long ada = w.user("ada@example.com");

    @Test
    void cancellingAHeldBookingFreesSeatsWithNoRefund() {
        Booking b = w.held(ada, show, "A2");
        w.events.published.clear();
        Booking out = w.cancel.execute(ada, b.id());
        assertEquals(BookingStatus.CANCELLED, out.status());
        assertEquals(0, out.refundAmount());
        assertEquals(1, out.version());
        assertEquals(0, w.count("seat_lock"));
        assertEquals(
                List.of(
                        new SeatEvent.Released(
                                show, List.of(SeatId.parse("A2")), SeatEvent.Reason.CANCELLED)),
                w.events.published);
    }

    @Test
    void paymentPendingCannotBeCancelled() {
        Booking b = w.held(ada, show, "A2");
        w.checkout.execute(ada, b.id());
        assertEquals(
                Code.PAYMENT_IN_PROGRESS,
                assertThrows(DomainException.class, () -> w.cancel.execute(ada, b.id())).code());
        assertEquals(BookingStatus.PAYMENT_PENDING, w.bookingStatus(b.id()));
    }

    @Test
    void confirmedMoreThan24hAheadRefundsInFull() {
        Booking b = w.confirmed(ada, show, "A2", "G1");
        Booking out = w.cancel.execute(ada, b.id());
        assertEquals(BookingStatus.REFUNDED, out.status());
        assertEquals(46000, out.refundAmount());
        assertEquals(1, w.gateway.refunds.size());
        assertTrue(w.gateway.refunds.get(0).endsWith(":46000"));
        assertEquals(List.of(PaymentStatus.REFUNDED), w.payments(b.id()));
        assertEquals(46000, w.refunded(b.id()));
        assertEquals(0, w.count("seat_lock"));
    }

    @Test
    void confirmedWithinTheHalfRefundBandRoundsDown() {
        Booking b = w.confirmed(ada, show, "A2", "A3", "G1");
        w.clock.set(w.startsAt(show).minus(Duration.ofHours(24)));
        // BOOKED locks never expire, so the clock jump does not disturb them.
        Booking out = w.cancel.execute(ada, b.id());
        assertEquals(BookingStatus.REFUNDED, out.status());
        assertEquals((18000 * 2 + 28000) / 2, out.refundAmount());
        assertEquals(32000, w.refunded(b.id()));
    }

    @Test
    void anOddAmountRoundsDown() {
        // An odd price, so half of it is fractional; exactly 4 h before is still the half band.
        w.db.inTransaction(
                tx ->
                        tx.update(
                                "UPDATE show SET prices_json = '{\"CLASSIC\":18001,\"PRIME\":28000}'"
                                        + " WHERE id = ?",
                                show));
        Booking b = w.confirmed(ada, show, "A2");
        w.clock.set(w.startsAt(show).minus(Duration.ofHours(4)));
        assertEquals(9000, w.cancel.execute(ada, b.id()).refundAmount());
    }

    @Test
    void confirmedUnderFourHoursIsCancelledWithoutRefundAndSeatsAreReleased() {
        Booking b = w.confirmed(ada, show, "A2");
        w.clock.set(w.startsAt(show).minus(Duration.ofHours(3)));
        Booking out = w.cancel.execute(ada, b.id());
        assertEquals(BookingStatus.CANCELLED, out.status());
        assertEquals(0, out.refundAmount());
        assertTrue(w.gateway.refunds.isEmpty());
        assertEquals(0, w.count("seat_lock"));
        assertEquals(List.of(PaymentStatus.SUCCEEDED), w.payments(b.id()));
    }

    @Test
    void afterStartIsShowStartedAndNothingChanges() {
        Booking b = w.confirmed(ada, show, "A2");
        w.clock.set(w.startsAt(show));
        assertEquals(
                Code.SHOW_STARTED,
                assertThrows(DomainException.class, () -> w.cancel.execute(ada, b.id())).code());
        assertEquals(BookingStatus.CONFIRMED, w.bookingStatus(b.id()));
        assertEquals(1, w.count("seat_lock"));
    }

    @Test
    void foreignAndTerminalBookingsAreRejected() {
        Booking b = w.held(ada, show, "A2");
        long eve = w.user("eve@example.com");
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> w.cancel.execute(eve, b.id())).code());
        w.cancel.execute(ada, b.id());
        assertEquals(
                Code.BOOKING_STATE,
                assertThrows(DomainException.class, () -> w.cancel.execute(ada, b.id())).code());
    }

    @Test
    void expiredBookingIsExpiredNotCancelled() {
        Booking b = w.held(ada, show, "A2");
        w.clock.advance(Duration.ofMinutes(8));
        DomainException e =
                assertThrows(DomainException.class, () -> w.cancel.execute(ada, b.id()));
        assertEquals(Code.BOOKING_STATE, e.code());
        assertEquals("Booking has expired", e.getMessage());
        assertEquals(BookingStatus.EXPIRED, w.bookingStatus(b.id()));
        assertEquals(0, w.count("seat_lock"));
    }

    @Test
    void gatewayRefundFailureRollsBackTheCancellation() {
        Booking b = w.confirmed(ada, show, "A2");
        w.gateway.failRefunds = true;
        w.events.published.clear();
        assertThrows(IllegalStateException.class, () -> w.cancel.execute(ada, b.id()));
        assertEquals(BookingStatus.CONFIRMED, w.bookingStatus(b.id()));
        assertEquals(1, w.count("seat_lock"));
        assertEquals(List.of(PaymentStatus.SUCCEEDED), w.payments(b.id()));
        assertTrue(w.events.published.isEmpty(), "nothing published for a rolled back cancel");
    }
}
