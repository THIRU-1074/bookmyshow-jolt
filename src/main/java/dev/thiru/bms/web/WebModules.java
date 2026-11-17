package dev.thiru.bms.web;

import dev.thiru.bms.application.MockGatewayPayments;
import dev.thiru.bms.application.ProcessPaymentWebhook;
import dev.thiru.bms.features.accounts.Accounts;
import dev.thiru.bms.features.accounts.AuthRoutes;
import dev.thiru.bms.features.admin.AdminRoutes;
import dev.thiru.bms.features.admin.OwnerRoutes;
import dev.thiru.bms.features.booking.BookingRoutes;
import dev.thiru.bms.features.catalog.CatalogRoutes;
import dev.thiru.bms.features.live.LiveRoutes;
import in.jolt.App;
import in.jolt.JsonMapper;
import in.jolt.Middleware;
import in.jolt.Webhooks;
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

        pipeline.module(
                AuthRoutes.of(
                        pipeline.jwt(),
                        new Accounts(deps.db(), deps.hasher(), deps.clock()),
                        deps.refreshTokens()));
        pipeline.module(
                new CatalogRoutes(deps.db(), deps.clock(), deps.policy(), pipeline.catalogCache()));
        pipeline.module(
                new LiveRoutes(
                        deps.db(),
                        deps.live(),
                        deps.clock(),
                        deps.policy(),
                        settings.sseHeartbeat(),
                        mapper));
        pipeline.module(
                new BookingRoutes(
                        deps.db(),
                        deps.publisher(),
                        deps.ids(),
                        deps.clock(),
                        deps.policy(),
                        deps.gateway(),
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
        pipeline.module(
                new AdminRoutes(
                        deps.db(),
                        deps.clock(),
                        deps.gateway(),
                        deps.publisher(),
                        deps.live(),
                        idempotent));
        pipeline.module(
                new OwnerRoutes(
                        deps.db(), deps.clock(), deps.gateway(), deps.publisher(), idempotent));
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
}
