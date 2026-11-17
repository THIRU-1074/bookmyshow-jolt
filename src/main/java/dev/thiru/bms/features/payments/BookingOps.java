package dev.thiru.bms.features.payments;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.ports.Db.Tx;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.ports.SeatEvents;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The booking writes a payment makes: this slice's copy of the booking slice's steps (PLAN.md:
 * slices never import each other). The transition rules live in {@link Booking}.
 */
final class BookingOps {

    private BookingOps() {}

    /**
     * Writes a transition with the optimistic version. On a miss it re-reads and re-applies the
     * transition once (SPEC 5.4); a second miss surfaces as VERSION_CONFLICT.
     */
    static Booking apply(Tx tx, Booking loaded, UnaryOperator<Booking> transition) {
        try {
            return PaymentSql.updateBooking(tx, transition.apply(loaded));
        } catch (DomainException e) {
            if (e.code() != DomainException.Code.VERSION_CONFLICT) {
                throw e;
            }
            Booking fresh =
                    PaymentSql.booking(tx, loaded.id())
                            .orElseThrow(() -> DomainException.notFound("Booking"));
            return PaymentSql.updateBooking(tx, transition.apply(fresh));
        }
    }

    /** Expires an open booking, frees its seats and announces them once committed. */
    static Booking expire(Tx tx, SeatEvents events, Booking booking, Instant now) {
        Booking expired = apply(tx, booking, b -> b.expire(now));
        List<SeatId> freed = PaymentSql.deleteLocks(tx, booking.holdId());
        if (!freed.isEmpty()) {
            publish(
                    tx,
                    events,
                    new SeatEvent.Released(booking.showId(), freed, SeatEvent.Reason.EXPIRED));
        }
        return expired;
    }

    /** Publishes {@code event} once the transaction has committed, never on rollback. */
    static void publish(Tx tx, SeatEvents events, SeatEvent event) {
        tx.afterCommit(() -> events.publish(event));
    }
}
