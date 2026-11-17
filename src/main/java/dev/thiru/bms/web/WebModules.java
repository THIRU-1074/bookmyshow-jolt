package dev.thiru.bms.web;

import dev.thiru.bms.application.Accounts;
import dev.thiru.bms.application.AdminCatalog;
import dev.thiru.bms.application.AdminReports;
import dev.thiru.bms.application.BookingQueries;
import dev.thiru.bms.application.CancelBooking;
import dev.thiru.bms.application.CancelShow;
import dev.thiru.bms.application.CatalogQueries;
import dev.thiru.bms.application.Checkout;
import dev.thiru.bms.application.CreateBooking;
import dev.thiru.bms.application.CreateHold;
import dev.thiru.bms.application.MockGatewayPayments;
import dev.thiru.bms.application.ProcessPaymentWebhook;
import dev.thiru.bms.application.UserAdmin;
import dev.thiru.bms.domain.Role;
import dev.thiru.bms.domain.User;
import in.jolt.App;
import in.jolt.Compress;
import in.jolt.Csrf;
import in.jolt.HttpException;
import in.jolt.JsonMapper;
import in.jolt.Logger;
import in.jolt.Middleware;
import in.jolt.RateLimit;
import in.jolt.RequestId;
import in.jolt.SecurityHeaders;
import in.jolt.auth.JwtAuth;
import in.jolt.auth.RefreshTokens;
import java.time.Duration;
import java.util.Map;

/**
 * Entry point of the web layer. Main builds the {@link App} (with {@code problemJson(true)}), calls
 * {@link #install} once before {@code listen}, then registers static files after it so API routes
 * win over the SPA fallback.
 *
 * <p>Middleware order is SPEC 9: request id, request log, security headers, problem mapping, gzip,
 * IP rate limits, CSRF on cookie routes, then the RBAC gate (JWT and role checks, see {@link
 * Permissions}) with prefix-wide role layers on {@code /api/admin} and {@code /api/owner}, per-user
 * rate limits and Idempotency-Key as route middleware.
 */
public final class WebModules {

    static final String CSP =
            "default-src 'self'; img-src 'self' https: data:; frame-ancestors 'none'; "
                    + "form-action 'self'";

    private WebModules() {}

    /** Registers middleware and every route module on {@code app}. */
    public static void install(App app, WebDeps deps) {
        JsonMapper mapper = app.config().jsonMapper();
        WebDeps.Settings settings = deps.settings();
        app.views(settings.viewsDir());
        Problems.install(app);

        JwtAuth jwt = JwtAuth.hs256(settings.jwtSecret());
        Accounts accounts = new Accounts(deps.uow(), deps.hasher(), deps.clock());
        RefreshTokens tokens =
                RefreshTokens.of(jwt)
                        .accessTtl(AuthModule.ACCESS_TTL)
                        .refreshTtl(AuthModule.REFRESH_TTL)
                        .store(new JoltRefreshStore(deps.refreshTokens()))
                        // Re-read on every issue and refresh: a demotion or deletion is picked up
                        // the next time the access token is renewed.
                        .claims(subject -> claimsFor(accounts, subject));
        Rbac rbac = new Rbac(jwt);

        app.use(RequestId.middleware());
        app.use(Logger.requests());
        app.use(
                SecurityHeaders.defaults()
                        .csp(CSP)
                        .hsts(Duration.ofDays(365), req -> "https".equalsIgnoreCase(req.scheme())));
        app.use(Problems.middleware(mapper));
        app.use(Compress.gzip());

        Duration minute = Duration.ofMinutes(1);
        app.use("/api/auth/login", RateLimit.perIp(10, minute));
        app.use("/api/auth/register", RateLimit.perIp(10, minute));
        app.use("/api/auth/refresh", RateLimit.perIp(30, minute));
        app.use("/api/auth", Csrf.protect());
        app.use("/api", rbac.gate());
        app.use("/api/admin", rbac.adminLayer());
        app.use("/api/owner", rbac.ownerLayer());

        Middleware idempotent = new IdempotencyFilter(deps.idempotency(), deps.clock()).required();
        // Runs after the gate, so "user" is the verified subject.
        Middleware holdLimit = RateLimit.perKey(20, minute, req -> "user:" + req.attr("user"));

        CatalogQueries catalog = new CatalogQueries(deps.uow(), deps.clock(), deps.policy());
        BookingQueries bookings = new BookingQueries(deps.uow());

        app.module(new AuthModule(accounts, tokens));
        app.module(new CatalogModule(catalog));
        app.module(
                new LiveModule(
                        catalog, deps.live(), deps.clock(), settings.sseHeartbeat(), mapper));
        app.module(
                new BookingModule(
                        new CreateHold(
                                deps.uow(),
                                deps.publisher(),
                                deps.ids(),
                                deps.clock(),
                                deps.policy()),
                        new CreateBooking(deps.uow(), deps.publisher(), deps.ids(), deps.clock()),
                        bookings,
                        new Checkout(
                                deps.uow(),
                                deps.gateway(),
                                deps.publisher(),
                                deps.ids(),
                                deps.clock(),
                                deps.policy()),
                        new CancelBooking(
                                deps.uow(), deps.gateway(), deps.publisher(), deps.clock()),
                        idempotent,
                        holdLimit));

        MockGatewayPayments mock = null;
        if (settings.mockGateway()) {
            mock = new MockGatewayPayments(deps.uow(), deps.mockSender(), deps.ids(), deps.clock());
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
        app.module(
                new PaymentModule(
                        new ProcessPaymentWebhook(
                                deps.uow(), deps.gateway(), deps.publisher(), deps.clock()),
                        deps.webhookVerifier(),
                        mock,
                        mapper));
        AdminCatalog adminCatalog = new AdminCatalog(deps.uow(), deps.clock());
        AdminReports reports = new AdminReports(deps.uow(), deps.clock());
        CancelShow cancelShow =
                new CancelShow(deps.uow(), deps.gateway(), deps.publisher(), deps.clock());
        app.module(
                new AdminModule(
                        adminCatalog,
                        reports,
                        cancelShow,
                        new UserAdmin(deps.uow()),
                        deps.live(),
                        idempotent));
        app.module(new OwnerModule(adminCatalog, reports, cancelShow, idempotent));
        // TODO(jolt#71): register the DB check with exposeMetrics once apps can add checks.
        app.get(
                "/api/health",
                (req, res) -> {
                    boolean up = deps.dbUp().getAsBoolean();
                    res.status(up ? 200 : 503)
                            .noStore()
                            .json(Map.of("status", up ? "UP" : "DOWN", "db", up ? "UP" : "DOWN"));
                });
    }

    private static Map<String, ?> claimsFor(Accounts accounts, String subject) {
        long id;
        try {
            id = Long.parseLong(subject);
        } catch (NumberFormatException e) {
            throw new HttpException(401, "Invalid token");
        }
        User user = accounts.find(id).orElseThrow(() -> new HttpException(401, "Invalid token"));
        return Map.of("roles", user.roles().stream().map(Role::name).sorted().toList());
    }
}
