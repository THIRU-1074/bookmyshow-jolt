package dev.thiru.bms.features.payments;

import dev.thiru.bms.features.booking.BookingRoutes;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.GatewayCallback;
import dev.thiru.bms.testing.SliceKit;
import in.jolt.Webhooks;
import in.jolt.test.JoltTest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A {@link SliceKit} with the booking slice beside {@link PaymentRoutes}, so a test reaches {@code
 * PAYMENT_PENDING} the way a customer does: hold, book, check out. Tests may combine slices; main
 * code may not. The show is the first 2D show in Audi 1 three days out (rows A to F CLASSIC at
 * 18000 paise).
 */
final class PaymentFlow {

    /** SPEC 7, as {@code WebModules.WEBHOOK_TOLERANCE}. */
    static final Duration TOLERANCE = Duration.ofSeconds(300);

    final SliceKit kit;
    final long show;

    private PaymentFlow(SliceKit kit) {
        this.kit = kit;
        this.show = showOn(kit, 3);
    }

    /** Mock gateway on, delivering into {@link SliceKit#callbacks()}. */
    static PaymentFlow boot() {
        return boot(true, SliceKit::callbacks);
    }

    /**
     * {@code mock} turns the mock gateway guards and routes on; {@code callback} builds its
     * callback from the kit (ignored when the mock is off).
     */
    static PaymentFlow boot(boolean mock, Function<SliceKit, GatewayCallback> callback) {
        SliceKit kit =
                SliceKit.builder()
                        .mockGateway(mock)
                        .routes(
                                k ->
                                        List.of(
                                                new BookingRoutes(
                                                        k.db(),
                                                        k.events(),
                                                        k.ids(),
                                                        k.clock(),
                                                        k.policy(),
                                                        k.gateway(),
                                                        k.pipeline().idempotent(),
                                                        k.pipeline().holdLimit()),
                                                routes(k, mock ? callback.apply(k) : null)))
                        .boot();
        return new PaymentFlow(kit);
    }

    /** The routes as {@code WebModules} mounts them. */
    static PaymentRoutes routes(SliceKit k, GatewayCallback mockSender) {
        return new PaymentRoutes(
                k.db(),
                k.gateway(),
                k.events(),
                k.ids(),
                k.clock(),
                signature(k),
                mockSender,
                k.pipeline().mapper());
    }

    /** The signature chain {@code WebModules} builds. */
    static in.jolt.Middleware signature(SliceKit k) {
        return Webhooks.hmacSha256(SliceKit.WEBHOOK_SECRET, "Bms-Signature")
                .prefix("v1=")
                .timestampHeader("Bms-Timestamp")
                .tolerance(TOLERANCE)
                .clock(k.clock());
    }

    /**
     * A callback that signs the event and posts it to the kit's own webhook, as production does.
     */
    static GatewayCallback delivering(SliceKit k) {
        return e -> {
            byte[] body =
                    json(
                            k,
                            e.eventId(),
                            e.type(),
                            e.paymentId(),
                            e.gatewayRef(),
                            e.amount(),
                            e.currency(),
                            e.occurredAt());
            long now = k.clock().instant().getEpochSecond();
            return signed(k, body, now, sign(now, body)).send().status();
        };
    }

    static long showOn(SliceKit kit, int day) {
        Instant from = kit.seedDate().plusDays(day).atStartOfDay(SliceKit.IST).toInstant();
        return kit.db()
                .inTransaction(
                        tx ->
                                tx.queryOne(
                                                "SELECT id FROM show WHERE screen_id = 1 AND"
                                                        + " format = '2D' AND starts_at >= ?"
                                                        + " ORDER BY starts_at LIMIT 1",
                                                rs -> rs.getLong(1),
                                                Db.millis(from))
                                        .orElseThrow());
    }

    // ---- the customer's side, through the booking slice ----

    String hold(String token, String... seats) {
        return (String)
                kit.http()
                        .post("/api/holds")
                        .header("Authorization", SliceKit.bearer(token))
                        .header("Idempotency-Key", kit.key())
                        .json(Map.of("showId", show, "seats", List.of(seats)))
                        .expect(201)
                        .jsonField("id");
    }

