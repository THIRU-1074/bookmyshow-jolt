package dev.thiru.bms.web;

import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.domain.port.AdminRepositories;
import dev.thiru.bms.domain.port.UnitOfWork;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.kernel.catalog.Cinema;
import dev.thiru.bms.kernel.catalog.City;
import dev.thiru.bms.kernel.catalog.Movie;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.FakeLive;
import dev.thiru.bms.testing.MemoryRefreshTokens;
import dev.thiru.bms.testing.PlainHasher;
import dev.thiru.bms.testing.SliceKit;
import in.jolt.App;
import in.jolt.IdempotencyStore;
import in.jolt.test.JoltTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The whole web layer (pipeline and every legacy route module) on {@link SliceKit}: one SQLite
 * database behind both the legacy repositories and {@code ports.Db}, so a slice swapped into {@link
 * WebModules#routes} and the legacy routes around it read and write the same rows, as in
 * production. {@link FakeWorld} is only the gateway, ids, clock and seat-event publisher.
 *
 * <p>The V2 catalog is replaced by {@link FakeWorld#seeded()}'s (city bengaluru, cinema 2, screen 5
 * with 23 seats, movie 1, show 9 at {@code T0} + 3 days): V2 schedules its shows from the day the
 * database is migrated, and these tests run on the fixed {@link FakeWorld#T0}. Slice tests use the
 * V2 seed through {@link SliceKit} directly.
 */
final class WebKit implements AutoCloseable, AfterEachCallback {

    static final String SECRET = SliceKit.SECRET;
    static final String PASSWORD = SliceKit.PASSWORD;
    static final String WEBHOOK_SECRET = SliceKit.WEBHOOK_SECRET;
    static final long CINEMA_ID = 2;
    static final long MOVIE_ID = 1;

    final FakeWorld world = FakeWorld.seeded();
    final AtomicBoolean dbUp = new AtomicBoolean(true);
    final SliceKit slice;
    final UnitOfWork uow;
    final MemoryRefreshTokens refreshTokens;
    final IdempotencyStore idempotency;
    final FakeLive live;
    final App app;
    final JoltTest http;
    private final AtomicInteger addresses = new AtomicInteger();

    WebKit() {
        this(true, Duration.ofSeconds(15));
    }

    WebKit(boolean mockGateway, Duration heartbeat) {
        slice =
                SliceKit.builder()
                        .clock(world.clock)
                        .mockGateway(mockGateway)
                        .mount(
                                k -> {
                                    seedFakeWorldCatalog(k.testDb().legacyUnitOfWork(), k.db());
                                    WebModules.routes(
                                            k.pipeline(), deps(k, mockGateway, heartbeat));
                                })
                        .boot();
        uow = slice.testDb().legacyUnitOfWork();
        refreshTokens = slice.refreshTokens();
        idempotency = slice.idempotency();
        live = slice.live();
        app = slice.app();
        http = slice.http();
    }

    private WebDeps deps(SliceKit k, boolean mockGateway, Duration heartbeat) {
        return new WebDeps(
                k.testDb().legacyUnitOfWork(),
                k.db(),
                world.clock,
                world.policy,
                world,
                k.hasher(),
                k.refreshTokens(),
                k.idempotency(),
                world,
                world,
                event -> 200,
                k.live(),
                dbUp::get,
                new WebDeps.Settings(
                        SECRET,
                        WEBHOOK_SECRET,
                        mockGateway,
                        Path.of("src/main/resources/views"),
                        heartbeat));
    }

    /**
     * Swaps the V2 catalog for FakeWorld's, written through the production repositories so the JSON
     * columns are exactly what the adapters write. Rows are inserted bare first because the
     * repositories update a row whose id is set.
     */
    private void seedFakeWorldCatalog(UnitOfWork sql, Db db) {
        Cinema cinema = world.inTransaction(w -> w.cinemas().findById(CINEMA_ID)).orElseThrow();
        Screen screen =
                world.inTransaction(w -> w.screens().findById(FakeWorld.SCREEN_ID)).orElseThrow();
        Movie movie = world.inTransaction(w -> w.movies().findById(MOVIE_ID)).orElseThrow();
        Show show = world.inTransaction(w -> w.shows().findById(FakeWorld.SHOW_ID)).orElseThrow();
        List<City> cities = world.inTransaction(w -> w.cities().findAll());
        db.inTransaction(
                tx -> {
                    for (String table :
                            List.of(
                                    "show",
                                    "screen",
                                    "cinema",
                                    "movie_language",
                                    "movie_genre",
                                    "movie",
                                    "city")) {
                        tx.update("DELETE FROM " + table);
                    }
                    return null;
                });
        sql.inTransaction(
                r -> {
                    AdminRepositories.CatalogWrites writes = r.catalogWrites();
                    cities.forEach(writes::saveCity);
                    return null;
                });
        db.inTransaction(
                tx -> {
                    tx.insert(
                            "INSERT INTO cinema (id, city_id, name, address) VALUES (?, ?, '', '')",
                            cinema.id(),
                            cinema.cityId());
                    tx.insert(
                            "INSERT INTO screen (id, cinema_id, name, layout_json, capacity)"
                                    + " VALUES (?, ?, '', '{}', 1)",
                            screen.id(),
                            screen.cinemaId());
                    tx.insert(
                            "INSERT INTO movie (id, title, certificate, duration_minutes,"
                                    + " release_date, poster_url, description)"
                                    + " VALUES (?, '', 'U', 1, '', '', '')",
                            movie.id());
                    return null;
                });
        sql.inTransaction(
                r -> {
                    AdminRepositories.CatalogWrites writes = r.catalogWrites();
                    writes.saveCinema(cinema);
                    writes.saveScreen(screen);
                    writes.saveMovie(movie);
                    r.shows().save(show);
                    return null;
                });
    }

    @Override
    public void afterEach(ExtensionContext context) {
        close();
    }

    @Override
    public void close() {
        slice.close();
    }

    /** Rows in {@code table}, for tests that check what a request left behind. */
    long count(String table) {
        return slice.db()
                .inTransaction(
                        tx ->
                                tx.queryOne("SELECT count(*) FROM " + table, rs -> rs.getLong(1))
                                        .orElseThrow());
    }

    Db db() {
        return slice.db();
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
        if (uow.inTransaction(r -> r.users().findByEmail("admin@bms.test")).isPresent()) {
            return login("admin@bms.test");
        }
        uow.inTransaction(
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
        return uow.inTransaction(r -> r.users().findByEmail(email)).orElseThrow().id();
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
