package dev.thiru.bms.web;

import in.jolt.Middleware;
import in.jolt.auth.Auth;
import in.jolt.auth.JwtAuth;

/**
 * Role-based access control gate for {@code /api}, driven by {@link Permissions}. Deny by default:
 * no rule means 404, a protected rule needs a valid JWT (401 otherwise), an ADMIN rule needs the
 * ADMIN role and a THEATRE_OWNER rule THEATRE_OWNER or ADMIN (403 otherwise). Ownership of single
 * resources and the cinema scope of owners are checked in the use cases (404 for strangers).
 *
 * <p>Roles are read from the JWT, so a demotion takes effect when the access token expires (15
 * minutes at most); refresh re-reads roles from the stored user.
 */
final class Rbac {

    private final Middleware jwt;
    private final Middleware adminOnly;
    private final Middleware ownerOrAdmin;

    Rbac(JwtAuth jwtAuth) {
        // Since Jolt 0.4.1 these throw HttpException on refusal, so 401 and 403 leave through the
        // problem+json mappers with WWW-Authenticate kept.
        this.jwt = jwtAuth.middleware();
        this.adminOnly = Auth.requireRole("ADMIN");
        this.ownerOrAdmin = Auth.requireRole("THEATRE_OWNER", "ADMIN");
    }

    /** The gate itself; register with {@code app.use("/api", gate())}. */
    Middleware gate() {
        return (req, res, next) -> {
            Permissions.Rule rule =
                    Permissions.match(req.method(), req.path())
                            .orElseThrow(
                                    () ->
                                            ApiException.notFound(
                                                    "No route " + req.method() + " " + req.path()));
            if (!rule.access().needsToken()) {
                next.run();
                return;
            }
            jwt.handle(
                    req,
                    res,
                    () -> {
                        Principal who = Principal.of(req);
                        if (rule.access() == Permissions.Access.ADMIN) {
                            adminOnly.handle(req, res, next);
                        } else if (rule.access() == Permissions.Access.THEATRE_OWNER) {
                            ownerOrAdmin.handle(req, res, next);
                        } else if (who.isSignedIn()) {
                            next.run();
                        } else {
                            throw ApiException.forbidden("Forbidden");
                        }
                    });
        };
    }

    /** Second, prefix-level layer for {@code /api/admin}: the stock role check. */
    Middleware adminLayer() {
        return adminOnly;
    }

    /** Second, prefix-level layer for {@code /api/owner}: the stock role check. */
    Middleware ownerLayer() {
        return ownerOrAdmin;
    }
}
