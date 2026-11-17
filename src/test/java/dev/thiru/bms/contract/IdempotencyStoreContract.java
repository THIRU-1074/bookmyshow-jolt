package dev.thiru.bms.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.thiru.bms.domain.port.IdempotencyStore;
import dev.thiru.bms.domain.port.IdempotencyStore.Claim;
import dev.thiru.bms.domain.port.IdempotencyStore.Kind;
import dev.thiru.bms.domain.port.IdempotencyStore.Stored;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Idempotency-Key store behind IdempotencyFilter (SPEC 5.7). */
public abstract class IdempotencyStoreContract {

    protected static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final byte[] BODY = "{\"id\":\"b1\"}".getBytes(StandardCharsets.UTF_8);

    private IdempotencyStore store;

    protected abstract IdempotencyStore newStore();

    @BeforeEach
    protected void openStore() {
        store = newStore();
    }

    @Test
    protected void aNewKeyIsFreshThenInFlightThenReplaysTheStoredResponse() {
        assertEquals(Kind.FRESH, store.begin(1, "k", "POST /a h", NOW).kind());
        assertEquals(Kind.IN_FLIGHT, store.begin(1, "k", "POST /a h", NOW).kind());
        store.complete(1, "k", new Stored(201, "application/json", BODY));
        Claim replay = store.begin(1, "k", "POST /a h", NOW);
        assertEquals(Kind.REPLAY, replay.kind());
        assertEquals(201, replay.stored().status());
        assertEquals("application/json", replay.stored().contentType());
        assertArrayEquals(BODY, replay.stored().body());
    }

    @Test
    protected void anotherRequestUnderTheSameKeyIsAMismatchInFlightOrDone() {
        store.begin(1, "k", "POST /a h", NOW);
        assertEquals(Kind.MISMATCH, store.begin(1, "k", "POST /a other", NOW).kind());
        store.complete(1, "k", new Stored(200, "application/json", BODY));
        assertEquals(Kind.MISMATCH, store.begin(1, "k", "POST /a other", NOW).kind());
    }

    @Test
    protected void keysAreScopedPerUser() {
        store.begin(1, "k", "POST /a h", NOW);
        assertEquals(Kind.FRESH, store.begin(2, "k", "POST /a other", NOW).kind());
    }

    @Test
    protected void releasingAnInFlightClaimLetsTheKeyBeReused() {
        store.begin(1, "k", "POST /a h", NOW);
        store.release(1, "k");
        assertEquals(Kind.FRESH, store.begin(1, "k", "POST /a other", NOW).kind());
    }

    @Test
    protected void releasingAfterCompletionKeepsReplayingTheStoredResponse() {
        store.begin(1, "k", "POST /a h", NOW);
        store.complete(1, "k", new Stored(200, "application/json", BODY));
        store.release(1, "k");
        assertEquals(Kind.REPLAY, store.begin(1, "k", "POST /a h", NOW).kind());
    }

    @Test
    protected void anEmptyBodyReplaysAsEmpty() {
        store.begin(1, "k", "DELETE /a", NOW);
        store.complete(1, "k", new Stored(204, null, new byte[0]));
        Claim replay = store.begin(1, "k", "DELETE /a", NOW);
        assertEquals(204, replay.stored().status());
        assertArrayEquals(new byte[0], replay.stored().body());
    }
}
