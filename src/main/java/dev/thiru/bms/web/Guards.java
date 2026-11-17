package dev.thiru.bms.web;

import in.jolt.HttpException;
import in.jolt.HttpStatus;
import in.jolt.JsonMapper;
import in.jolt.Middleware;
import in.jolt.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Jolt's stock middleware (JWT, roles, CSRF, rate limits) answers a refusal itself with a plain
 * body. Running it against a scratch response lets a refusal be re-thrown as an exception, so it
 * leaves through {@link Problems} with a stable code and the request id.
 *
 * <p>TODO(jolt#76): delete when stock refusals honour {@code problemJson} or raise exceptions.
 */
final class Guards {

    private Guards() {}

    static Middleware adapt(Middleware stock, JsonMapper mapper) {
        return (req, res, next) -> {
            boolean[] passed = {false};
            Response scratch = new Response(mapper);
            stock.handle(
                    req,
                    scratch,
                    () -> {
                        passed[0] = true;
                        next.run();
                    });
            if (passed[0]) {
                return;
            }
            for (Map.Entry<String, String> h : scratch.headers()) {
                String name = h.getKey();
                if (name.equalsIgnoreCase("Retry-After")
                        || name.equalsIgnoreCase("WWW-Authenticate")) {
                    res.header(name, h.getValue());
                }
            }
            int status = scratch.statusCode() >= 400 ? scratch.statusCode() : 403;
            String detail = new String(scratch.body(), StandardCharsets.UTF_8).trim();
            throw new HttpException(
                    status, detail.isEmpty() ? HttpStatus.reasonFor(status) : detail);
        };
    }
}
