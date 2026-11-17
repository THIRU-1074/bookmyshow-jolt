package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.db.Db;
import in.jolt.db.DbException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Applies the real migrations to a temp-file database and probes the constraints. */
class SchemaTest {

    @TempDir Path dir;

    Db db;

    @BeforeEach
    void open() {
        String url = Sqlite.jdbcUrl(dir.resolve("bms.db"));
        SchemaRunner.migrate(url);
        db = Sqlite.open(url);
        // Fixture ids start at 9000 so they never collide with seed rows.
        db.update("INSERT INTO app_user VALUES (9000, 'u@example.test', 'x', 'U', 'AUDIENCE', 0)");
        db.update(
                "INSERT INTO movie VALUES (9000, 'Fixture', 'U', 100, '2026-01-01', '/p.jpg', 'd')");
        db.update("INSERT INTO city VALUES ('fixture-city', 'Fixture City', 'Asia/Kolkata')");
        db.update("INSERT INTO cinema VALUES (9000, 'fixture-city', 'Fixture Cinema', 'addr')");
        db.update("INSERT INTO screen VALUES (9000, 9000, 'S1', '{}', 10)");
        db.update(
                "INSERT INTO show VALUES (9000, 9000, 9000, 1000, 2000, 'en', '2D', '{}', 'SCHEDULED')");
        db.update("INSERT INTO seat_hold VALUES ('h1', 9000, 9000, '[\"A1\"]', 0, 1000, 'ACTIVE')");
        db.update("INSERT INTO seat_hold VALUES ('h2', 9000, 9000, '[\"A1\"]', 0, 1000, 'ACTIVE')");
    }

    @AfterEach
    void close() {
        db.close();
    }

    private void rejects(String sql, Object... args) {
        assertThrows(DbException.class, () -> db.update(sql, args), sql);
    }

    @Test
    void migrationsAreRecorded() {
        assertEquals(
                1,
                db.queryOne("SELECT min(version) FROM schema_history", r -> r.getInt(1))
                        .orElseThrow());
    }

    @Test
    void everyTableIsStrict() {
        List<String> loose =
                db.query(
                        "SELECT name FROM pragma_table_list WHERE schema = 'main'"
                                + " AND type = 'table' AND strict = 0 AND name NOT LIKE 'sqlite_%'",
                        r -> r.getString(1));
        assertEquals(List.of(), loose);
    }

    @Test
    void seatLockPrimaryKeyBlocksADoubleInsert() {
        db.update("INSERT INTO seat_lock VALUES (9000, 'A1', 'h1', NULL, 'HELD', 5000)");
        rejects("INSERT INTO seat_lock VALUES (9000, 'A1', 'h2', NULL, 'HELD', 5000)");
        assertEquals(
                1, db.queryOne("SELECT count(*) FROM seat_lock", r -> r.getInt(1)).orElseThrow());
    }

    @Test
    void seatLockStateAndExpiryMustAgree() {
        rejects("INSERT INTO seat_lock VALUES (9000, 'B1', 'h1', NULL, 'HELD', NULL)");
        rejects("INSERT INTO seat_lock VALUES (9000, 'B2', 'h1', NULL, 'BOOKED', 5000)");
        rejects("INSERT INTO seat_lock VALUES (9000, 'B3', 'h1', NULL, 'GONE', NULL)");
    }

    @Test
    void foreignKeysAreEnforced() {
        rejects("INSERT INTO seat_lock VALUES (9999, 'A1', 'h1', NULL, 'HELD', 5000)");
        rejects("INSERT INTO cinema VALUES (9001, 'no-such-city', 'C', 'a')");
    }

    @Test
    void screenCapacityMustBeWithinOneAndAThousand() {
        rejects("INSERT INTO screen VALUES (9001, 9000, 'Zero', '{}', 0)");
        rejects("INSERT INTO screen VALUES (9001, 9000, 'Huge', '{}', 1001)");
        db.update("INSERT INTO screen VALUES (9001, 9000, 'Min', '{}', 1)");
        db.update("INSERT INTO screen VALUES (9002, 9000, 'Max', '{}', 1000)");
    }

    @Test
    void movieChecks() {
        rejects("INSERT INTO movie VALUES (9001, 'T', 'X', 100, '2026-01-01', '/p', 'd')");
        rejects("INSERT INTO movie VALUES (9001, 'T', 'U', 0, '2026-01-01', '/p', 'd')");
        rejects("INSERT INTO movie VALUES (9001, 'T', 'U', 401, '2026-01-01', '/p', 'd')");
    }

    @Test
    void showFormatAndStatusChecks() {
        rejects("INSERT INTO show VALUES (9001, 9000, 9000, 1, 2, 'en', '4K', '{}', 'SCHEDULED')");
        rejects("INSERT INTO show VALUES (9001, 9000, 9000, 1, 2, 'en', '2D', '{}', 'DONE')");
    }