    String book(String token, String holdId) {
        return (String)
                kit.http()
                        .post("/api/bookings")
                        .header("Authorization", SliceKit.bearer(token))
                        .header("Idempotency-Key", kit.key())
                        .json(Map.of("holdId", holdId))
                        .expect(201)
                        .jsonField("id");
    }

    /** Checks out and returns the payment id. */
    String checkout(String token, String bookingId) {
        return (String)
                kit.http()
                        .post("/api/bookings/" + bookingId + "/checkout")
                        .header("Authorization", SliceKit.bearer(token))
                        .header("Idempotency-Key", kit.key())
                        .expect(201)
                        .jsonField("paymentId");
    }

    /** A {@code PAYMENT_PENDING} booking of {@code seats}; returns {bookingId, paymentId}. */
    String[] pending(String token, String... seats) {
        String booking = book(token, hold(token, seats));
        return new String[] {booking, checkout(token, booking)};
    }

    // ---- the gateway's side ----

    JoltTest.Call webhook(String eventId, String type, String paymentId, long amount) {
        byte[] body =
                json(
                        kit,
                        eventId,
                        type,
                        paymentId,
                        "gw_" + paymentId,
                        amount,
                        "INR",
                        kit.clock().instant());
        long now = kit.clock().instant().getEpochSecond();
        return signedWebhook(body, now, sign(now, body));
    }

    JoltTest.Call signedWebhook(byte[] body, long timestamp, String signature) {
        return signed(kit, body, timestamp, signature);
    }

    private static JoltTest.Call signed(SliceKit k, byte[] body, long timestamp, String signature) {
        return k.http()
                .post("/api/payments/webhook")
                .header("Content-Type", "application/json")
                .header("Bms-Timestamp", Long.toString(timestamp))
                .header("Bms-Signature", signature)
                .body(body);
    }

    private static byte[] json(
            SliceKit k,
            String eventId,
            String type,
            String paymentId,
            String gatewayRef,
            long amount,
            String currency,
            Instant occurredAt) {
        return k.pipeline()
                .mapper()
                .write(
                        Map.of(
                                "eventId", eventId,
                                "type", type,
                                "paymentId", paymentId,
                                "gatewayRef", gatewayRef,
                                "amount", amount,
                                "currency", currency,
                                "occurredAt", occurredAt.toString()));
    }

    /** SPEC 7: {@code v1=<hex HMAC-SHA256(WEBHOOK_SECRET, timestamp + "." + rawBody)>}. */
    static String sign(long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            SliceKit.WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- rows ----

    String bookingStatus(String bookingId) {
        return string("SELECT status FROM booking WHERE id = ?", bookingId);
    }

    String paymentStatus(String paymentId) {
        return string("SELECT status FROM payment WHERE id = ?", paymentId);
    }

    long refunded(String paymentId) {
        return kit.db()
                .inTransaction(
                        tx ->
                                tx.queryOne(
                                                "SELECT refunded_amount FROM payment WHERE id = ?",
                                                rs -> rs.getLong(1),
                                                paymentId)
                                        .orElseThrow());
    }

    long version(String bookingId) {
        return kit.db()
                .inTransaction(
                        tx ->
                                tx.queryOne(
                                                "SELECT version FROM booking WHERE id = ?",
                                                rs -> rs.getLong(1),
                                                bookingId)
                                        .orElseThrow());
    }

    /** {@code seat:state:expiresAt-or-null} for every lock of the booking's show, by seat. */
    List<String> locks() {
        return kit.db()
                .inTransaction(
                        tx ->
                                tx.query(
                                        "SELECT seat_id, state, expires_at FROM seat_lock"
                                                + " WHERE show_id = ? ORDER BY seat_id",
                                        rs ->
                                                rs.getString(1)
                                                        + ":"
                                                        + rs.getString(2)
                                                        + ":"
                                                        + (rs.getObject(3) == null
                                                                ? "null"
                                                                : "set"),
                                        show));
    }

    void sql(String sql, Object... args) {
        kit.db().inTransaction(tx -> tx.update(sql, args));
    }

    private String string(String sql, Object arg) {
        return kit.db()
                .inTransaction(tx -> tx.queryOne(sql, rs -> rs.getString(1), arg).orElseThrow());
    }
}
