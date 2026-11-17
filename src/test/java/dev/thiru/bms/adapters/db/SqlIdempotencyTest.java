package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.thiru.bms.domain.port.IdempotencyStore.Kind;
import dev.thiru.bms.domain.port.IdempotencyStore.Stored;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqlIdempotencyTest extends DbTest {

    private SqlIdempotency store;

    @BeforeEach
    void create() {
        store = new SqlIdempotency(db);
    }

    private static final byte[] BODY = "{\"id\":\"b1\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void firstUseIsFreshThenInFlightUntilCompleted() {
        assertEquals(Kind.FRESH, store.begin(1, "k", "POST /a h", NOW).kind());
        assertEquals(Kind.IN_FLIGHT, store.begin(1, "k", "POST /a h", NOW).kind());
        store.complete(1, "k", new Stored(201, "application/json", BODY));
        var replay = store.begin(1, "k", "POST /a h", NOW);
        assertEquals(Kind.REPLAY, replay.kind());
        assertEquals(201, replay.stored().status());
        assertEquals("application/json", replay.stored().contentType());
        assertArrayEquals(BODY, replay.stored().body());
    }

    @Test
    void differentRequestUnderTheSameKeyIsAMismatch() {
        store.begin(1, "k", "POST /a h", NOW);
        assertEquals(Kind.MISMATCH, store.begin(1, "k", "POST /a other", NOW).kind());
    }

    @Test
    void keysAreScopedPerUser() {
        store.begin(1, "k", "POST /a h", NOW);
        assertEquals(Kind.FRESH, store.begin(2, "k", "POST /a other", NOW).kind());
    }

    @Test
    void releaseForgetsAnInFlightClaimButNotAStoredResponse() {
        store.begin(1, "k", "POST /a h", NOW);
        store.release(1, "k");
        assertEquals(Kind.FRESH, store.begin(1, "k", "POST /a other", NOW).kind());
        store.complete(1, "k", new Stored(200, "application/json", BODY));
        store.release(1, "k");
        assertEquals(Kind.REPLAY, store.begin(1, "k", "POST /a other", NOW).kind());
    }

    @Test
    void purgeDropsKeysOlderThanTheCutoff() {
        store.begin(1, "old", "f", NOW.minus(Duration.ofDays(2)));
        store.begin(1, "new", "f", NOW);
        assertEquals(1, store.purgeBefore(NOW.minus(Duration.ofDays(1))));
        assertEquals(Kind.FRESH, store.begin(1, "old", "g", NOW).kind());
        assertEquals(Kind.IN_FLIGHT, store.begin(1, "new", "f", NOW).kind());
    }
}
