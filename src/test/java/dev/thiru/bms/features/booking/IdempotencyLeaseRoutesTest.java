package dev.thiru.bms.features.booking;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.thiru.bms.adapters.db.SqlIdempotency;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.SliceKit;
import in.jolt.IdempotencyStore;
import in.jolt.IdempotencyStore.Key;
import in.jolt.test.JoltTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A request killed mid-flight leaves an unfinished claim; the lease (SPEC 5.7) lets the same
 * request take it over instead of answering 409 for 24 hours. The production SQL store runs behind
 * the production pipeline, with the clock moved by hand.
 */
class IdempotencyLeaseRoutesTest {

    @RegisterExtension
    final SliceKit kit =
            SliceKit.builder()
                    .sqlIdempotency()
                    .routes(
                            k ->
                                    List.of(
                                            new BookingRoutes(
                                                    k.db(),
                                                    k.events(),
                                                    k.ids(),
                                                    k.clock(),
                                                    k.policy(),
                                                    k.gateway(),
                                                    k.pipeline().idempotent(),
                                                    k.pipeline().holdLimit())))
                    .boot();

    final String ada = kit.audience("ada@example.com");
    final long show = firstShow();

    private long firstShow() {
        Instant from = kit.seedDate().plusDays(3).atStartOfDay(SliceKit.IST).toInstant();
        return kit.db()
                .inTransaction(
                        tx ->
                                tx.queryOne(
                                                "SELECT id FROM show WHERE screen_id = 1 AND"
                                                        + " format = '2D' AND starts_at >= ?"
                                                        + " ORDER BY starts_at LIMIT 1",
                                                rs -> rs.getLong(1),
                                                Db.millis(from))
                                        .orElseThrow());
    }

    private JoltTest.Call hold(String key, String... seats) {
        return kit.http()
                .post("/api/holds")
                .header("Authorization", SliceKit.bearer(ada))
                .header("Idempotency-Key", key)
                .json(Map.of("showId", show, "seats", List.of(seats)));
    }

    /** What a crashed process leaves: a claim under the request's real fingerprint, no response. */
    private void crashAfterClaiming(String key) {
        // The fingerprint is Jolt's, so run the request for real, then put the row back as a kill
        // before complete() would have left it and undo the request's effects.
        hold(key, "A2").expect(201);
        kit.testDb()
                .joltDb()
                .update(
                        "UPDATE idempotency_key SET status = NULL, headers = NULL, body = NULL"
                                + " WHERE key = ?",
                        key);
        kit.db().inTransaction(tx -> tx.update("DELETE FROM seat_lock"));
        kit.db().inTransaction(tx -> tx.update("DELETE FROM seat_hold"));
        kit.events().published.clear();
    }

    @Test
    void aFreshInFlightClaimStillAnswers409WithRetryAfter() {
        crashAfterClaiming("k1");
        kit.mutableClock().advance(SqlIdempotency.LEASE.minusSeconds(1));
        var res = hold("k1", "A2").expect(409).jsonEquals("code", "IDEMPOTENCY");
        assertNotNull(res.header("Retry-After"));
    }

    @Test
    void aStuckClaimIsReclaimedAfterTheLeaseAndTheRequestRuns() {
        crashAfterClaiming("k2");
        kit.mutableClock().advance(SqlIdempotency.LEASE.plusSeconds(1));
        var res = hold("k2", "A2").expect(201);
        var replay = hold("k2", "A2").expect(201).header("Idempotency-Replayed", "true");
        assertArrayEquals(res.bytes(), replay.bytes());
        assertEquals(1, kit.events().published.size(), "the retry ran exactly once");
    }

    @Test
    void aDifferentBodyOnAStuckClaimIsStill422() {
        crashAfterClaiming("k3");
        kit.mutableClock().advance(SqlIdempotency.LEASE.plusSeconds(1));
        hold("k3", "A3").expect(422).jsonEquals("code", "IDEMPOTENCY");
    }

    @Test
    void aCompletedResponseStillReplaysAfterTheLease() {
        hold("k4", "A2").expect(201);
        kit.mutableClock().advance(SqlIdempotency.LEASE.multipliedBy(5));
        hold("k4", "A2").expect(201).header("Idempotency-Replayed", "true");
    }

    @Test
    void theStoreIsTheSqlOne() {
        IdempotencyStore store = kit.idempotency();
        assertEquals(SqlIdempotency.class, store.getClass());
        assertEquals(IdempotencyStore.State.STARTED, store.begin(new Key("x", "y"), "f").state());
    }
}
