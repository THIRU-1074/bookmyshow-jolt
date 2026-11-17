package dev.thiru.bms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.JoltConfig;
import in.jolt.JsonMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole app on a real socket: real SQLite file, real adapters, and the mock gateway delivering
 * its signed webhook back over HTTP (SPEC 13, end to end).
 */
class EndToEndTest {

    private static final String ADMIN_EMAIL = "admin@bms.test";
    private static final String ADMIN_PASSWORD = "admin-password-123";
    private static final String PASSWORD = "user-password-123";
    private static final JsonMapper JSON = JoltConfig.defaults().jsonMapper();

    @TempDir Path dir;
    private Main.Booted booted;
    private String base;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void start() throws IOException {
        int port;
        // The mock gateway posts to PUBLIC_BASE_URL, so the port must be known before building.
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Map<String, String> env = new HashMap<>();
        env.put("PORT", Integer.toString(port));
        env.put("JWT_SECRET", AppConfigTest.JWT);
        env.put("WEBHOOK_SECRET", AppConfigTest.HOOK);
        env.put("DB_URL", "jdbc:sqlite:" + dir.resolve("bms.db"));
        env.put("ADMIN_EMAIL", ADMIN_EMAIL);
        env.put("ADMIN_PASSWORD", ADMIN_PASSWORD);
        AppConfig config = AppConfig.fromEnv(env);
        booted = Main.app(config, JoltConfig.defaults());
        booted.listen(config.port());
        base = config.publicBaseUrl().toString();
    }

    @AfterEach
    void stop() {
        if (booted != null) {
            booted.close();
        }
    }

    private record Res(int status, String body, HttpResponse<String> raw) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json() {
            return JSON.read(body.getBytes(StandardCharsets.UTF_8), Map.class);
        }

