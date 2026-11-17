package dev.thiru.bms.web;

import in.jolt.App;
import in.jolt.Compress;
import in.jolt.Csrf;
import in.jolt.IdempotencyStore;
import in.jolt.JsonMapper;
import in.jolt.Logger;
import in.jolt.Middleware;
import in.jolt.RateLimit;
import in.jolt.RequestId;
import in.jolt.RouteModule;
import in.jolt.SecurityHeaders;
import in.jolt.auth.JwtAuth;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * The shared HTTP pipeline every route runs behind, with no route of its own. Legacy modules
 * ({@link WebModules#routes}) and feature slices register into the same pipeline, so swapping a
 * legacy module for its slice is one line and changes nothing around it.
 *
 * <p>Middleware order is SPEC 9: request id, request log, security headers, problem mapping, gzip,
 * IP rate limits, CSRF on cookie routes, then the RBAC gate (JWT and role checks, see {@link
 * Permissions}) with prefix-wide role layers on {@code /api/admin} and {@code /api/owner}, the
 * catalog cache clearing after admin and owner writes, and the mock gateway's limits. Per-user rate
 * limits and Idempotency-Key are route middleware, handed out by {@link #holdLimit} and {@link
 * #idempotent}.
 */
public final class HttpPipeline {

    static final String CSP =
            "default-src 'self'; img-src 'self' https: data:; frame-ancestors 'none'; "
                    + "form-action 'self'";

    /**
     * What the pipeline needs from the composition root.
     *
     * @param jwtSecret HS256 secret, at least 32 bytes
     * @param clock drives the catalog cache expiry
     * @param idempotency stored Idempotency-Key responses
     * @param mockGateway guards {@code /mock-gateway} (POST limits, CSRF, gate) when true
     * @param viewsDir directory of the server views, or {@code null} for {@code /views} on the
     *     class path
     */
    public record Config(
            String jwtSecret,
            Clock clock,
            IdempotencyStore idempotency,
            boolean mockGateway,
            Path viewsDir) {}

    private final App app;
    private final JwtAuth jwt;
    private final CatalogCache catalogCache;
    private final Middleware idempotent;
    private final Middleware holdLimit;

    private HttpPipeline(
            App app,
            JwtAuth jwt,
            CatalogCache catalogCache,
            Middleware idempotent,
            Middleware holdLimit) {
        this.app = app;
        this.jwt = jwt;
        this.catalogCache = catalogCache;
        this.idempotent = idempotent;
        this.holdLimit = holdLimit;
    }

    /** Registers views, problem mapping and the middleware chain on {@code app}; no routes. */
    public static HttpPipeline install(App app, Config config) {
        if (config.viewsDir() == null) {
            app.viewsClasspath("/views", null);
        } else {
            app.views(config.viewsDir());
        }
        Problems.install(app);

        JwtAuth jwt = JwtAuth.hs256(config.jwtSecret());
        Rbac rbac = new Rbac(jwt);

        app.use(RequestId.middleware());
        app.use(Logger.requests());
        app.use(
                SecurityHeaders.defaults()
                        .csp(CSP)
                        .hsts(Duration.ofDays(365), req -> "https".equalsIgnoreCase(req.scheme())));
        app.use(Problems.middleware());
        app.use(Compress.gzip());

        Duration minute = Duration.ofMinutes(1);
        app.use("/api/auth/login", RateLimit.perIp(10, minute));
        app.use("/api/auth/register", RateLimit.perIp(10, minute));
        app.use("/api/auth/refresh", RateLimit.perIp(30, minute));
        app.use("/api/auth", Csrf.protect());
        app.use("/api", rbac.gate());
        app.use("/api/admin", rbac.adminLayer());
        app.use("/api/owner", rbac.ownerLayer());
        CatalogCache catalogCache = new CatalogCache(config.clock());
        catalogCache.metrics(app.metrics());
        app.use("/api/admin", catalogCache.clearAfterWrites());
        app.use("/api/owner", catalogCache.clearAfterWrites());

        if (config.mockGateway()) {
            Middleware completeLimit = RateLimit.perIp(30, minute);
            Middleware csrf = Csrf.protect();
            app.use(
                    "/mock-gateway",
                    (req, res, next) -> {
                        if ("POST".equals(req.method())) {
                            completeLimit.handle(req, res, () -> csrf.handle(req, res, next));
                        } else {
                            next.run();
                        }
                    });
            app.use("/mock-gateway", rbac.gate());
        }

        Middleware idempotent = Idempotent.required(config.idempotency());
        // Runs after the gate, so "user" is the verified subject.
        Middleware holdLimit = RateLimit.perKey(20, minute, req -> "user:" + req.attr("user"));
        return new HttpPipeline(app, jwt, catalogCache, idempotent, holdLimit);
    }

    /** Mounts a route module (a legacy one or a slice's) behind this pipeline. */
    public HttpPipeline module(RouteModule module) {
        app.module(module);
        return this;
    }

    public App app() {
        return app;
    }

    public JsonMapper mapper() {
        return app.config().jsonMapper();
    }

    /** Signs and verifies access tokens; the gate uses the same instance. */
    public JwtAuth jwt() {
        return jwt;
    }

    /** The public catalog cache that admin and owner writes clear. */
    public CatalogCache catalogCache() {
        return catalogCache;
    }

    /** Route middleware: requires an Idempotency-Key and replays a completed response. */
    public Middleware idempotent() {
        return idempotent;
    }

    /** Route middleware: 20 holds per user per minute. */
    public Middleware holdLimit() {
        return holdLimit;
    }
}
