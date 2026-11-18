package dev.thiru.bms.web;

import in.jolt.Idempotency;
import in.jolt.IdempotencyStore;
import in.jolt.Middleware;
import in.jolt.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Required {@code Idempotency-Key} on state-changing POSTs (SPEC 5.7) through Jolt's {@link
 * Idempotency}, scoped to the verified user (route middleware after the RBAC gate).
 *
 * <p>Jolt 0.4.1 writes its own 400, 409, 422 and 503 refusals as plain text. They are re-thrown
 * here, so they leave as problem+json with {@code code: IDEMPOTENCY} and the request id, keeping
 * {@code Retry-After}. A replay echoes the current request id.
 *
 * <p>TODO(jolt#85): install {@code Idempotency.keys()} directly once its refusals go through the
 * error path and replays keep the current request id.
 */
final class Idempotent {

    private static final String REPLAYED = "Idempotency-Replayed";

    private Idempotent() {}

    static Middleware required(IdempotencyStore store) {
        Idempotency keys = Idempotency.keys().store(store).required();
        return (req, res, next) -> {
            boolean[] ran = {false};
            keys.handle(
                    req,
                    res,
                    () -> {
                        ran[0] = true;
                        next.run();
                    });
            if (ran[0]) {
                return;
            }
            if (header(res, REPLAYED) != null) {
                if (req.id() != null) {
                    res.header("X-Request-Id", req.id());
                }
                return;
            }
            int status = res.statusCode();
            String detail = new String(res.body(), StandardCharsets.UTF_8).trim();
            throw new ApiException(
                    status, status == 503 ? "SERVICE_UNAVAILABLE" : "IDEMPOTENCY", detail);
        };
    }

    private static String header(Response res, String name) {
        for (Map.Entry<String, String> h : res.headers()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                return h.getValue();
            }
        }
        return null;
    }
}