        @SuppressWarnings("unchecked")
        List<Object> list() {
            return JSON.read(body.getBytes(StandardCharsets.UTF_8), List.class);
        }
    }

    private Res send(String method, String path, String token, Object body, String... headers)
            throws Exception {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : base + path))
                        .timeout(Duration.ofSeconds(20));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(JSON.write(body)));
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), r);
    }

    private Res expect(int status, Res r) {
        assertEquals(status, r.status(), () -> "unexpected status, body: " + r.body());
        return r;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arr(Object o) {
        return (List<Object>) o;
    }

    @Test
    void registerBrowseHoldBookPayAndGetATicket() throws Exception {
        // Auth
        Map<String, Object> reg =
                expect(
                                201,
                                send(
                                        "POST",
                                        "/api/auth/register",
                                        null,
                                        Map.of(
                                                "email", "buyer@bms.test",
                                                "password", PASSWORD,
                                                "name", "Buyer")))
                        .json();
        assertNotNull(reg.get("accessToken"));
        String token =
                (String)
                        expect(
                                        200,
                                        send(
                                                "POST",
                                                "/api/auth/login",
                                                null,
                                                Map.of(
                                                        "email",
                                                        "buyer@bms.test",
                                                        "password",
                                                        PASSWORD)))
                                .json()
                                .get("accessToken");
        expect(200, send("GET", "/api/me", token, null));

        // Catalog: tomorrow in the city, so every seeded show of the day is still ahead.
        List<Object> cities = expect(200, send("GET", "/api/cities", null, null)).list();
        assertTrue(cities.stream().anyMatch(c -> "bengaluru".equals(obj(c).get("id"))));
        String date = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1).toString();
        Map<String, Object> movies =
                expect(200, send("GET", "/api/movies?city=bengaluru&date=" + date, null, null))
                        .json();
        List<Object> items = arr(movies.get("items"));
        assertFalse(items.isEmpty());
        long movieId = ((Number) obj(items.get(0)).get("id")).longValue();
        Map<String, Object> listing =
                expect(
                                200,
                                send(
                                        "GET",
                                        "/api/movies/"
                                                + movieId
                                                + "/shows?city=bengaluru&date="
                                                + date,
                                        null,
                                        null))
                        .json();
        Map<String, Object> cinema = obj(arr(listing.get("cinemas")).get(0));
        long showId = ((Number) obj(arr(cinema.get("shows")).get(0)).get("id")).longValue();
        expect(200, send("GET", "/api/shows/" + showId, null, null));

        // Seat map: the first two bookable seats.
        List<String> seats = new ArrayList<>();
        Map<String, Object> map =
                expect(200, send("GET", "/api/shows/" + showId + "/seats", null, null)).json();
        for (Object row : arr(map.get("rows"))) {
            for (Object seat : arr(obj(row).get("seats"))) {
                if ("AVAILABLE".equals(obj(seat).get("status")) && seats.size() < 2) {
                    seats.add((String) obj(seat).get("id"));
                }
            }
        }
        assertEquals(2, seats.size());

        // Live updates: subscribe before holding, expect the seat-held event.
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        CompletableFuture<HttpResponse<Void>> stream =
                http.sendAsync(
                        HttpRequest.newBuilder(
                                        URI.create(base + "/api/shows/" + showId + "/events"))
                                .header("Accept", "text/event-stream")
                                .build(),
                        HttpResponse.BodyHandlers.fromLineSubscriber(
                                new java.util.concurrent.Flow.Subscriber<String>() {
                                    @Override
                                    public void onSubscribe(
                                            java.util.concurrent.Flow.Subscription s) {
                                        s.request(Long.MAX_VALUE);
                                    }

                                    @Override
                                    public void onNext(String line) {
                                        events.add(line);
                                    }

                                    @Override
                                    public void onError(Throwable t) {}

                                    @Override
                                    public void onComplete() {}
                                }));
        awaitLine(events, "event: snapshot");

        // Hold, book, check out.
        Map<String, Object> hold =
                expect(
                                201,
                                send(
                                        "POST",
                                        "/api/holds",
                                        token,
                                        Map.of("showId", showId, "seats", seats),
                                        "Idempotency-Key",
                                        key()))
                        .json();
        awaitLine(events, "event: seat-held");
        String bookingKey = key();
        Map<String, Object> booking =
                expect(
                                201,
                                send(
                                        "POST",
                                        "/api/bookings",
                                        token,
                                        Map.of("holdId", hold.get("id")),
                                        "Idempotency-Key",
                                        bookingKey))
                        .json();
        String bookingId = (String) booking.get("id");
        assertEquals("HELD", booking.get("status"));
        // A replay with the same key returns the stored response from SQLite.
        Res replay =
                expect(
                        201,
                        send(
                                "POST",
                                "/api/bookings",
                                token,
                                Map.of("holdId", hold.get("id")),
                                "Idempotency-Key",
                                bookingKey));
        assertEquals("true", replay.raw().headers().firstValue("Idempotency-Replayed").orElse(""));

        Map<String, Object> checkout =
                expect(
                                201,
                                send(
                                        "POST",
                                        "/api/bookings/" + bookingId + "/checkout",
                                        token,
                                        null,
                                        "Idempotency-Key",
                                        key()))
                        .json();
        String paymentId = (String) checkout.get("paymentId");
        String checkoutUrl = (String) checkout.get("checkoutUrl");
        assertEquals(base + "/mock-gateway/" + paymentId, checkoutUrl);

        // Mock gateway: the page, then Pay, which posts a signed webhook back over HTTP.
        // Sent the way a browser navigates, so the SPA fallback must not swallow it.
        Res page = expect(200, send("GET", checkoutUrl, null, null, "Accept", "text/html"));
        assertTrue(page.body().contains("/mock-gateway/" + paymentId + "/complete"));
        Res paid =
                expect(
                        303,
                        send(
                                "POST",
                                "/mock-gateway/" + paymentId + "/complete",
                                null,
                                Map.of("outcome", "SUCCESS")));
        assertEquals(
                "/bookings/" + bookingId, paid.raw().headers().firstValue("Location").orElse(""));
        awaitLine(events, "event: seat-booked");

        Map<String, Object> confirmed =
                expect(200, send("GET", "/api/bookings/" + bookingId, token, null)).json();
        assertEquals("CONFIRMED", confirmed.get("status"));
        Res ticket =
                expect(200, send("GET", "/api/bookings/" + bookingId + "/ticket", token, null));
        assertTrue(ticket.body().contains((String) confirmed.get("code")));
        assertTrue(
                ticket.raw()
                        .headers()
                        .firstValue("Content-Type")
                        .orElse("")
                        .startsWith("text/html"));

        Map<String, Object> after =
                expect(200, send("GET", "/api/shows/" + showId + "/seats", null, null)).json();
        long booked =
                arr(after.get("rows")).stream()
                        .flatMap(r -> arr(obj(r).get("seats")).stream())
                        .filter(s -> seats.contains(obj(s).get("id")))
                        .filter(s -> "BOOKED".equals(obj(s).get("status")))
                        .count();
        assertEquals(2, booked);

        // RBAC: a USER is refused the admin area; the seeded ADMIN gets in.
        expect(403, send("GET", "/api/admin/stats", token, null));
        String admin =
                (String)
                        expect(
                                        200,
                                        send(
                                                "POST",
                                                "/api/auth/login",
                                                null,
                                                Map.of(
                                                        "email",
                                                        ADMIN_EMAIL,
                                                        "password",
                                                        ADMIN_PASSWORD)))
                                .json()
                                .get("accessToken");
        Map<String, Object> stats =
                expect(200, send("GET", "/api/admin/stats", admin, null)).json();
        assertEquals(1, ((Number) stats.get("bookingsConfirmedToday")).intValue());
        assertEquals(1, ((Number) stats.get("sseStreams")).intValue());

        // Shutdown ends the open stream instead of waiting for the show to start.
        long t0 = System.nanoTime();
        booted.close();
        booted = null;
        assertTrue(Duration.ofNanos(System.nanoTime() - t0).toSeconds() < 10, "shutdown hung");
        stream.get(10, TimeUnit.SECONDS);
    }

    private static void awaitLine(BlockingQueue<String> lines, String wanted) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            if (wanted.equals(line)) {
                return;
            }
        }
        throw new AssertionError("no '" + wanted + "' line on the SSE stream");
    }
}
