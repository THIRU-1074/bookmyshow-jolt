package dev.thiru.bms.application;

import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.ports.SeatEvent;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

/** Steps shared by several use cases. */
final class BookingOps {

    private BookingOps() {}

    /**
     * Writes a transition with the optimistic version. On a miss it re-reads and re-applies the
     * transition once (SPEC 5.4); a second miss surfaces as VERSION_CONFLICT.
     */
    static Booking apply(Repositories r, Booking loaded, UnaryOperator<Booking> transition) {
        try {
            return r.bookings().update(transition.apply(loaded));
        } catch (DomainException e) {
            if (e.code() != DomainException.Code.VERSION_CONFLICT) {
                throw e;
            }
            Booking fresh =
                    r.bookings()
                            .findById(loaded.id())
                            .orElseThrow(() -> DomainException.notFound("Booking"));
            return r.bookings().update(transition.apply(fresh));
        }
    }

    /** Expires an open booking and frees its seats. */
    static Booking expire(Repositories r, Booking booking, Instant now, EventBuffer events) {
        Booking expired = apply(r, booking, b -> b.expire(now));
        List<SeatId> freed = r.locks().deleteByHold(booking.holdId());
        events.released(booking.showId(), freed, SeatEvent.Reason.EXPIRED);
        return expired;
    }

    static Booking ownedBooking(Repositories r, String bookingId, long userId) {
        return r.bookings()
                .findById(bookingId)
                .filter(b -> b.userId() == userId)
                .orElseThrow(() -> DomainException.notFound("Booking"));
    }
}
