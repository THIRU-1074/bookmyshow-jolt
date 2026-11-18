package dev.thiru.bms.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import in.jolt.IdempotencyStore;
import in.jolt.IdempotencyStore.Claim;
import in.jolt.IdempotencyStore.Key;
import in.jolt.IdempotencyStore.State;
import in.jolt.IdempotencyStore.Stored;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Jolt's {@link IdempotencyStore} SPI as {@code web.Idempotent} relies on it (SPEC 5.7): the web
 * tests run on Jolt's in-memory store and production on SQLite, so both must agree. Keys are scoped
 * per user by the caller ({@code u:<id>}).
 */
public abstract class IdempotencyStoreContract {

    private static final byte[] BODY = "{\"id\":\"b1\"}".getBytes(StandardCharsets.UTF_8);
    private static final Key K = new Key("u:1", "k");
    private static final List<Map.Entry<String, String>> JSON =
            List.of(Map.entry("Content-Type", "application/json; charset=utf-8"));

    private IdempotencyStore store;

    protected abstract IdempotencyStore newStore();

    /**
     * The lease after which an unfinished claim may be taken over, or null for a store that has
     * none. Jolt's in-memory store keeps a claim until it is completed or released and takes no
     * clock, so the lease clauses run only on stores that expire a crashed request's claim.
     */
    protected Duration lease() {
        return null;
    }

    /** Moves the store's clock forward; only called when {@link #lease()} is not null. */
    protected void advance(Duration d) {
        throw new UnsupportedOperationException();
    }

    @BeforeEach
    protected void openStore() {
        store = newStore();
    }

    @Test
    protected void aNewKeyStartsThenIsInFlightThenReplaysTheStoredResponse() {
        assertEquals(State.STARTED, store.begin(K, "POST /a h").state());
        assertEquals(State.IN_FLIGHT, store.begin(K, "POST /a h").state());
        store.complete(K, new Stored(201, JSON, BODY));
        Claim replay = store.begin(K, "POST /a h");
        assertEquals(State.COMPLETED, replay.state());
        assertEquals(201, replay.response().status());
        assertEquals(JSON, replay.response().headers());
        assertArrayEquals(BODY, replay.response().body());
    }

    @Test
    protected void anotherRequestUnderTheSameKeyIsAMismatchInFlightOrDone() {
        store.begin(K, "POST /a h");
        assertEquals(State.MISMATCH, store.begin(K, "POST /a other").state());
        store.complete(K, new Stored(200, JSON, BODY));
        assertEquals(State.MISMATCH, store.begin(K, "POST /a other").state());
    }

    @Test
    protected void keysAreScopedPerUser() {
        store.begin(K, "POST /a h");
        assertEquals(State.STARTED, store.begin(new Key("u:2", "k"), "POST /a other").state());
    }

    @Test
    protected void releasingAnInFlightClaimLetsTheKeyBeReused() {
        store.begin(K, "POST /a h");
        store.release(K);
        assertEquals(State.STARTED, store.begin(K, "POST /a other").state());
    }

    @Test
    protected void releasingAfterCompletionKeepsReplayingTheStoredResponse() {
        store.begin(K, "POST /a h");
        store.complete(K, new Stored(200, JSON, BODY));
        store.release(K);
        assertEquals(State.COMPLETED, store.begin(K, "POST /a h").state());
    }

    @Test
    protected void anEmptyBodyReplaysAsEmpty() {
        store.begin(K, "DELETE /a");
        store.complete(K, new Stored(204, List.of(), new byte[0]));
        Claim replay = store.begin(K, "DELETE /a");
        assertEquals(204, replay.response().status());
        assertArrayEquals(new byte[0], replay.response().body());
    }

    @Test
    protected void aStuckClaimIsReclaimedByTheSameRequestOnlyAfterTheLease() {
        assumeTrue(lease() != null, "this store has no claim lease");
        store.begin(K, "POST /a h"); // the process died here: never completed, never released
        advance(lease().minusSeconds(1));
        assertEquals(State.IN_FLIGHT, store.begin(K, "POST /a h").state(), "still leased");
        advance(Duration.ofSeconds(2));
        assertEquals(State.STARTED, store.begin(K, "POST /a h").state(), "lease over: reclaimed");
        assertEquals(State.IN_FLIGHT, store.begin(K, "POST /a h").state(), "a fresh lease again");
        store.complete(K, new Stored(201, JSON, BODY));
        assertEquals(State.COMPLETED, store.begin(K, "POST /a h").state());
    }

    @Test
    protected void aStuckClaimIsNeverTakenOverByADifferentRequest() {
        assumeTrue(lease() != null, "this store has no claim lease");
        store.begin(K, "POST /a h");
        advance(lease().plusSeconds(1));
        assertEquals(State.MISMATCH, store.begin(K, "POST /a other").state());
    }

    @Test
    protected void aCompletedResponseStillReplaysAfterTheLease() {
        assumeTrue(lease() != null, "this store has no claim lease");
        store.begin(K, "POST /a h");
        store.complete(K, new Stored(201, JSON, BODY));
        advance(lease().multipliedBy(10));
        assertEquals(State.COMPLETED, store.begin(K, "POST /a h").state());
    }
}
