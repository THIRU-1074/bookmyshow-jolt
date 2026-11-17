package dev.thiru.bms;

import in.jolt.Middleware;
import in.jolt.Request;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Startup and first-request facts about proxy trust. Behind Render, {@code req.scheme()} is https
 * only when the load balancer's peer address is inside TRUST_PROXY, and the peer's address format
 * is not known in advance; these two log lines show it.
 *
 * <p>Nothing secret is logged: no token, cookie, body, or header value except X-Forwarded-Proto,
 * and that only as {@code https}, {@code http} or {@code other}. Jolt exposes the peer address only
 * when it does not trust the peer; a trusted peer's request carries the forwarded client address
 * instead, which stays out of the log.
 */
final class ProxyDiagnostics {

    private ProxyDiagnostics() {}

    /** {@code trustProxy=[...]}, or {@code trustProxy=none}. */
    static String describe(List<String> trusted) {
        return trusted.isEmpty() ? "trustProxy=none" : "trustProxy=" + trusted;
    }

    /** Logs {@link #describe(Request)} once, for the first request that is not a health check. */
    static Middleware firstRequest(Consumer<String> log) {
        AtomicBoolean logged = new AtomicBoolean();
        return (req, res, next) -> {
            if (!isHealth(req.path()) && logged.compareAndSet(false, true)) {
                log.accept(describe(req));
            }
            next.run();
        };
    }

    static String describe(Request req) {
        String forwardedFor = req.header("X-Forwarded-For");
        String proto = proto(req.header("X-Forwarded-Proto"));
        String trusted = trusted(req, forwardedFor, proto);
        return "First request: peer="
                + ("yes".equals(trusted) ? "forwarded" : req.remoteAddress())
                + " xForwardedFor="
                + (forwardedFor == null ? "absent" : "present")
                + " xForwardedProto="
                + proto
                + " trusted="
                + trusted
                + " scheme="
                + req.scheme();
    }

    /**
     * Jolt does not say whether it trusted the peer, so this reads it off the request. The app
     * listens on plain HTTP, so an https scheme can only come from a trusted X-Forwarded-Proto; an
     * https proto that left the scheme at http was ignored. Otherwise a trusted peer replaces its
     * own address with an X-Forwarded-For entry. With neither header, trust changes nothing.
     */
    private static String trusted(Request req, String forwardedFor, String proto) {
        if ("https".equals(req.scheme())) {
            return "yes";
        }
        if ("https".equals(proto)) {
            return "no";
        }
        if (forwardedFor != null) {
            return Arrays.stream(forwardedFor.split(","))
                            .map(String::strip)
                            .anyMatch(e -> e.equals(req.remoteAddress()))
                    ? "yes"
                    : "no";
        }
        return "unknown";
    }

    /** The last X-Forwarded-Proto entry, as Jolt reads it, reduced to a fixed word. */
    private static String proto(String header) {
        if (header == null) {
            return "absent";
        }
        String last = header.substring(header.lastIndexOf(',') + 1).strip();
        if (last.equalsIgnoreCase("https")) {
            return "https";
        }
        return last.equalsIgnoreCase("http") ? "http" : "other";
    }

    private static boolean isHealth(String path) {
        return path.equals("/api/health") || path.startsWith("/_jolt/health");
    }
}
