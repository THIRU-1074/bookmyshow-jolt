package dev.thiru.bms.features.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.kernel.catalog.SeatMap;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.MutableClock;
import dev.thiru.bms.testing.TestDb;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveSqlTest {

    @TempDir Path dir;
    TestDb test;
    MutableClock clock;
    LiveSql live;
    Instant start;

    @BeforeEach
    void open() {
        test = TestDb.open(dir);
        start =
                test.db()
                        .inTransaction(
                                tx ->
                                        tx.queryOne(
                                                        "SELECT starts_at FROM show WHERE id = 1",
                                                        rs -> Db.instant(rs, "starts_at"))
                                                .orElseThrow());
        clock = new MutableClock(start.minus(Duration.ofHours(2)));
        live = new LiveSql(test.db(), clock, BookingPolicy.defaults());
    }

    @AfterEach
    void close() {
        test.close();
    }

    @Test
    void showStateIsTheStatusAndStartOrNotFound() {
        assertEquals(new LiveSql.ShowState(ShowStatus.SCHEDULED, start), live.show(1));
        test.db().inTransaction(tx -> tx.update("UPDATE show SET status = 'CANCELLED'"));
        assertEquals(ShowStatus.CANCELLED, live.show(1).status());
        assertEquals(
                DomainException.Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> live.show(404_404)).code());
    }

    @Test
    void snapshotIsTheSeatMapWithLiveLocks() {
        SeatMap before = live.snapshot(1);
        assertEquals(1, before.showId());
        assertEquals(10, before.rows().size());
        assertEquals(List.of(5, 15), before.rows().get(0).aisleAfter());
        assertEquals(SeatMap.Status.BLOCKED, before.rows().get(0).seats().get(0).status());
        assertEquals(23000, before.categories().get(0).amount());
        assertEquals(420, before.holdTtlSeconds());

        hold(test.db(), 1, clock.instant().plusSeconds(60), "A2");
        SeatMap after = live.snapshot(1);
        assertEquals(SeatMap.Status.HELD, after.rows().get(0).seats().get(1).status());
        assertNotEquals(before.version(), after.version());
        clock.advance(Duration.ofMinutes(1));
        assertEquals(before.version(), live.snapshot(1).version(), "expired holds are free");
        assertEquals(
                DomainException.Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> live.snapshot(404_404)).code());
    }

    /** A HELD lock on {@code seat} for a fresh user. */
    static void hold(Db db, long showId, Instant expires, String seat) {
        db.inTransaction(
                tx -> {
                    long user =
                            tx.insert(
                                    "INSERT INTO app_user (email, password_hash, name, roles,"
                                            + " created_at) VALUES (?, 'x', 'T', 'AUDIENCE', 0)",
                                    "holder-" + seat + "@t.test");
                    String hold = "h" + user;
                    tx.update(
                            "INSERT INTO seat_hold (id, show_id, user_id, seats_json, created_at,"
                                    + " expires_at, status) VALUES (?, ?, ?, '[]', 0, ?, 'ACTIVE')",
                            hold,
                            showId,
                            user,
                            Db.millis(expires));
                    tx.update(
                            "INSERT INTO seat_lock (show_id, seat_id, hold_id, state, expires_at)"
                                    + " VALUES (?, ?, ?, 'HELD', ?)",
                            showId,
                            seat,
                            hold,
                            Db.millis(expires));
                    return null;
                });
    }
}