    @Test
    void holdBookingAndPaymentStatusChecks() {
        rejects("INSERT INTO seat_hold VALUES ('h3', 9000, 9000, '[]', 0, 1, 'LOST')");
        rejects(
                "INSERT INTO booking (id, code, user_id, show_id, hold_id, amount, status,"
                        + " expires_at, created_at, updated_at)"
                        + " VALUES ('b1', 'BK00000000', 9000, 9000, 'h1', 100, 'NOPE', 1, 0, 0)");
        rejects(
                "INSERT INTO booking (id, code, user_id, show_id, hold_id, amount, status,"
                        + " expires_at, created_at, updated_at)"
                        + " VALUES ('b1', 'BK00000000', 9000, 9000, 'h1', 0, 'HELD', 1, 0, 0)");
    }

    @Test
    void refundCannotExceedPaymentAmount() {
        db.update(
                "INSERT INTO booking (id, code, user_id, show_id, hold_id, amount, status,"
                        + " expires_at, created_at, updated_at)"
                        + " VALUES ('b1', 'BK00000000', 9000, 9000, 'h1', 100, 'HELD', 1, 0, 0)");
        rejects(
                "INSERT INTO payment (id, booking_id, amount, status, refunded_amount, created_at,"
                        + " updated_at) VALUES ('p1', 'b1', 100, 'SUCCEEDED', 101, 0, 0)");
        db.update(
                "INSERT INTO payment (id, booking_id, amount, status, refunded_amount, created_at,"
                        + " updated_at) VALUES ('p1', 'b1', 100, 'SUCCEEDED', 100, 0, 0)");
        rejects(
                "INSERT INTO booking (id, code, user_id, show_id, hold_id, amount, status,"
                        + " expires_at, created_at, updated_at)"
                        + " VALUES ('b2', 'BK00000000', 9000, 9000, 'h2', 100, 'HELD', 1, 0, 0)");
    }

    @Test
    void strictTablesRejectWrongTypes() {
        rejects(
                "INSERT INTO app_user VALUES (9001, 'v@example.test', 'x', 'U', 'AUDIENCE', 'later')");
    }

    @Test
    void cinemaOwnerPairsAreUniqueAndReferenceRealRows() {
        db.update("INSERT INTO cinema_owner VALUES (9000, 9000)");
        rejects("INSERT INTO cinema_owner VALUES (9000, 9000)");
        rejects("INSERT INTO cinema_owner VALUES (9999, 9000)");
        rejects("INSERT INTO cinema_owner VALUES (9000, 9999)");
        rejects("INSERT INTO cinema_owner VALUES ('x', 9000)");
    }

    @Test
    void cinemaOwnerLinksAreIndexedByUserAndGoWithTheirCinema() {
        assertEquals(
                List.of("user_id"),
                db.query(
                        "SELECT name FROM pragma_index_info('cinema_owner_user')",
                        r -> r.getString(1)));
        db.update("INSERT INTO cinema_owner VALUES (9000, 9000)");
        db.update("DELETE FROM seat_hold");
        db.update("DELETE FROM show WHERE id = 9000");
        db.update("DELETE FROM screen WHERE cinema_id = 9000");
        db.update("DELETE FROM cinema WHERE id = 9000");
        assertEquals(
                0,
                db.queryOne("SELECT count(*) FROM cinema_owner", r -> r.getInt(1)).orElseThrow());
    }

    @Test
    void v4RewritesTheStoredUserRoleToAudience() throws IOException {
        String script;
        try (InputStream in = getClass().getResourceAsStream("/db/migrations/V4__roles.sql")) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String rewrite =
                SchemaRunner.split(script).stream()
                        .filter(s -> s.startsWith("UPDATE app_user"))
                        .findFirst()
                        .orElseThrow();
        db.update("INSERT INTO app_user VALUES (9001, 'a@example.test', 'x', 'A', 'USER', 0)");
        db.update(
                "INSERT INTO app_user VALUES (9002, 'b@example.test', 'x', 'B', 'ADMIN,USER', 0)");
        db.update(
                "INSERT INTO app_user VALUES (9003, 'c@example.test', 'x', 'C', 'USER,ADMIN', 0)");
        db.update("UPDATE app_user SET roles = 'USER' WHERE id = 9000");
        db.update(rewrite);
        assertEquals(
                List.of("AUDIENCE", "ADMIN,AUDIENCE", "AUDIENCE,ADMIN", "AUDIENCE"),
                db.query(
                        "SELECT roles FROM app_user WHERE id IN (9001, 9002, 9003, 9000)"
                                + " ORDER BY CASE id WHEN 9000 THEN 1 ELSE 0 END, id",
                        r -> r.getString(1)));
        // Running it again changes nothing.
        db.update(rewrite);
        assertEquals(
                0,
                db.queryOne(
                                "SELECT count(*) FROM app_user WHERE ',' || roles || ','"
                                        + " LIKE '%,USER,%'",
                                r -> r.getInt(1))
                        .orElseThrow());
    }

    @Test
    void emailAndWebhookEventIdsAreUnique() {
        rejects("INSERT INTO app_user VALUES (9001, 'u@example.test', 'x', 'U', 'AUDIENCE', 0)");
        db.update("INSERT INTO payment_event VALUES ('e1', 'p1', 'payment.succeeded', 0)");
        rejects("INSERT INTO payment_event VALUES ('e1', 'p1', 'payment.succeeded', 0)");
    }
}
