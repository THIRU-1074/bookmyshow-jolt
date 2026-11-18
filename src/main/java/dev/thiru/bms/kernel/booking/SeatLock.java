package dev.thiru.bms.kernel.booking;

import dev.thiru.bms.kernel.SeatId;
import java.time.Instant;

/**
 * One row of {@code seat_lock}. HELD locks carry an expiry; BOOKED locks never expire. A booking id
 * is attached once a booking is created from the hold.
 */
public record SeatLock(
        long showId, SeatId seat, String holdId, String bookingId, State state, Instant expiresAt) {

    public enum State {
        HELD,
        BOOKED
    }

    /** A HELD lock past its expiry is dead weight that lazy expiry may delete. */
    public boolean isLiveAt(Instant now) {
        return state == State.BOOKED || expiresAt.isAfter(now);
    }
}
