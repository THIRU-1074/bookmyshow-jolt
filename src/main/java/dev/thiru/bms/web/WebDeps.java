package dev.thiru.bms.web;

import dev.thiru.bms.application.MockGatewayPayments;
import dev.thiru.bms.domain.BookingPolicy;
import dev.thiru.bms.domain.port.IdGenerator;
import dev.thiru.bms.domain.port.LiveSeats;
import dev.thiru.bms.domain.port.PasswordHasher;
import dev.thiru.bms.domain.port.PaymentGateway;
import dev.thiru.bms.domain.port.RefreshTokenStore;
import dev.thiru.bms.domain.port.SeatEventPublisher;
import dev.thiru.bms.domain.port.UnitOfWork;
import in.jolt.IdempotencyStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Everything {@link WebModules#install} needs from the composition root. The web layer names only
 * ports and functional interfaces; Main supplies the adapters behind them.
 *
 * @param uow transactional boundary over the repositories
 * @param ids random ids and the booking-code random source
 * @param hasher password hashing (Jolt's PBKDF2 in production)
 * @param refreshTokens durable refresh-token hashes
 * @param idempotency durable Idempotency-Key responses behind Jolt's Idempotency middleware
 * @param gateway outbound payments (checkout sessions and refunds)
 * @param publisher post-commit seat events
 * @param mockSender delivers the mock gateway's signed webhook over HTTP
 * @param live SSE subscriptions
 * @param dbUp true when {@code SELECT 1} succeeds
 * @param settings static settings
 */
public record WebDeps(
        UnitOfWork uow,
        Clock clock,
        BookingPolicy policy,
        IdGenerator ids,
        PasswordHasher hasher,
        RefreshTokenStore refreshTokens,
        IdempotencyStore idempotency,
        PaymentGateway gateway,
        SeatEventPublisher publisher,
        MockGatewayPayments.Sender mockSender,
        LiveSeats live,
        BooleanSupplier dbUp,
        Settings settings) {

    /**
     * @param jwtSecret HS256 secret, at least 32 bytes (JWT_SECRET)
     * @param webhookSecret HMAC-SHA256 key of the payment webhook, at least 32 bytes
     *     (WEBHOOK_SECRET)
     * @param mockGateway enables the mock gateway pages (MOCK_GATEWAY)
     * @param viewsDir directory holding {@code ticket.html} and {@code mock-gateway.html}
     *     (VIEWS_DIR)
     * @param sseHeartbeat interval between SSE heartbeats (15 s in production)
     */
    public record Settings(
            String jwtSecret,
            String webhookSecret,
            boolean mockGateway,
            Path viewsDir,
            Duration sseHeartbeat) {

        public Settings {
            if (jwtSecret == null
                    || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("JWT_SECRET must be at least 32 bytes");
            }
            if (webhookSecret == null
                    || webhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            < 32) {
                throw new IllegalArgumentException("WEBHOOK_SECRET must be at least 32 bytes");
            }
        }
    }
}
