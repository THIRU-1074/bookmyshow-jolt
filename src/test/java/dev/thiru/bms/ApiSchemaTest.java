package dev.thiru.bms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.adapters.payment.WebhookSigner;
import dev.thiru.bms.api.OpenApi;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Boots the real app (as {@link EndToEndTest} does) and walks every operation in {@code
 * api/openapi.yaml}, validating each JSON response, problem and SSE payload against its schema. It
 * fails when a response drifts from the schema or when an operation is never exercised, so the
 * schema the Java and TypeScript types are generated from stays the JSON the server really sends.
 */
class ApiSchemaTest {

    private static final String ADMIN_EMAIL = "admin@bms.test";
    private static final String ADMIN_PASSWORD = "admin-password-123";
    private static final String PASSWORD = "user-password-123";
    private static final JsonMapper JSON = JoltConfig.defaults().jsonMapper();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OpenApi api = OpenApi.load();
    private final TreeSet<String> covered = new TreeSet<>();
    private final List<String> failures = new ArrayList<>();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir Path dir;
    private Main.Booted booted;
    private String base;

    @BeforeEach
    void start() throws IOException {
        int port;
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
        env.put("STATIC_DIR", MainTest.STATIC_FIXTURE);
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

    // ---- client that validates every response against the schema ----

    private record Res(int status, String body, HttpResponse<String> raw, Object json) {
        Map<String, Object> obj() {
            return ApiSchemaTest.obj(json);
        }

        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    private Res call(int expected, String method, String path, String token, Object body)
            throws Exception {
        return call(expected, method, path, token, body, Map.of());
    }

    private Res call(
            int expected,
            String method,
            String path,
            String token,
            Object body,
            Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        headers.forEach(b::header);
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else if (body instanceof String raw) {
            b.method(method, HttpRequest.BodyPublishers.ofString(raw));
            if (!headers.containsKey("Content-Type")) {
                b.header("Content-Type", "application/json");
            }
        } else {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofByteArray(JSON.write(body)));
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, r.statusCode(), () -> method + " " + path + ": " + r.body());
        return new Res(r.statusCode(), r.body(), r, check(method, path, r));
    }

    /** Validates the response against its operation; returns the parsed JSON body, if any. */
    private Object check(String method, String path, HttpResponse<String> r) {
        OpenApi.Operation op =
                api.find(method, path)
                        .orElseThrow(
                                () -> new AssertionError("no operation " + method + " " + path));
        covered.add(op.key());
        String type = r.headers().firstValue("Content-Type").orElse("");
        String media = type.contains(";") ? type.substring(0, type.indexOf(';')).trim() : type;
        if (r.body().isEmpty()) {
            Optional<Map<String, Object>> schema = api.responseSchema(op, r.statusCode(), "-");
            assertTrue(schema.isEmpty(), op.key() + " " + r.statusCode() + " expects a body");
            return null;
        }
        Optional<Map<String, Object>> schema = api.responseSchema(op, r.statusCode(), media);
        if (!media.endsWith("json")) {
            return null;
        }
        // Since Jolt 0.4.1 every JSON body, problems included, names its charset.
        if (!type.equals(media + "; charset=utf-8")) {
            failures.add(op.key() + " " + r.statusCode() + ": Content-Type " + type);
        }
        Object json = parse(r.body());
        schema.ifPresent(
                s -> {
                    List<String> errors = api.validate(s, json);
                    if (!errors.isEmpty()) {
                        failures.add(op.key() + " " + r.statusCode() + ": " + errors);
                    }
                });
        return json;
    }

    private void checkSse(String schemaName, String data) {
        List<String> errors = api.validate(schemaName, parse(data));
        if (!errors.isEmpty()) {
            failures.add("SSE " + schemaName + ": " + errors);
        }
    }

    private static Object parse(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return body.trim().startsWith("[")
                ? JSON.read(bytes, List.class)
                : JSON.read(bytes, Map.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) {
        return (List<Object>) o;
    }

    private static long id(Object o) {
        return ((Number) obj(o).get("id")).longValue();
    }

    private static Map<String, String> key() {
        return Map.of("Idempotency-Key", UUID.randomUUID().toString());
    }

    private String login(String email, String password) throws Exception {
        return (String)
                call(
                                200,
                                "POST",
                                "/api/auth/login",
                                null,
                                Map.of("email", email, "password", password))
                        .obj()
                        .get("accessToken");
    }

    private String register(String email) throws Exception {
        call(
                201,
                "POST",
                "/api/auth/register",
                null,
                Map.of("email", email, "password", PASSWORD, "name", "Someone"));
        return login(email, PASSWORD);
    }

    private List<String> availableSeats(long showId, int n) throws Exception {
        List<String> seats = new ArrayList<>();
        for (Object row :
                arr(
                        call(200, "GET", "/api/shows/" + showId + "/seats", null, null)
                                .obj()
                                .get("rows"))) {
            for (Object seat : arr(obj(row).get("seats"))) {
                if ("AVAILABLE".equals(obj(seat).get("status")) && seats.size() < n) {
                    seats.add((String) obj(seat).get("id"));
                }
            }
        }
        return seats;
    }

    private String hold(String token, long showId, List<String> seats) throws Exception {
        return (String)
                call(
                                201,
                                "POST",
                                "/api/holds",
                                token,
                                Map.of("showId", showId, "seats", seats),
                                key())
                        .obj()
                        .get("id");
    }

    private String book(String token, String holdId) throws Exception {
        return (String)
                call(201, "POST", "/api/bookings", token, Map.of("holdId", holdId), key())
                        .obj()
                        .get("id");
    }

    // ---- the walk ----

    @Test
    void everyOperationAnswersWithTheJsonItsSchemaDescribes() throws Exception {
        call(200, "GET", "/api/health", null, null);

        // Auth, including the cookie routes.
        Res reg =
                call(
                        201,
                        "POST",
                        "/api/auth/register",
                        null,
                        Map.of("email", "buyer@bms.test", "password", PASSWORD, "name", "Buyer"));
        String cookie = reg.header("Set-Cookie").split(";")[0];
        Map<String, String> browser = Map.of("Cookie", cookie, "Sec-Fetch-Site", "same-origin");
        Res refreshed = call(200, "POST", "/api/auth/refresh", null, null, browser);
        String rotated = refreshed.header("Set-Cookie").split(";")[0];
        call(
                204,
                "POST",
                "/api/auth/logout",
                null,
                null,
                Map.of("Cookie", rotated, "Sec-Fetch-Site", "same-origin"));
        String buyer = login("buyer@bms.test", PASSWORD);
        call(200, "GET", "/api/me", buyer, null);

        // Catalog.
        call(200, "GET", "/api/cities", null, null);
        String date = LocalDate.now(IST).plusDays(1).toString();
        Map<String, Object> movies =
                call(200, "GET", "/api/movies?city=bengaluru&date=" + date, null, null).obj();
        Map<String, Object> movie = obj(arr(movies.get("items")).get(0));
        long movieId = id(movie);
        call(200, "GET", "/api/movies/" + movieId, null, null);
        Map<String, Object> listing =
                call(
                                200,
                                "GET",
                                "/api/movies/" + movieId + "/shows?city=bengaluru&date=" + date,
                                null,
                                null)
                        .obj();
        long showId = id(arr(obj(arr(listing.get("cinemas")).get(0)).get("shows")).get(0));
        List<Object> cinemas =
                arr(call(200, "GET", "/api/cinemas?city=bengaluru", null, null).obj().get("items"));
        call(200, "GET", "/api/cinemas/" + id(cinemas.get(0)), null, null);
        call(200, "GET", "/api/shows/" + showId, null, null);

        // Live seats: the snapshot and each change event carry schema-valid data.
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        CompletableFuture<HttpResponse<Void>> stream = openStream(showId, lines);
        Sse snapshot = awaitEvent(lines, "snapshot");
        checkSse("SeatMap", snapshot.data());
        // Every connect starts from a full snapshot, so it has no id to resume from.
        assertEquals(null, snapshot.id(), "snapshot id");
        long lastId = Long.MIN_VALUE;

        // Holds: one released (seat-released), one booked and paid through the mock gateway.
        List<String> seats = availableSeats(showId, 3);
        String released = hold(buyer, showId, List.of(seats.get(2)));
        Sse held = awaitEvent(lines, "seat-held");
        checkSse("SeatHeldEvent", held.data());
        lastId = checkEventId(held, lastId);
        call(204, "DELETE", "/api/holds/" + released, buyer, null);
        Sse freed = awaitEvent(lines, "seat-released");
        checkSse("SeatReleasedEvent", freed.data());
        lastId = checkEventId(freed, lastId);

        String bookingId = book(buyer, hold(buyer, showId, seats.subList(0, 2)));
        call(200, "GET", "/api/bookings/" + bookingId, buyer, null);
        String paymentId =
                (String)
                        call(
                                        201,
                                        "POST",
                                        "/api/bookings/" + bookingId + "/checkout",
                                        buyer,
                                        null,
                                        key())
                                .obj()
                                .get("paymentId");
        call(200, "GET", "/mock-gateway/" + paymentId, null, null, Map.of("Accept", "text/html"));
        call(
                303,
                "POST",
                "/mock-gateway/" + paymentId + "/complete",
                null,
                Map.of("outcome", "SUCCESS"));
        Sse booked = awaitEvent(lines, "seat-booked");
        checkSse("SeatBookedEvent", booked.data());
        checkEventId(booked, lastId);
        call(200, "GET", "/api/bookings/" + bookingId + "/ticket", buyer, null);
        call(200, "GET", "/api/bookings?status=CONFIRMED", buyer, null);

        // A second booking paid by a signed webhook, replayed, then cancelled with a refund.
        String second = book(buyer, hold(buyer, showId, availableSeats(showId, 1)));
        Map<String, Object> co =
                call(201, "POST", "/api/bookings/" + second + "/checkout", buyer, null, key())
                        .obj();
        String event =
                webhookBody((String) co.get("paymentId"), ((Number) co.get("amount")).longValue());
        call(200, "POST", "/api/payments/webhook", null, event, signed(event));
        Res dup = call(200, "POST", "/api/payments/webhook", null, event, signed(event));
        assertEquals(true, dup.obj().get("duplicate"));
        call(200, "POST", "/api/bookings/" + second + "/cancel", buyer, null, key());

        // Idempotency through Jolt: the same key and body replays, another body is refused.
        Map<String, String> once = key();
        Map<String, Object> holdBody = Map.of("showId", showId, "seats", availableSeats(showId, 1));
        Res first = call(201, "POST", "/api/holds", buyer, holdBody, once);
        Res replay = call(201, "POST", "/api/holds", buyer, holdBody, once);
        assertEquals("true", replay.header("Idempotency-Replayed"));
        assertEquals(first.body(), replay.body());
        Res reused =
                call(
                        422,
                        "POST",
                        "/api/holds",
                        buyer,
                        Map.of("showId", showId, "seats", availableSeats(showId, 1)),
                        once);
        assertEquals("IDEMPOTENCY", reused.obj().get("code"));
        Res running = call(409, "POST", "/api/holds", buyer, holdBody, inFlightCopy(once));
        assertEquals("IDEMPOTENCY", running.obj().get("code"));
        assertNotNull(running.header("Retry-After"), "Retry-After on an in-flight key");
        call(204, "DELETE", "/api/holds/" + first.obj().get("id"), buyer, null);

        // Problems.
        call(404, "GET", "/api/movies/999999", null, null);
        call(422, "POST", "/api/holds", buyer, Map.of("showId", showId, "seats", List.of()), key());
        call(401, "GET", "/api/me", null, null);
        call(403, "GET", "/api/admin/stats", buyer, null);

        adminAndOwnerRoutes();

        booted.close();
        booted = null;
        stream.get(10, TimeUnit.SECONDS);
        covered.add("GET /api/shows/{id}/events");

        assertEquals(List.of(), failures);
        TreeSet<String> all = new TreeSet<>();
        api.operations().forEach(op -> all.add(op.key()));
        all.removeAll(covered);
        assertEquals(new TreeSet<String>(), all, "operations never exercised");
    }

    private void adminAndOwnerRoutes() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        call(200, "GET", "/api/admin/stats", admin, null);

        // Catalog writes.
        call(
                201,
                "POST",
                "/api/admin/cities",
                admin,
                Map.of("id", "testville", "name", "Testville", "timeZone", "Asia/Kolkata"),
                key());
        call(200, "PUT", "/api/admin/cities/testville", admin, Map.of("name", "Testville Two"));
        // GET /api/cities was cached by the walk above; a catalog write clears the cache.
        assertTrue(
                call(200, "GET", "/api/cities", null, null).body().contains("Testville Two"),
                "catalog cache cleared on write");
        long cinema =
                id(
                        call(
                                        201,
                                        "POST",
                                        "/api/admin/cinemas",
                                        admin,
                                        Map.of(
                                                "cityId",
                                                "testville",
                                                "name",
                                                "Test Cinema",
                                                "address",
                                                "1 Test Road"),
                                        key())
                                .json());
        call(
                200,
                "PUT",
                "/api/admin/cinemas/" + cinema,
                admin,
                Map.of("name", "Test Cinema", "address", "2 Test Road"));
        long spare =
                id(
                        call(
                                        201,
                                        "POST",
                                        "/api/admin/cinemas",
                                        admin,
                                        Map.of(
                                                "cityId",
                                                "testville",
                                                "name",
                                                "Spare",
                                                "address",
                                                "3 Test Road"),
                                        key())
                                .json());
        call(204, "DELETE", "/api/admin/cinemas/" + spare, admin, null);

        Map<String, Object> layout =
                Map.of(
                        "categories",
                        List.of(Map.of("code", "CLASSIC", "name", "Classic")),
                        "rows",
                        List.of(
                                Map.of(
                                        "row",
                                        "A",
                                        "seats",
                                        10,
                                        "category",
                                        "CLASSIC",
                                        "aisleAfter",
                                        List.of(5),
                                        "blocked",
                                        List.of(1))));
        long screen =
                id(
                        call(
                                        201,
                                        "POST",
                                        "/api/admin/cinemas/" + cinema + "/screens",
                                        admin,
                                        Map.of("name", "Screen 1", "layout", layout),
                                        key())
                                .json());
        call(
                200,
                "PUT",
                "/api/admin/screens/" + screen,
                admin,
                Map.of("name", "Screen One", "layout", layout));
        long spareScreen =
                id(
                        call(
                                        201,
                                        "POST",
                                        "/api/admin/cinemas/" + cinema + "/screens",
                                        admin,
                                        Map.of("name", "Spare Screen", "layout", layout),
                                        key())
                                .json());
        call(204, "DELETE", "/api/admin/screens/" + spareScreen, admin, null);

        Map<String, Object> movieBody = new LinkedHashMap<>();
        movieBody.put("title", "Schema Movie");
        movieBody.put("languages", List.of("en"));
        movieBody.put("genres", List.of("Drama"));
        movieBody.put("certificate", "UA");
        movieBody.put("durationMinutes", 120);
        movieBody.put("releaseDate", "2026-11-01");
        movieBody.put("posterUrl", "/poster.png");
        movieBody.put("description", "A movie for the schema test.");
        long movie = id(call(201, "POST", "/api/admin/movies", admin, movieBody, key()).json());
        movieBody.put("title", "Schema Movie Two");
        call(200, "PUT", "/api/admin/movies/" + movie, admin, movieBody);
        long spareMovie =
                id(call(201, "POST", "/api/admin/movies", admin, movieBody, key()).json());
        call(204, "DELETE", "/api/admin/movies/" + spareMovie, admin, null);

        Map<String, Object> show = showBody(movie, screen, 20, 12);
        long adminShow = id(call(201, "POST", "/api/admin/shows", admin, show, key()).json());
        call(200, "PUT", "/api/admin/shows/" + adminShow, admin, showBody(movie, screen, 20, 15));

        // Reports, before and after a cancel.
        call(200, "GET", "/api/admin/reports/shows?city=bengaluru&size=5", admin, null);
        call(200, "GET", "/api/admin/reports/shows/" + adminShow, admin, null);
        call(200, "POST", "/api/admin/shows/" + adminShow + "/cancel", admin, null, key());

        // Users, roles and owner links.
        register("owner@bms.test");
        String other = register("other@bms.test");
        long ownerId =
                id(
                        arr(call(200, "GET", "/api/admin/users?q=owner@bms.test", admin, null)
                                        .obj()
                                        .get("items"))
                                .get(0));
        long otherId =
                id(
                        arr(call(200, "GET", "/api/admin/users?q=other@bms.test", admin, null)
                                        .obj()
                                        .get("items"))
                                .get(0));
        call(200, "PUT", "/api/admin/users/" + otherId + "/roles/THEATRE_OWNER", admin, null);
        call(200, "DELETE", "/api/admin/users/" + otherId + "/roles/THEATRE_OWNER", admin, null);
        call(200, "PUT", "/api/admin/cinemas/" + cinema + "/owners/" + otherId, admin, null);
        call(200, "DELETE", "/api/admin/cinemas/" + cinema + "/owners/" + otherId, admin, null);
        call(200, "PUT", "/api/admin/cinemas/" + cinema + "/owners/" + ownerId, admin, null);
        call(403, "GET", "/api/owner/cinemas", other, null);

        // Theatre owner, after signing in again so the token carries the role.
        String owner = login("owner@bms.test", PASSWORD);
        call(200, "GET", "/api/owner/cinemas", owner, null);
        long ownerScreen =
                id(
                        call(
                                        201,
                                        "POST",
                                        "/api/owner/cinemas/" + cinema + "/screens",
                                        owner,
                                        Map.of("name", "Owner Screen", "layout", layout),
                                        key())
                                .json());
        call(
                200,
                "PUT",
                "/api/owner/screens/" + ownerScreen,
                owner,
                Map.of("name", "Owner Screen 2", "layout", layout));
        long ownerShow =
                id(
                        call(
                                        201,
                                        "POST",
                                        "/api/owner/shows",
                                        owner,
                                        showBody(movie, ownerScreen, 21, 12),
                                        key())
                                .json());
        String day = LocalDate.now(IST).plusDays(21).toString();
        call(200, "GET", "/api/owner/reports/shows?from=" + day + "&to=" + day, owner, null);
        call(200, "GET", "/api/owner/reports/shows/" + ownerShow, owner, null);
        call(200, "POST", "/api/owner/shows/" + ownerShow + "/cancel", owner, null, key());
        call(404, "GET", "/api/owner/reports/shows/999999", owner, null);
    }

    private static Map<String, Object> showBody(long movie, long screen, int daysAhead, int hour) {
        Map<String, Object> show = new LinkedHashMap<>();
        show.put("movieId", movie);
        show.put("screenId", screen);
        show.put(
                "startsAt",
                LocalDate.now(IST)
                        .plusDays(daysAhead)
                        .atTime(hour, 0)
                        .atZone(IST)
                        .toInstant()
                        .toString());
        show.put("language", "en");
        show.put("format", "2D");
        show.put("prices", Map.of("CLASSIC", 20000L));
        return show;
    }

    /**
     * A fresh key whose row looks like {@code done}'s request still running: same scope and
     * fingerprint, no stored status. Racing a real request would be flaky; this is the row a
     * running request leaves.
     */
    private Map<String, String> inFlightCopy(Map<String, String> done) throws Exception {
        String key = UUID.randomUUID().toString();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("bms.db"));
                PreparedStatement copy =
                        c.prepareStatement(
                                "INSERT INTO idempotency_key (scope, key, fingerprint, created_at)"
                                        + " SELECT scope, ?, fingerprint, created_at"
                                        + " FROM idempotency_key WHERE key = ?")) {
            copy.setString(1, key);
            copy.setString(2, done.get("Idempotency-Key"));
            assertEquals(1, copy.executeUpdate());
        }
        return Map.of("Idempotency-Key", key);
    }

    // ---- webhook and SSE helpers ----

    private static String webhookBody(String paymentId, long amount) {
        return "{\"eventId\":\"evt-"
                + UUID.randomUUID()
                + "\",\"type\":\"payment.succeeded\",\"paymentId\":\""
                + paymentId
                + "\",\"gatewayRef\":\"mock_"
                + paymentId
                + "\",\"amount\":"
                + amount
                + ",\"currency\":\"INR\",\"occurredAt\":\""
                + java.time.Instant.now()
                + "\"}";
    }

    private static Map<String, String> signed(String body) {
        long ts = java.time.Instant.now().getEpochSecond();
        String sig = new WebhookSigner(AppConfigTest.HOOK).sign(ts, body);
        return Map.of("Bms-Timestamp", Long.toString(ts), "Bms-Signature", sig);
    }

    private CompletableFuture<HttpResponse<Void>> openStream(
            long showId, BlockingQueue<String> lines) {
        return http.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "/api/shows/" + showId + "/events"))
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.fromLineSubscriber(
                        new Flow.Subscriber<String>() {
                            @Override
                            public void onSubscribe(Flow.Subscription s) {
                                s.request(Long.MAX_VALUE);
                            }

                            @Override
                            public void onNext(String line) {
                                lines.add(line);
                            }

                            @Override
                            public void onError(Throwable t) {}

                            @Override
                            public void onComplete() {}
                        }));
    }

    /** One SSE message: its {@code id:} line ({@code null} when absent) and its data. */
    private record Sse(String id, String data) {}

    /**
     * The next {@code event: <name>} message. The id line, when sent, comes before the event line;
     * a blank line ends a message, so an id never leaks from one message into the next.
     */
    private static Sse awaitEvent(BlockingQueue<String> lines, String event) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String id = null;
        boolean seen = false;
        while (System.nanoTime() < deadline) {
            String line = lines.poll(200, TimeUnit.MILLISECONDS);
            if (line == null) {
                continue;
            }
            if (line.isEmpty()) {
                id = null;
                seen = false;
            } else if (line.startsWith("id:")) {
                id = line.substring("id:".length()).trim();
            } else if (line.equals("event: " + event)) {
                seen = true;
            } else if (seen && line.startsWith("data:")) {
                return new Sse(id, line.substring("data:".length()).trim());
            }
        }
        throw new AssertionError("no '" + event + "' event with data on the SSE stream");
    }

    /** Seat events carry the hub's id, increasing in publish order; returns it. */
    private long checkEventId(Sse message, long previous) {
        if (message.id() == null) {
            failures.add("SSE seat event without an id: line");
            return previous;
        }
        long id = Long.parseLong(message.id());
        if (id <= previous) {
            failures.add("SSE id " + id + " after " + previous);
        }
        return id;
    }
}
