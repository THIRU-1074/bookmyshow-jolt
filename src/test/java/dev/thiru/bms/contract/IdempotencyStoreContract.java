package dev.thiru.bms.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.IdempotencyStore;
import in.jolt.IdempotencyStore.Claim;
import in.jolt.IdempotencyStore.Key;
import in.jolt.IdempotencyStore.State;
import in.jolt.IdempotencyStore.Stored;
import java.nio.charset.StandardCharsets;
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
}
