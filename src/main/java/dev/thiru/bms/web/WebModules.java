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
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.JsonMapper;
import in.jolt.Middleware;
import in.jolt.Webhooks;
import in.jolt.auth.RefreshTokens;
import java.time.Duration;
import java.util.Map;

/**
 * Entry point of the web layer. Main builds the {@link App} (with {@code problemJson(true)}), calls
 * {@link #install} once before {@code listen}, then registers static files after it so API routes
 * win over the SPA fallback.
 *
 * <p>{@link #install} is the {@link HttpPipeline} (SPEC 9 middleware, RBAC gate, problems,
 * idempotency) followed by {@link #routes}, the route registration. Each line of {@link #routes}
 * mounts one route module; when a feature slice replaces a legacy module, that one line changes.
 */
public final class WebModules {

    /** SPEC 7: a webhook timestamp more than 300 s from now is rejected. */
    static final Duration WEBHOOK_TOLERANCE = Duration.ofSeconds(300);

    private WebModules() {}

    /** Installs the pipeline, then every route module, on {@code app}. */
    public static HttpPipeline install(App app, WebDeps deps) {
        HttpPipeline pipeline = HttpPipeline.install(app, pipelineConfig(deps));
        routes(pipeline, deps);
        return pipeline;
    }

    /** The pipeline settings carried by {@code deps}. */
    public static HttpPipeline.Config pipelineConfig(WebDeps deps) {
        WebDeps.Settings settings = deps.settings();
        return new HttpPipeline.Config(
                settings.jwtSecret(),
                deps.clock(),
                deps.idempotency(),
                settings.mockGateway(),
                settings.viewsDir());
    }

    /** Registers every route module behind {@code pipeline}. */
    public static void routes(HttpPipeline pipeline, WebDeps deps) {
        App app = pipeline.app();
        JsonMapper mapper = pipeline.mapper();
        WebDeps.Settings settings = deps.settings();
        Middleware idempotent = pipeline.idempotent();

        Accounts accounts = new Accounts(deps.uow(), deps.hasher(), deps.clock());
        RefreshTokens tokens =
                RefreshTokens.of(pipeline.jwt())
                        .accessTtl(AuthModule.ACCESS_TTL)
                        .refreshTtl(AuthModule.REFRESH_TTL)
                        .store(new JoltRefreshStore(deps.refreshTokens()))
                        // Re-read on every issue and refresh: a demotion or deletion is picked up
                        // the next time the access token is renewed.
                        .claims(subject -> claimsFor(accounts, subject));
        CatalogQueries catalog = new CatalogQueries(deps.uow(), deps.clock(), deps.policy());
        BookingQueries bookings = new BookingQueries(deps.uow());

        pipeline.module(new AuthModule(accounts, tokens));
        pipeline.module(new CatalogModule(catalog, pipeline.catalogCache()));
        pipeline.module(
                new LiveModule(
                        catalog, deps.live(), deps.clock(), settings.sseHeartbeat(), mapper));
        pipeline.module(
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
                        pipeline.holdLimit()));

        MockGatewayPayments mock =
                settings.mockGateway()
                        ? new MockGatewayPayments(
                                deps.uow(), deps.mockSender(), deps.ids(), deps.clock())
                        : null;
        pipeline.module(
                new PaymentModule(
                        new ProcessPaymentWebhook(
                                deps.uow(), deps.gateway(), deps.publisher(), deps.clock()),
                        Webhooks.hmacSha256(settings.webhookSecret(), "Bms-Signature")
                                .prefix("v1=")
                                .timestampHeader("Bms-Timestamp")
                                .tolerance(WEBHOOK_TOLERANCE)
                                .clock(deps.clock()),
                        mock,
                        mapper));
        AdminCatalog adminCatalog = new AdminCatalog(deps.uow(), deps.clock());
        AdminReports reports = new AdminReports(deps.uow(), deps.clock());
        CancelShow cancelShow =
                new CancelShow(deps.uow(), deps.gateway(), deps.publisher(), deps.clock());
        pipeline.module(
                new AdminModule(
                        adminCatalog,
                        reports,
                        cancelShow,
                        new UserAdmin(deps.uow()),
                        deps.live(),
                        idempotent));
        pipeline.module(new OwnerModule(adminCatalog, reports, cancelShow, idempotent));
        // The same check feeds /_jolt/health; /api/health keeps its own JSON shape for Render.
        // TODO(jolt#84): run the registered checks for /api/health once Jolt exposes them.
        app.health("db", () -> deps.dbUp().getAsBoolean());
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
