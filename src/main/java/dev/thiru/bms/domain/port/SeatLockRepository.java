package dev.thiru.bms.domain.port;

import dev.thiru.bms.domain.SeatId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Seat locks, keyed by the hold that created them. The primary key (show, seat) prevents double
 * booking.
 */
public interface SeatLockRepository {

    /**
     * Inserts one HELD lock per seat. Throws {@code DomainException.seatUnavailable(conflicts)}
     * when any seat already has a row, leaving no partial insert visible after the transaction
     * rolls back.
     */
    void lock(long showId, String holdId, List<SeatId> seats, Instant expiresAt);

    /** Deletes HELD locks of the given seats whose expiry has passed; returns the seats freed. */
    List<SeatId> deleteExpiredHeld(long showId, Collection<SeatId> seats, Instant now);

    /** Deletes every lock of the hold (any state); returns the seats freed. */
    List<SeatId> deleteByHold(String holdId);

    /** Stamps the booking id on the hold's locks. */
    void attachBooking(String holdId, String bookingId);

    /** Moves the expiry of the hold's HELD locks (checkout extends it). */
    void updateExpiry(String holdId, Instant expiresAt);

    /** Turns the hold's locks into BOOKED with no expiry. */
    void markBooked(String holdId);

    List<SeatLock> findByHold(String holdId);

    /**
     * All lock rows of a show, including expired HELD ones; callers filter with {@link
     * SeatLock#isLiveAt}.
     */
    List<SeatLock> findByShow(long showId);

    /** Live (BOOKED or unexpired HELD) lock counts per show, for availability badges. */
    Map<Long, Integer> countLive(Collection<Long> showIds, Instant now);
}
