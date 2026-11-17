package dev.thiru.bms.web;

import dev.thiru.bms.application.ProcessPaymentWebhook;
import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.testing.FakeLive;
import dev.thiru.bms.testing.MemoryRefreshTokens;
import dev.thiru.bms.testing.PlainHasher;
import in.jolt.App;
import in.jolt.IdempotencyStore;
import in.jolt.Jolt;
import in.jolt.JoltConfig;
import in.jolt.test.JoltTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** The whole web layer on in-memory fakes: no socket, no database. */
final class WebKit {

    static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";
    static final String PASSWORD = "correct-horse-battery";
    static final String WEBHOOK_SECRET = "webhook-secret-that-is-at-least-32-bytes";

    final FakeWorld world = FakeWorld.seeded();
    final MemoryRefreshTokens refreshTokens = new MemoryRefreshTokens();
    final IdempotencyStore idempotency = IdempotencyStore.inMemory();
    final FakeLive live = new FakeLive(5);
    final List<ProcessPaymentWebhook.Event> sentWebhooks = new CopyOnWriteArrayList<>();
    final AtomicBoolean dbUp = new AtomicBoolean(true);
    final App app;
    final JoltTest http;
    private final AtomicInteger addresses = new AtomicInteger();

    WebKit() {
        this(true, Duration.ofSeconds(15));
    }

    WebKit(boolean mockGateway, Duration heartbeat) {
        app = Jolt.create(JoltConfig.defaults().problemJson(true));
        WebDeps deps =
                new WebDeps(
                        world,
                        world.clock,
                        world.policy,
                        world,
                        new PlainHasher(),
                        refreshTokens,
                        idempotency,
                        world,
                        world,
                        event -> {
                            sentWebhooks.add(event);
                            return 200;
                        },
                        live,
                        dbUp::get,
                        new WebDeps.Settings(
                                SECRET,
                                WEBHOOK_SECRET,
                                mockGateway,
                                Path.of("src/main/resources/views"),
                                heartbeat));
        WebModules.install(app, deps);
        http = JoltTest.of(app);
    }

    /** A distinct client address per call keeps the per-IP auth limits out of unrelated tests. */
    String nextAddress() {
        return "10.0.0." + (addresses.incrementAndGet() % 250 + 1);
    }

    /** Registers an AUDIENCE account and returns its access token. */
    String user(String email) {
        return (String)
                http.post("/api/auth/register")
                        .remoteAddress(nextAddress())
                        .json(Map.of("email", email, "password", PASSWORD, "name", "Test"))
                        .expect(201)
                        .jsonField("accessToken");
    }

    /** Creates an ADMIN directly in the store (registration never grants roles) and logs in. */
    String admin() {
        if (world.inTransaction(r -> r.users().findByEmail("admin@bms.test")).isPresent()) {
            return login("admin@bms.test");
        }
        world.inTransaction(
                r ->
                        r.users()
                                .insert(
                                        new User(
                                                0,
                                                "admin@bms.test",
                                                PlainHasher.PREFIX + PASSWORD,
                                                "Admin",
                                                Set.of(Role.ADMIN, Role.AUDIENCE),
                                                FakeWorld.T0)));
        return login("admin@bms.test");
    }

    long idOf(String email) {
        return world.inTransaction(r -> r.users().findByEmail(email)).orElseThrow().id();
    }

    /**
     * Registers an account, has the admin link it to {@code cinemaId} over the API (which grants
     * THEATRE_OWNER), then signs in again so the token carries the new role.
     */
    String owner(String email, long cinemaId) {
        user(email);
        http.put("/api/admin/cinemas/" + cinemaId + "/owners/" + idOf(email))
                .header("Authorization", bearer(admin()))
                .expect(200);
        return login(email);
    }

    String login(String email) {
        return (String)
                http.post("/api/auth/login")
                        .remoteAddress(nextAddress())
                        .json(Map.of("email", email, "password", PASSWORD))
                        .expect(200)
                        .jsonField("accessToken");
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    // ---- booking flow ----

    private final AtomicInteger keys = new AtomicInteger();

    String key() {
        return "key-" + keys.incrementAndGet();
    }

    /** Holds seats on the seeded show and returns the hold id. */
    String hold(String token, String... seats) {
        return (String)
                http.post("/api/holds")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key())
                        .json(Map.of("showId", FakeWorld.SHOW_ID, "seats", List.of(seats)))
                        .expect(201)
                        .jsonField("id");
    }

    String book(String token, String holdId) {
        return (String)
                http.post("/api/bookings")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key())
                        .json(Map.of("holdId", holdId))
                        .expect(201)
                        .jsonField("id");
    }

    /** Checks out and returns the payment id. */
    String checkout(String token, String bookingId) {
        return (String)
                http.post("/api/bookings/" + bookingId + "/checkout")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", key())
                        .expect(201)
                        .jsonField("paymentId");
    }

    JoltTest.Call webhook(String eventId, String type, String paymentId, long amount) {
        byte[] body =
                app.config()
                        .jsonMapper()
                        .write(
                                Map.of(
                                        "eventId", eventId,
                                        "type", type,
                                        "paymentId", paymentId,
                                        "gatewayRef", "gw_" + paymentId,
                                        "amount", amount,
                                        "currency", "INR",
                                        "occurredAt", FakeWorld.T0.toString()));
        long now = world.clock.instant().getEpochSecond();
        return signedWebhook(body, now, sign(now, body));
    }

    JoltTest.Call signedWebhook(byte[] body, long timestamp, String signature) {
        return http.post("/api/payments/webhook")
                .header("Content-Type", "application/json")
                .header("Bms-Timestamp", Long.toString(timestamp))
                .header("Bms-Signature", signature)
                .body(body);
    }

    /** SPEC 7: {@code v1=<hex HMAC-SHA256(WEBHOOK_SECRET, timestamp + "." + rawBody)>}. */
    static String sign(long timestamp, byte[] body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(
                    new javax.crypto.spec.SecretKeySpec(
                            WEBHOOK_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return "v1=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A CONFIRMED booking of seats A2 and A3 (2 x 150.00) for {@code token}; returns its id. */
    String confirmedBooking(String token) {
        String booking = book(token, hold(token, "A2", "A3"));
        String payment = checkout(token, booking);
        webhook("evt-" + booking, "payment.succeeded", payment, 30000)
                .expect(200)
                .jsonEquals("status", "CONFIRMED");
        return booking;
    }
}
