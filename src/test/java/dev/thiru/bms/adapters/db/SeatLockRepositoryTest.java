package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.HoldStatus;
import dev.thiru.bms.kernel.booking.SeatHold;
import dev.thiru.bms.kernel.booking.SeatLock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SeatLockRepositoryTest extends DbTest {

    private static final Instant EXPIRES = NOW.plus(Duration.ofMinutes(7));

    long show;
    long user;

    @BeforeEach
    void fixtures() {
        show = anyShowId();
        user = newUser("buyer@example.com");
    }

    private static SeatId seat(String s) {
        return SeatId.parse(s);
    }

    private static List<SeatId> seats(String... s) {
        return java.util.Arrays.stream(s).map(SeatLockRepositoryTest::seat).toList();
    }

    private void hold(Repositories r, String id, List<SeatId> seats) {
        r.holds().insert(new SeatHold(id, show, user, seats, NOW, EXPIRES, HoldStatus.ACTIVE));
    }

    private void lock(String holdId, String... seats) {
        tx(
                r -> {
                    hold(r, holdId, seats(seats));
                    r.locks().lock(show, holdId, seats(seats), EXPIRES);
                    return null;
                });
    }

    @Test
    void lockInsertsOneHeldRowPerSeat() {
        lock("h1", "A2", "A1");
        List<SeatLock> rows = tx(r -> r.locks().findByHold("h1"));
        assertEquals(seats("A1", "A2"), rows.stream().map(SeatLock::seat).toList());
        assertTrue(rows.stream().allMatch(l -> l.state() == SeatLock.State.HELD));
        assertTrue(rows.stream().allMatch(l -> l.expiresAt().equals(EXPIRES)));
        assertTrue(rows.stream().allMatch(l -> l.bookingId() == null && l.showId() == show));
        assertEquals(2, tx(r -> r.locks().findByShow(show)).size());
    }

    @Test
    void conflictNamesTheActuallyConflictingSeatsAndRollsBackTheWholeHold() {
        lock("h1", "A2", "A3");
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () ->
                                tx(
                                        r -> {
                                            hold(r, "h2", seats("A1", "A2", "A3", "A4"));
                                            r.locks()
                                                    .lock(
                                                            show,
                                                            "h2",
                                                            seats("A1", "A2", "A3", "A4"),
                                                            EXPIRES);
                                            return null;
                                        }));
        assertEquals(DomainException.Code.SEAT_UNAVAILABLE, e.code());
        assertEquals(List.of("A2", "A3"), e.conflicts());
        // Nothing of the failed hold survives, including its hold row and the free seats.
        assertTrue(tx(r -> r.locks().findByHold("h2")).isEmpty());
        assertTrue(tx(r -> r.holds().findById("h2")).isEmpty());
        assertEquals(2, tx(r -> r.locks().findByShow(show)).size());
    }

    @Test
    void anyExistingRowConflictsUntilItIsDeleted() {
        lock("h1", "B1");
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () ->
                                tx(
                                        r -> {
                                            hold(r, "h2", seats("B1"));
                                            r.locks().lock(show, "h2", seats("B1"), EXPIRES);
                                            return null;
                                        }));
        assertEquals(List.of("B1"), e.conflicts());
    }

    @Test
    void lazyExpiryDeletesOnlyExpiredHeldLocksOfTheRequestedSeats() {
        lock("h1", "C1", "C2", "C3");
        Instant later = EXPIRES.plusSeconds(1);
        // C3 is not requested, so it stays even though it has expired too.
        assertEquals(
                seats("C1", "C2"),
                tx(r -> r.locks().deleteExpiredHeld(show, seats("C2", "C1", "D9"), later)));
        assertEquals(
                seats("C3"),
                tx(r -> r.locks().findByShow(show)).stream().map(SeatLock::seat).toList());
        // Not yet expired at the boundary instant minus a millisecond.
        assertTrue(
                tx(r -> r.locks().deleteExpiredHeld(show, seats("C3"), EXPIRES.minusMillis(1)))
                        .isEmpty());
        // Expiry is inclusive: expiresAt <= now.
        assertEquals(seats("C3"), tx(r -> r.locks().deleteExpiredHeld(show, seats("C3"), EXPIRES)));
        assertTrue(tx(r -> r.locks().deleteExpiredHeld(show, List.of(), later)).isEmpty());
    }

    @Test
    void bookedLocksAreNeverLazilyExpired() {
        lock("h1", "D1");
        tx(
                r -> {
                    r.locks().markBooked("h1");
                    return null;
                });
        assertTrue(
                tx(r -> r.locks().deleteExpiredHeld(show, seats("D1"), EXPIRES.plusSeconds(9999)))
                        .isEmpty());
        SeatLock l = tx(r -> r.locks().findByHold("h1")).get(0);
        assertEquals(SeatLock.State.BOOKED, l.state());
        assertNull(l.expiresAt());
    }

    @Test
    void deleteByHoldFreesEverySeatOfThatHoldOnly() {
        lock("h1", "E1", "E2");
        lock("h2", "E3");
        assertEquals(seats("E1", "E2"), tx(r -> r.locks().deleteByHold("h1")));
        assertTrue(tx(r -> r.locks().deleteByHold("h1")).isEmpty());
        assertEquals(1, tx(r -> r.locks().findByShow(show)).size());
    }

    @Test
    void deleteByHoldAlsoFreesBookedLocks() {
        lock("h1", "E1");
        tx(
                r -> {
                    r.locks().markBooked("h1");
                    return null;
                });
        assertEquals(seats("E1"), tx(r -> r.locks().deleteByHold("h1")));
    }

    @Test
    void updateExpiryMovesOnlyHeldLocksOfTheHold() {
        lock("h1", "F1");
        lock("h2", "F2");
        Instant moved = EXPIRES.plus(Duration.ofMinutes(10));
        tx(
                r -> {
                    r.locks().updateExpiry("h1", moved);
                    return null;
                });
        assertEquals(moved, tx(r -> r.locks().findByHold("h1")).get(0).expiresAt());
        assertEquals(EXPIRES, tx(r -> r.locks().findByHold("h2")).get(0).expiresAt());
    }

    @Test
    void attachBookingStampsTheBookingIdOnTheHoldsLocks() {
        lock("h1", "G1", "G2");
        String bookingId = "bk-1";
        tx(
                r -> {
                    r.bookings()
                            .insert(
                                    dev.thiru.bms.kernel.booking.Booking.create(
                                            bookingId,
                                            "BK00000000",
                                            user,
                                            show,
                                            "h1",
                                            List.of(
                                                    new dev.thiru.bms.kernel.booking.BookingSeat(
                                                            seat("G1"), "CLASSIC", 10000)),
                                            NOW,
                                            EXPIRES));
                    r.locks().attachBooking("h1", bookingId);
                    return null;
                });
        assertTrue(
                tx(r -> r.locks().findByHold("h1")).stream()
                        .allMatch(l -> bookingId.equals(l.bookingId())));
    }

    @Test
    void countLiveCountsBookedAndUnexpiredHeldPerShow() {
        lock("h1", "H1", "H2", "H3");
        tx(
                r -> {
                    r.locks().markBooked("h1");
                    return null;
                });
        lock("h2", "H4");
        long other = db.queryOne("SELECT max(id) FROM show", r -> r.getLong(1)).orElseThrow();
        assertEquals(Map.of(show, 4), tx(r -> r.locks().countLive(Set.of(show, other), NOW)));
        // h2 (H4) expired, the three BOOKED seats remain.
        assertEquals(
                Map.of(show, 3),
                tx(r -> r.locks().countLive(Set.of(show), EXPIRES.plusSeconds(1))));
        assertTrue(tx(r -> r.locks().countLive(Set.of(), NOW)).isEmpty());
    }

    @Test
    void isLiveAtMatchesTheStoredExpiry() {
        lock("h1", "J1");
        SeatLock l = tx(r -> r.locks().findByHold("h1")).get(0);
        assertTrue(l.isLiveAt(EXPIRES.minusMillis(1)));
        assertEquals(false, l.isLiveAt(EXPIRES));
    }
}
