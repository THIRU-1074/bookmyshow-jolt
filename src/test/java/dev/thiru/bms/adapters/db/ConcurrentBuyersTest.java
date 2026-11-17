package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.CreateBooking;
import dev.thiru.bms.application.CreateHold;
import dev.thiru.bms.application.ExpireSweep;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.ports.Ids;
import dev.thiru.bms.ports.SeatEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * SPEC section 13: 200 virtual-thread buyers race for overlapping seats through the real use cases
 * and the real unit of work. The primary key must keep every seat single-owned.
 */
class ConcurrentBuyersTest extends DbTest {

    private static final int BUYERS = 200;
    // Few seats, many buyers: almost every request overlaps another.
    private static final int POOL = 12;

    private static final class SequentialIds implements Ids {
        public String newId() {
            return UUID.randomUUID().toString();
        }

        public RandomGenerator random() {
            return RandomGenerator.getDefault();
        }
    }

    @Test
    void noSeatIsHeldTwiceAndBookedSeatsNeverExceedCapacity() throws Exception {
        long showId = anyShowId();
        Show show = tx(r -> r.shows().findById(showId)).orElseThrow();
        Screen screen = tx(r -> r.screens().findById(show.screenId())).orElseThrow();
        List<SeatId> pool = screen.layout().bookableSeats().subList(0, POOL);

        Clock clock = Clock.fixed(show.startsAt().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        SeatEvents quiet = e -> {};
        BookingPolicy policy = BookingPolicy.defaults();
        CreateHold createHold = new CreateHold(uow, quiet, new SequentialIds(), clock, policy);
        CreateBooking createBooking = new CreateBooking(uow, quiet, new SequentialIds(), clock);

        List<Long> users = new ArrayList<>();
        for (int i = 0; i < BUYERS; i++) {
            users.add(newUser("buyer" + i + "@example.com"));
        }

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger held = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger booked = new AtomicInteger();
        try (ExecutorService pool200 = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < BUYERS; i++) {
                long user = users.get(i);
                int n = i;
                done.add(
                        pool200.submit(
                                () -> {
                                    List<SeatId> mine = new ArrayList<>(pool);
                                    Collections.shuffle(mine);
                                    List<String> want =
                                            mine.subList(0, 1 + n % 3).stream()
                                                    .map(SeatId::toString)
                                                    .toList();
                                    start.await();
                                    try {
                                        var result = createHold.execute(user, showId, want);
                                        held.incrementAndGet();
                                        if (n % 2 == 0) {
                                            createBooking.execute(user, result.hold().id());
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
        }
        assertEquals(BUYERS, held.get() + rejected.get());
        assertTrue(held.get() > 0 && rejected.get() > 0, "the race must have winners and losers");

        // No seat belongs to two live holds, and the lock table mirrors the winners exactly.
        List<String> seatsOfWinners =
                db.query(
                        "SELECT seats_json FROM seat_hold WHERE show_id = ? AND status IN"
                                + " ('ACTIVE', 'CONSUMED')",
                        r -> r.getString(1),
                        showId);
        Set<String> distinct = new HashSet<>();
        int total = 0;
        for (String json : seatsOfWinners) {
            for (Object o : Json.array(Json.parse(json))) {
                total++;
                assertTrue(distinct.add((String) o), "seat held twice: " + o);
            }
        }
        int lockRows =
                db.queryOne(
                                "SELECT count(*) FROM seat_lock WHERE show_id = ?",
                                r -> r.getInt(1),
                                showId)
                        .orElseThrow();
        assertEquals(total, lockRows);
        assertEquals(held.get(), seatsOfWinners.size());
        assertTrue(lockRows <= POOL && lockRows <= screen.capacity());
        assertEquals(
                booked.get(),
                db.queryOne(
                                "SELECT count(*) FROM booking WHERE show_id = ?",
                                r -> r.getInt(1),
                                showId)
                        .orElseThrow());
        int bookedSeats =
                db.queryOne(
                                "SELECT count(*) FROM booking_seat bs JOIN booking b ON b.id ="
                                        + " bs.booking_id WHERE b.show_id = ?",
                                r -> r.getInt(1),
                                showId)
                        .orElseThrow();
        assertTrue(bookedSeats <= lockRows && bookedSeats <= screen.capacity());

        // Once the clock passes every expiry the sweeper leaves nothing ACTIVE and no locks.
        Clock later = Clock.fixed(clock.instant().plus(Duration.ofHours(1)), ZoneOffset.UTC);
        new ExpireSweep(uow, quiet, later).run();
        assertEquals(
                0,
                db.queryOne(
                                "SELECT count(*) FROM seat_hold WHERE status = 'ACTIVE'",
                                r -> r.getInt(1))
                        .orElseThrow());
        assertEquals(
                0, db.queryOne("SELECT count(*) FROM seat_lock", r -> r.getInt(1)).orElseThrow());
    }
}
