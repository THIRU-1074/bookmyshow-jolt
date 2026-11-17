package dev.thiru.bms.domain.port;

import java.time.Instant;

/**
 * Remembers responses to requests that carried an {@code Idempotency-Key} (SPEC 5.7). Scope is the
 * authenticated user, so one user's key never collides with another's.
 */
public interface IdempotencyStore {

    record Stored(int status, String contentType, byte[] body) {}

    enum Kind {
        /** First time this key is seen: the caller runs the request. */
        FRESH,
        /** A finished request with the same fingerprint: replay {@code stored}. */
        REPLAY,
        /** Same key, different method, path or body. */
        MISMATCH,
        /** Same key, the first request is still running. */
        IN_FLIGHT
    }

    record Claim(Kind kind, Stored stored) {}

    /** Atomically claims the key when unseen; otherwise reports what is already there. */
    Claim begin(long userId, String key, String fingerprint, Instant now);

    void complete(long userId, String key, Stored response);

    /** Forgets an in-flight claim after a failure so the client may retry. */
    void release(long userId, String key);
}
