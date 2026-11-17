package dev.thiru.bms.web;

import dev.thiru.bms.domain.DomainException;
import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.HttpStatus;
import in.jolt.Middleware;
import in.jolt.Reply;
import in.jolt.ValidationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns every failure into {@code application/problem+json} with a stable {@code code} (SPEC 7).
 * The bodies come from request-aware {@code app.onError} mappers; Jolt adds {@code requestId}.
 *
 * <p>{@link #middleware} only classifies before Jolt sees a failure: Jolt logs every mapped
 * exception that is not an {@link HttpException} at WARNING with its stack trace, which would log
 * each expected seat conflict and break SPEC 9's one line per request. So a domain error is
 * re-thrown as an {@link ApiException}, and anything unexpected is logged here by class and request
 * id (messages can carry user data) and re-thrown as a bare 500. See docs/JOLT_GAPS.md.
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
            String detail = h.status() == 500 ? "Internal server error" : h.getMessage();
            return new Problem(h.status(), codeOf(h.status()), detail, Map.of(), List.of());
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
            case 500 -> "INTERNAL";
            default -> HttpStatus.reasonFor(status).toUpperCase(Locale.ROOT).replace(' ', '_');
        };
    }

    static Map<String, Object> body(Problem p) {
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
        return body;
    }

    /** Classifies failures from everything after it; see the class comment. */
    static Middleware middleware() {
        return (req, res, next) -> {
            try {
                next.run();
            } catch (HttpException e) {
                throw e;
            } catch (DomainException e) {
                Problem p = of(e);
                throw new ApiException(p.status(), p.code(), p.detail(), p.errors(), p.conflicts());
            } catch (Exception e) {
                // Class name and request id only: messages can carry user data.
                LOG.log(
                        System.Logger.Level.ERROR,
                        "unhandled " + e.getClass().getName() + " requestId=" + req.id());
                throw new HttpException(500, "Internal server error");
            }
        };
    }

    /** The problem+json mappers; Jolt adds {@code requestId} when the request has one. */
    static void install(App app) {
        app.onError(HttpException.class, (e, req) -> reply(e));
        app.onError(DomainException.class, (e, req) -> reply(e));
        app.onError(Exception.class, (e, req) -> reply(e));
    }

    private static Reply<?> reply(Throwable e) {
        Problem p = of(e);
        return Reply.status(p.status(), body(p));
    }
}
