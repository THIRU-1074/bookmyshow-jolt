package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.IdempotencyStore.Key;
import in.jolt.IdempotencyStore.State;
import in.jolt.IdempotencyStore.Stored;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqlIdempotencyTest extends DbTest {

    private static final byte[] BODY = "{\"id\":\"b1\"}".getBytes(StandardCharsets.UTF_8);
    private static final Key K = new Key("u:1", "k");

    private final MutableClock clock = new MutableClock(NOW);
    private SqlIdempotency store;

    @BeforeEach
    void create() {
        store = new SqlIdempotency(db, clock);
    }

    private static Stored stored(int status, Map.Entry<String, String>... headers) {
        return new Stored(status, List.of(headers), BODY);
    }

    @Test
    void firstUseStartsThenInFlightUntilCompletedThenReplays() {
        assertEquals(State.STARTED, store.begin(K, "f").state());
        assertEquals(State.IN_FLIGHT, store.begin(K, "f").state());
        store.complete(
                K,
                stored(
                        201,
                        Map.entry("Content-Type", "application/json"),
                        Map.entry("ETag", "W/\"a\"")));
        var replay = store.begin(K, "f");
        assertEquals(State.COMPLETED, replay.state());
        assertEquals(201, replay.response().status());
        assertEquals(
                List.of(
                        Map.entry("Content-Type", "application/json"),
                        Map.entry("ETag", "W/\"a\"")),
                replay.response().headers());
        assertArrayEquals(BODY, replay.response().body());
    }

    @Test
    void theRequestIdOfTheFirstRequestIsNotStored() {
        store.begin(K, "f");
        store.complete(
                K,
                stored(
                        201,
                        Map.entry("X-Request-Id", "first"),
                        Map.entry("Content-Type", "application/json")));
        assertEquals(
                List.of(Map.entry("Content-Type", "application/json")),
                store.begin(K, "f").response().headers());
    }

    @Test
    void differentRequestUnderTheSameKeyIsAMismatch() {
        store.begin(K, "f");
        assertEquals(State.MISMATCH, store.begin(K, "other").state());
    }

    @Test
    void keysAreScopedPerClient() {
        store.begin(K, "f");
        assertEquals(State.STARTED, store.begin(new Key("u:2", "k"), "other").state());
    }

    @Test
    void releaseForgetsAnInFlightClaimButNotAStoredResponse() {
        store.begin(K, "f");
        store.release(K);
        assertEquals(State.STARTED, store.begin(K, "other").state());
        store.complete(K, stored(200));
        store.release(K);
        assertEquals(State.COMPLETED, store.begin(K, "other").state());
    }

    @Test
    void purgeDropsKeysOlderThanTheCutoff() {
        clock.set(NOW.minus(Duration.ofDays(2)));
        store.begin(new Key("u:1", "old"), "f");
        clock.set(NOW);
        store.begin(new Key("u:1", "new"), "f");
        assertEquals(1, store.purgeBefore(NOW.minus(Duration.ofDays(1))));
        assertEquals(State.STARTED, store.begin(new Key("u:1", "old"), "g").state());
        assertEquals(State.IN_FLIGHT, store.begin(new Key("u:1", "new"), "f").state());
    }

    @Test
    void aClaimOlderThanTheLeaseIsReclaimedByTheSameRequestOnly() {
        store.begin(K, "f");
        clock.set(NOW.plus(SqlIdempotency.LEASE).minusMillis(1));
        assertEquals(State.IN_FLIGHT, store.begin(K, "f").state());
        clock.set(NOW.plus(SqlIdempotency.LEASE).plusMillis(1));
        assertEquals(State.MISMATCH, store.begin(K, "g").state());
        assertEquals(State.STARTED, store.begin(K, "f").state());
        // The reclaim restarted the lease, so the new owner is in flight, not reclaimable at once.
        assertEquals(State.IN_FLIGHT, store.begin(K, "f").state());
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant t) {
            now = t;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
