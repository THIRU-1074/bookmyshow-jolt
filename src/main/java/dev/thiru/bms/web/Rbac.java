package dev.thiru.bms.web;

import in.jolt.JsonMapper;
import in.jolt.Middleware;
import in.jolt.auth.Auth;
import in.jolt.auth.JwtAuth;

/**
 * Role-based access control gate for {@code /api}, driven by {@link Permissions}. Deny by default:
 * no rule means 404, a protected rule needs a valid JWT (401 otherwise), and an ADMIN rule needs
 * the ADMIN role (403 otherwise). Ownership of single resources is checked in the handlers.
 *
 * <p>Roles are read from the JWT, so a demotion takes effect when the access token expires (15
 * minutes at most); refresh re-reads roles from the stored user.
 */
final class Rbac {

    private final Middleware jwt;
    private final Middleware adminOnly;

    Rbac(JwtAuth jwtAuth, JsonMapper mapper) {
        this.jwt = Guards.adapt(jwtAuth.middleware(), mapper);
        this.adminOnly = Guards.adapt(Auth.requireRole("ADMIN"), mapper);
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
                        } else if (who.isUser()) {
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
}
