package dev.thiru.bms.web;

import java.util.List;
import java.util.Optional;

/**
 * The single permission table for every {@code /api} and {@code /mock-gateway} route (SPEC 7).
 * {@link Rbac} enforces it and denies by default: a route missing here is answered 404 whatever its
 * handler does. {@code RbacMatrixTest} fails the build when this table, the registered routes and
 * {@code docs/RBAC.md} disagree.
 */
final class Permissions {

    /**
     * Who may call. OWNER and OWNER_OR_ADMIN additionally check the resource owner in the handler.
     */
    enum Access {
        /** No credentials. */
        PUBLIC,
        /** Verified by the request itself (webhook signature, refresh cookie), not by a JWT. */
        SELF_AUTHENTICATED,
        /** Any signed-in user. */
        USER,
        /** Signed in and owning the resource; a stranger gets 404. */
        OWNER,
        /** Owner, or an administrator; a stranger gets 404. */
        OWNER_OR_ADMIN,
        /** ADMIN role. */
        ADMIN;

        boolean needsToken() {
            return this != PUBLIC && this != SELF_AUTHENTICATED;
        }
    }

    record Rule(String method, String pattern, Access access) {}

    static final List<Rule> TABLE =
            List.of(
                    // Auth
                    r("POST", "/api/auth/register", Access.PUBLIC),
                    r("POST", "/api/auth/login", Access.PUBLIC),
                    r("POST", "/api/auth/refresh", Access.SELF_AUTHENTICATED),
                    r("POST", "/api/auth/logout", Access.SELF_AUTHENTICATED),
                    r("GET", "/api/me", Access.USER),
                    // Catalog and seats (public)
                    r("GET", "/api/cities", Access.PUBLIC),
                    r("GET", "/api/movies", Access.PUBLIC),
                    r("GET", "/api/movies/:id", Access.PUBLIC),
                    r("GET", "/api/movies/:id/shows", Access.PUBLIC),
                    r("GET", "/api/cinemas", Access.PUBLIC),
                    r("GET", "/api/cinemas/:id", Access.PUBLIC),
                    r("GET", "/api/shows/:id", Access.PUBLIC),
                    r("GET", "/api/shows/:id/seats", Access.PUBLIC),
                    r("GET", "/api/shows/:id/events", Access.PUBLIC),
                    // Holds and bookings
                    r("POST", "/api/holds", Access.USER),
                    r("DELETE", "/api/holds/:id", Access.OWNER),
                    r("POST", "/api/bookings", Access.USER),
                    r("GET", "/api/bookings", Access.USER),
                    r("GET", "/api/bookings/:id", Access.OWNER_OR_ADMIN),
                    r("POST", "/api/bookings/:id/checkout", Access.OWNER),
                    r("POST", "/api/bookings/:id/cancel", Access.OWNER),
                    r("GET", "/api/bookings/:id/ticket", Access.OWNER),
                    // Payments
                    r("POST", "/api/payments/webhook", Access.SELF_AUTHENTICATED),
                    // Mock gateway pages (only when MOCK_GATEWAY=true); the payment id is the
                    // unguessable capability, as at a real hosted checkout.
                    r("GET", "/mock-gateway/:id", Access.PUBLIC),
                    r("POST", "/mock-gateway/:id/complete", Access.PUBLIC),
                    // Admin
                    r("POST", "/api/admin/cities", Access.ADMIN),
                    r("PUT", "/api/admin/cities/:id", Access.ADMIN),
                    r("POST", "/api/admin/cinemas", Access.ADMIN),
                    r("PUT", "/api/admin/cinemas/:id", Access.ADMIN),
                    r("DELETE", "/api/admin/cinemas/:id", Access.ADMIN),
                    r("POST", "/api/admin/cinemas/:id/screens", Access.ADMIN),
                    r("PUT", "/api/admin/screens/:id", Access.ADMIN),
                    r("DELETE", "/api/admin/screens/:id", Access.ADMIN),
                    r("POST", "/api/admin/movies", Access.ADMIN),
                    r("PUT", "/api/admin/movies/:id", Access.ADMIN),
                    r("DELETE", "/api/admin/movies/:id", Access.ADMIN),
                    r("POST", "/api/admin/shows", Access.ADMIN),
                    r("PUT", "/api/admin/shows/:id", Access.ADMIN),
                    r("POST", "/api/admin/shows/:id/cancel", Access.ADMIN),
                    r("GET", "/api/admin/reports/shows", Access.ADMIN),
                    r("GET", "/api/admin/reports/shows/:id", Access.ADMIN),
                    r("GET", "/api/admin/stats", Access.ADMIN),
                    // Ops
                    r("GET", "/api/health", Access.PUBLIC));

    private Permissions() {}

    private static Rule r(String method, String pattern, Access access) {
        return new Rule(method, pattern, access);
    }

    /** Exact lookup by the pattern string used at registration (for the route-walk test). */
    static Optional<Rule> forPattern(String method, String pattern) {
        return TABLE.stream()
                .filter(r -> r.method().equals(method) && r.pattern().equals(pattern))
                .findFirst();
    }

    /** Lookup for a concrete request path; HEAD is answered by GET routes. */
    static Optional<Rule> match(String method, String path) {
        String m = method.equals("HEAD") ? "GET" : method;
        String[] segments = split(path);
        for (Rule rule : TABLE) {
            if (rule.method().equals(m) && matches(split(rule.pattern()), segments)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    private static String[] split(String path) {
        String p =
                path.length() > 1 && path.endsWith("/")
                        ? path.substring(0, path.length() - 1)
                        : path;
        return p.split("/", -1);
    }

    private static boolean matches(String[] pattern, String[] actual) {
        if (pattern.length != actual.length) {
            return false;
        }
        for (int i = 0; i < pattern.length; i++) {
            boolean param = pattern[i].startsWith(":");
            if (param ? actual[i].isEmpty() : !pattern[i].equals(actual[i])) {
                return false;
            }
        }
        return true;
    }
}
