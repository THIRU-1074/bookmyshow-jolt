package dev.thiru.bms.features.booking;

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
 * Steps several booking use cases share. The transition rules live in {@link Booking}; this is the
 * write around them. Payments and admin keep their own copies (PLAN.md: slices never import each
 * other).
 */
final class BookingOps {

    private BookingOps() {}

    /**
     * Writes a transition with the optimistic version. On a miss it re-reads and re-applies the
     * transition once (SPEC 5.4); a second miss surfaces as VERSION_CONFLICT.
     */
    static Booking apply(Tx tx, Booking loaded, UnaryOperator<Booking> transition) {
        try {
            return BookingSql.updateBooking(tx, transition.apply(loaded));
        } catch (DomainException e) {
            if (e.code() != DomainException.Code.VERSION_CONFLICT) {
                throw e;
            }
            Booking fresh =
                    BookingSql.booking(tx, loaded.id())
                            .orElseThrow(() -> DomainException.notFound("Booking"));
            return BookingSql.updateBooking(tx, transition.apply(fresh));
        }
    }

    /** Expires an open booking and frees its seats. */
    static Booking expire(Tx tx, SeatEvents events, Booking booking, Instant now) {
        Booking expired = apply(tx, booking, b -> b.expire(now));
        released(
                tx,
                events,
                booking.showId(),
                BookingSql.deleteLocks(tx, booking.holdId()),
                SeatEvent.Reason.EXPIRED);
        return expired;
    }

    /** The caller's booking; anyone else's reads as not found so ids cannot be probed. */
    static Booking owned(Tx tx, String bookingId, long userId) {
        return BookingSql.booking(tx, bookingId)
                .filter(b -> b.userId() == userId)
                .orElseThrow(() -> DomainException.notFound("Booking"));
    }

    /** Publishes {@code event} once the transaction has committed, never on rollback. */
    static void publish(Tx tx, SeatEvents events, SeatEvent event) {
        tx.afterCommit(() -> events.publish(event));
    }

    /** A release event for {@code seats}, unless nothing was freed. */
    static void released(
            Tx tx, SeatEvents events, long showId, List<SeatId> seats, SeatEvent.Reason reason) {
        if (!seats.isEmpty()) {
            publish(tx, events, new SeatEvent.Released(showId, seats, reason));
        }
    }
}
