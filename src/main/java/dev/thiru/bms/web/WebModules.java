package dev.thiru.bms.web;

import dev.thiru.bms.features.accounts.Accounts;
import dev.thiru.bms.features.accounts.AuthRoutes;
import dev.thiru.bms.features.admin.AdminRoutes;
import dev.thiru.bms.features.admin.OwnerRoutes;
import dev.thiru.bms.features.booking.BookingRoutes;
import dev.thiru.bms.features.catalog.CatalogRoutes;
import dev.thiru.bms.features.live.LiveRoutes;
import dev.thiru.bms.features.payments.PaymentRoutes;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.GatewayCallback;
import dev.thiru.bms.ports.Ids;
import dev.thiru.bms.ports.LiveSeats;
import dev.thiru.bms.ports.PasswordHasher;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.RefreshTokenStore;
import dev.thiru.bms.ports.SeatEvents;
import in.jolt.App;
import in.jolt.IdempotencyStore;
import in.jolt.JsonMapper;
import in.jolt.Middleware;
import in.jolt.Webhooks;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Entry point of the web layer. Main builds the {@link App} (with {@code problemJson(true)}), calls
 * {@link #install} once before {@code listen}, then registers static files after it so API routes
 * win over the SPA fallback.
 *
 * <p>{@link #install} is the {@link HttpPipeline} (SPEC 9 middleware, RBAC gate, problems,
 * idempotency) followed by {@link #routes}, the route registration. Each line of {@link #routes}
 * mounts one slice's route module with the ports that slice uses. The web layer names only ports
 * and functional interfaces; Main supplies the adapters behind {@link Ports}.
 */
public final class WebModules {

    /** SPEC 7: a webhook timestamp more than 300 s from now is rejected. */
    static final Duration WEBHOOK_TOLERANCE = Duration.ofSeconds(300);

    private WebModules() {}

    /**
     * The ports the slices are built from.
     *
     * @param db the transaction seam every slice runs its SQL through
     * @param ids random ids and the booking-code random source
     * @param hasher password hashing (Jolt's PBKDF2 in production)
     * @param refreshTokens durable refresh-token hashes
     * @param idempotency durable Idempotency-Key responses behind Jolt's Idempotency middleware
     * @param gateway outbound payments (checkout sessions and refunds)
     * @param publisher post-commit seat events
     * @param mockSender delivers the mock gateway's signed webhook over HTTP
     * @param live SSE subscriptions
     * @param dbUp true when {@code SELECT 1} succeeds
     */
    public record Ports(
            Db db,
            Clock clock,
            BookingPolicy policy,
            Ids ids,
            PasswordHasher hasher,
            RefreshTokenStore refreshTokens,
            IdempotencyStore idempotency,
            PaymentGateway gateway,
            SeatEvents publisher,
            GatewayCallback mockSender,
            LiveSeats live,
            BooleanSupplier dbUp) {}

    /**
     * @param jwtSecret HS256 secret, at least 32 bytes (JWT_SECRET)
     * @param webhookSecret HMAC-SHA256 key of the payment webhook, at least 32 bytes
     *     (WEBHOOK_SECRET)
     * @param mockGateway enables the mock gateway pages (MOCK_GATEWAY)
     * @param viewsDir directory holding {@code ticket.html} and {@code mock-gateway.html}
     *     (VIEWS_DIR), or {@code null} to read them from {@code /views} on the class path
     * @param sseHeartbeat interval between SSE heartbeats (15 s in production)
     */
    public record Settings(
            String jwtSecret,
            String webhookSecret,
            boolean mockGateway,
            Path viewsDir,
            Duration sseHeartbeat) {

        public Settings {
            if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("JWT_SECRET must be at least 32 bytes");
            }
            if (webhookSecret == null
                    || webhookSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("WEBHOOK_SECRET must be at least 32 bytes");
            }
        }
    }

    /** Installs the pipeline, then every route module, on {@code app}. */
    public static HttpPipeline install(App app, Ports ports, Settings settings) {
        HttpPipeline pipeline = HttpPipeline.install(app, pipelineConfig(ports, settings));
        routes(pipeline, ports, settings);
        return pipeline;
    }

    /** The pipeline's configuration. */
    public static HttpPipeline.Config pipelineConfig(Ports ports, Settings settings) {
        return new HttpPipeline.Config(
                settings.jwtSecret(),
                ports.clock(),
                ports.idempotency(),
                settings.mockGateway(),
                settings.viewsDir());
    }

    /** Registers every route module behind {@code pipeline}. */
    public static void routes(HttpPipeline pipeline, Ports deps, Settings settings) {
        App app = pipeline.app();
        JsonMapper mapper = pipeline.mapper();
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

        pipeline.module(
                new PaymentRoutes(
                        deps.db(),
                        deps.gateway(),
                        deps.publisher(),
                        deps.ids(),
                        deps.clock(),
                        Webhooks.hmacSha256(settings.webhookSecret(), "Bms-Signature")
                                .prefix("v1=")
                                .timestampHeader("Bms-Timestamp")
                                .tolerance(WEBHOOK_TOLERANCE)
                                .clock(deps.clock()),
                        settings.mockGateway() ? deps.mockSender() : null,
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
