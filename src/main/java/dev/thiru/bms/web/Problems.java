package dev.thiru.bms.web;

import dev.thiru.bms.domain.DomainException;
import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.HttpStatus;
import in.jolt.JsonMapper;
import in.jolt.Middleware;
import in.jolt.Reply;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.ValidationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns every failure into {@code application/problem+json} with a stable {@code code} (SPEC 7).
 * One middleware catches what handlers throw so the body can carry the request id, which Jolt's own
 * error mappers cannot see.
 *
 * <p>TODO(jolt#77): fold into {@code app.onError} once a mapper can read the request.
 */
final class Problems {

    private static final System.Logger LOG = System.getLogger("bms.web");

    /** The wire shape before serialisation. */
    record Problem(
            int status,
            String code,
            String detail,
            Map<String, String> errors,
            List<String> conflicts) {}

    private Problems() {}

    static Problem of(Throwable e) {
        if (e instanceof ApiException a) {
            return new Problem(a.status(), a.code(), a.getMessage(), a.errors(), a.conflicts());
        }
        if (e instanceof DomainException d) {
            return new Problem(
                    statusOf(d.code()), d.code().name(), d.getMessage(), d.errors(), d.conflicts());
        }
        if (e instanceof ValidationException v) {
            return new Problem(422, "VALIDATION", "Validation failed", v.errors(), List.of());
        }
        if (e instanceof HttpException h) {
            return new Problem(h.status(), codeOf(h.status()), h.getMessage(), Map.of(), List.of());
        }
        return new Problem(500, "INTERNAL", "Internal server error", Map.of(), List.of());
    }

    static int statusOf(DomainException.Code code) {
        return switch (code) {
            case VALIDATION -> 422;
            case NOT_FOUND -> 404;
            case HOLD_EXPIRED -> 410;
            case LIMIT_EXCEEDED -> 429;
            case UNAUTHENTICATED -> 401;
            case SEAT_UNAVAILABLE,
                    BOOKING_STATE,
                    PAYMENT_IN_PROGRESS,
                    SHOW_STARTED,
                    VERSION_CONFLICT,
                    CONFLICT ->
                    409;
        };
    }

    static String codeOf(int status) {
        return switch (status) {
            case 400 -> "VALIDATION";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 429 -> "RATE_LIMITED";
            default -> HttpStatus.reasonFor(status).toUpperCase(Locale.ROOT).replace(' ', '_');
        };
    }

    static Map<String, Object> body(Problem p, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", HttpStatus.reasonFor(p.status()));
        body.put("status", p.status());
        body.put("detail", p.detail());
        body.put("code", p.code());
        if (!p.errors().isEmpty()) {
            body.put("errors", p.errors());
        }
        if (!p.conflicts().isEmpty()) {
            body.put("conflicts", p.conflicts());
        }
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return body;
    }

    /** Catches handler failures and writes the problem, including the request id. */
    static Middleware middleware(JsonMapper mapper) {
        return (req, res, next) -> {
            try {
                next.run();
            } catch (Exception e) {
                write(req, res, mapper, e);
            }
        };
    }

    static void write(Request req, Response res, JsonMapper mapper, Throwable e) {
        Problem p = of(e);
        if (p.status() >= 500) {
            // Class name and request id only: messages can carry user data.
            LOG.log(
                    System.Logger.Level.ERROR,
                    "unhandled " + e.getClass().getName() + " requestId=" + req.id());
        }
        res.status(p.status())
                .header("Content-Type", "application/problem+json")
                .send(mapper.write(body(p, req.id())));
    }

    /** Fallback for errors raised outside the middleware chain (no request id available). */
    static void install(App app) {
        app.onError(ApiException.class, e -> reply(e));
        app.onError(DomainException.class, e -> reply(e));
        app.onError(ValidationException.class, e -> reply(e));
        app.onError(HttpException.class, e -> reply(e));
    }

    private static Reply<?> reply(Throwable e) {
        Problem p = of(e);
        return Reply.status(p.status(), body(p, null));
    }
}
