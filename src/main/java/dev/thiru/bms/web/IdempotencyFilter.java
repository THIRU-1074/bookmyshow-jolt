package dev.thiru.bms.web;

import dev.thiru.bms.domain.port.IdempotencyStore;
import in.jolt.Middleware;
import in.jolt.Response;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;

/**
 * Required {@code Idempotency-Key} on state-changing POSTs (SPEC 5.7): the first success is stored
 * and replayed byte for byte; the same key with a different request is 422, a request still running
 * is 409. Failed requests are not stored (their transaction rolled back, so running them again is
 * safe) and release the key.
 *
 * <p>TODO(jolt#54): delete when Jolt ships {@code Idempotency.keys()}.
 */
final class IdempotencyFilter {

    private static final int MAX_KEY = 128;

    private final IdempotencyStore store;
    private final Clock clock;

    IdempotencyFilter(IdempotencyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Route middleware; must run after authentication so the scope is a user id. */
    Middleware required() {
        return (req, res, next) -> {
            String key = req.header("Idempotency-Key");
            if (key == null || !validKey(key)) {
                throw new ApiException(
                        400,
                        "IDEMPOTENCY",
                        "Idempotency-Key header is required (1 to 128 printable ASCII characters)");
            }
            long userId = Principal.of(req).userId();
            String fingerprint = req.method() + " " + req.path() + " " + sha256(req.body());
            IdempotencyStore.Claim claim = store.begin(userId, key, fingerprint, clock.instant());
            switch (claim.kind()) {
                case REPLAY -> {
                    IdempotencyStore.Stored s = claim.stored();
                    res.status(s.status())
                            .header("Content-Type", s.contentType())
                            .header("Idempotency-Replayed", "true")
                            .send(s.body());
                }
                case MISMATCH ->
                        throw new ApiException(
                                422,
                                "IDEMPOTENCY",
                                "Idempotency-Key was already used with a different request");
                case IN_FLIGHT ->
                        throw new ApiException(
                                409,
                                "IDEMPOTENCY",
                                "A request with this Idempotency-Key is still running");
                case FRESH -> runAndStore(userId, key, res, next);
            }
        };
    }

    private void runAndStore(long userId, String key, Response res, Middleware.Next next)
            throws Exception {
        try {
            next.run();
        } catch (Exception | Error e) {
            store.release(userId, key);
            throw e;
        }
        int status = res.statusCode();
        if (status >= 200 && status < 300) {
            String type = "application/json";
            for (Map.Entry<String, String> h : res.headers()) {
                if (h.getKey().equalsIgnoreCase("Content-Type")) {
                    type = h.getValue();
                }
            }
            store.complete(userId, key, new IdempotencyStore.Stored(status, type, res.body()));
        } else {
            store.release(userId, key);
        }
    }

    static boolean validKey(String key) {
        if (key.isEmpty() || key.length() > MAX_KEY) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(body == null ? new byte[0] : body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
