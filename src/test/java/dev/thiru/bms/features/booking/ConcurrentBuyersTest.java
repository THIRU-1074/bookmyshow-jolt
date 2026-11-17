package dev.thiru.bms.features.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.catalog.Screen;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * SPEC section 13: 200 virtual-thread buyers race for overlapping seats through the slice's use
 * cases and the real {@code SqliteDb} (BEGIN IMMEDIATE). The {@code seat_lock} primary key must
 * keep every seat single-owned while the expiry sweep runs alongside. Ported from {@code
 * adapters.db.ConcurrentBuyersTest}.
 */
class ConcurrentBuyersTest {

    private static final int BUYERS = 200;
    // Few seats, many buyers: almost every request overlaps another.
    private static final int POOL = 12;

    @RegisterExtension final BookingWorld w = new BookingWorld();

    @Test
    void noSeatIsHeldTwiceAndBookedSeatsNeverExceedCapacity() throws Exception {
        long showId = w.show(0);
        Screen screen =
                w.db.inTransaction(
                        tx ->
                                BookingSql.screen(
                                        tx, BookingSql.show(tx, showId).orElseThrow().screenId()));
        List<SeatId> pool = screen.layout().bookableSeats().subList(0, POOL);

        List<Long> users = new ArrayList<>();
        for (int i = 0; i < BUYERS; i++) {
            users.add(w.user("buyer" + i + "@example.com"));
        }

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger held = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger booked = new AtomicInteger();
        AtomicInteger sweeps = new AtomicInteger();
        AtomicBoolean racing = new AtomicBoolean(true);
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            // The sweeper runs alongside the buyers, as the scheduled sweep does in production.
            // Nothing has expired yet, so it must take the writer lock and change nothing.
            Future<?> sweeper =
                    threads.submit(
                            () -> {
                                start.await();
                                while (racing.get()) {
                                    assertEquals(new ExpireSweep.Result(0, 0), w.sweep.run());
                                    sweeps.incrementAndGet();
                                }
                                return null;
                            });
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < BUYERS; i++) {
                long user = users.get(i);
                int n = i;
                done.add(
                        threads.submit(
                                () -> {
                                    List<SeatId> mine = new ArrayList<>(pool);
                                    Collections.shuffle(mine);
                                    List<String> want =
                                            mine.subList(0, 1 + n % 3).stream()
                                                    .map(SeatId::toString)
                                                    .toList();
                                    start.await();
                                    try {
                                        var result = w.holds.execute(user, showId, want);
                                        held.incrementAndGet();
                                        if (n % 2 == 0) {
                                            w.create.execute(user, result.hold().id());
                                            booked.incrementAndGet();
                                        }
                                    } catch (DomainException e) {
                                        assertEquals(
                                                DomainException.Code.SEAT_UNAVAILABLE, e.code());
                                        assertTrue(!e.conflicts().isEmpty());
                                        assertTrue(
                                                pool.stream()
                                                        .map(SeatId::toString)
                                                        .toList()
                                                        .containsAll(e.conflicts()));
                                        rejected.incrementAndGet();
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get();
            }
            racing.set(false);
            sweeper.get();
        }
        assertTrue(sweeps.get() > 0, "the sweep ran during the race");
        assertEquals(BUYERS, held.get() + rejected.get());
        assertTrue(held.get() > 0 && rejected.get() > 0, "the race must have winners and losers");

        // No seat belongs to two live holds, and the lock table mirrors the winners exactly.
        List<String> seatsOfWinners =
                w.db.inTransaction(
                        tx ->
                                tx.query(
                                        "SELECT seats_json FROM seat_hold WHERE show_id = ? AND"
                                                + " status IN ('ACTIVE', 'CONSUMED')",
                                        rs -> rs.getString(1),
                                        showId));
        Set<String> distinct = new HashSet<>();
        int total = 0;
        for (String json : seatsOfWinners) {
            for (String seat : BookingSql.elements(json)) {
                total++;
                assertTrue(distinct.add(seat), "seat held twice: " + seat);
            }
        }
        int lockRows = w.count("seat_lock WHERE show_id = " + showId);
        assertEquals(total, lockRows);
        assertEquals(held.get(), seatsOfWinners.size());
        assertTrue(lockRows <= POOL && lockRows <= screen.capacity());
        assertEquals(booked.get(), w.count("booking WHERE show_id = " + showId));
        int bookedSeats =
                w.count(
                        "booking_seat bs JOIN booking b ON b.id = bs.booking_id WHERE b.show_id = "
                                + showId);
        assertTrue(bookedSeats <= lockRows && bookedSeats <= screen.capacity());

        // Once the clock passes every expiry the sweeper leaves nothing ACTIVE and no locks.
        w.clock.advance(Duration.ofHours(1));
        w.sweep.run();
        assertEquals(0, w.count("seat_hold WHERE status = 'ACTIVE'"));
        assertEquals(0, w.count("seat_lock"));
    }
}
