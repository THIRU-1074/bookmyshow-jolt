package dev.thiru.bms.features.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.account.CinemaScope;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.MutableClock;
import dev.thiru.bms.testing.TestDb;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SYSTEM_DESIGN 12: the overlap check and the insert share one IMMEDIATE transaction, so two
 * schedulers racing for overlapping slots on one screen cannot both win. There is no database
 * constraint for overlap, so this race is the proof. Each round uses a fresh slot on screen 5.
 */
class ShowOverlapRaceTest {

    private static final Map<String, Long> PRICES = Map.of("CLASSIC", 15000L, "PRIME", 25000L);
    private static final int ROUNDS = 15;

    @TempDir Path dir;
    TestDb test;
    Db db;
    AdminCatalog admin;
    Instant base;

    @BeforeEach
    void open() {
        test = TestDb.open(dir);
        db = test.db();
        MutableClock clock =
                new MutableClock(
                        AdminFixtures.seedDate(db)
                                .atTime(LocalTime.of(8, 0))
                                .atZone(AdminFixtures.IST)
                                .toInstant());
        admin = new AdminCatalog(db, clock);
        base = clock.instant().plus(Duration.ofDays(30));
    }

    @AfterEach
    void close() {
        test.close();
    }

    private long showsInSlot(Instant from, Instant to) {
        return db.inTransaction(
                tx ->
                        tx.queryOne(
                                        "SELECT COUNT(*) FROM show WHERE screen_id = 5"
                                                + " AND starts_at >= ? AND starts_at < ?",
                                        rs -> rs.getLong(1),
                                        Db.millis(from),
                                        Db.millis(to))
                                .orElseThrow());
    }

    @Test
    void twoSchedulersRacingForOverlappingSlotsExactlyOneWins() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            // Each round is a day of its own; the two slots overlap by an hour.
            Instant a = base.plus(Duration.ofDays(round));
            Instant b = a.plus(Duration.ofMinutes(60));
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> outcomes = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                for (Instant start : List.of(a, b)) {
                    outcomes.add(
                            pool.submit(
                                    () -> {
                                        go.await();
                                        try {
                                            return admin.createShow(
                                                    CinemaScope.everything(),
                                                    1,
                                                    5,
                                                    start,
                                                    "en",
                                                    ShowFormat.TWO_D,
                                                    PRICES);
                                        } catch (DomainException e) {
                                            return e;
                                        }
                                    }));
                }
                go.countDown();
            }
            int wins = 0;
            for (Future<Object> f : outcomes) {
                Object r = f.get();
                if (r instanceof DomainException e) {
                    assertEquals(
                            Map.of("startsAt", "overlaps another show on this screen"), e.errors());
                } else {
                    wins++;
                }
            }
            assertEquals(1, wins, "round " + round + ": exactly one scheduler wins");
            assertEquals(
                    1,
                    showsInSlot(a.minus(Duration.ofHours(1)), b.plus(Duration.ofHours(1))),
                    "round " + round + ": the database holds one show");
        }
    }
}
