package dev.thiru.bms.features.booking;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.Db.Tx;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.ports.SeatEvents;
import java.time.Clock;
import java.time.Instant;

/**
 * Owner cancellation with the time-based refund of SPEC section 4 ({@code RefundPolicy}, applied by
 * {@link Booking#cancelByOwner}). The gateway refund runs inside the transaction, so a gateway
 * failure rolls the cancellation back.
 */
final class CancelBooking {

    private final Db db;
    private final PaymentGateway gateway;
    private final SeatEvents events;
    private final Clock clock;

    CancelBooking(Db db, PaymentGateway gateway, SeatEvents events, Clock clock) {
        this.db = db;
        this.gateway = gateway;
        this.events = events;
        this.clock = clock;
    }

    Booking execute(long userId, String bookingId) {
        return db.inTransaction(tx -> run(tx, userId, bookingId)).orThrow();
    }

    private Outcome<Booking> run(Tx tx, long userId, String bookingId) {
        Instant now = clock.instant();
        Booking booking = BookingOps.owned(tx, bookingId, userId);
        if (booking.status().isOpen() && booking.isExpiredAt(now)) {
            BookingOps.expire(tx, events, booking, now);
            return Outcome.fail(DomainException.bookingState("Booking has expired"));
        }
        Show show = BookingSql.show(tx, booking.showId()).orElseThrow();
        BookingStatus before = booking.status();
        Booking cancelled =
                BookingOps.apply(tx, booking, b -> b.cancelByOwner(now, show.startsAt()));
        if (before == BookingStatus.CONFIRMED && cancelled.refundAmount() > 0) {
            Payment payment = BookingSql.latestPayment(tx, bookingId).orElseThrow();
            gateway.refund(payment.gatewayRef(), cancelled.refundAmount());
            BookingSql.updatePayment(tx, payment.refund(cancelled.refundAmount(), now));
        }
        BookingOps.released(
                tx,
                events,
                booking.showId(),
                BookingSql.deleteLocks(tx, booking.holdId()),
                SeatEvent.Reason.CANCELLED);
        return Outcome.ok(cancelled);
    }
}
