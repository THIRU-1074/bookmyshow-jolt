package dev.thiru.bms.testing;

import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.web.HttpPipeline;
import in.jolt.App;
import in.jolt.IdempotencyStore;
import in.jolt.Jolt;
import in.jolt.JoltConfig;
import in.jolt.RouteModule;
import in.jolt.test.JoltTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Route harness for feature slices: a {@link TestDb} (migrated and seeded SQLite behind {@link
 * Db}), the production {@link HttpPipeline}, port fakes and a {@link JoltTest} client. A slice test
 * boots the kit with its routes, calls HTTP and asserts:
 *
 * <pre>{@code
 * @RegisterExtension
 * final SliceKit kit = SliceKit.boot(k -> List.of(new CatalogRoutes(k.db(), k.clock())));
 *
 * @Test
 * void cities() {
 *     kit.http().get("/api/cities").expect(200).jsonEquals("0.id", "bengaluru");
 * }
 * }</pre>
 *
 * <p>As a JUnit extension the kit closes after each test; otherwise use try-with-resources. The
 * clock starts at 08:00 IST on {@link #seedDate()}, two hours before the first seeded show.
 */
public final class SliceKit implements AutoCloseable, AfterEachCallback {

    public static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";
    public static final String WEBHOOK_SECRET = "webhook-secret-that-is-at-least-32-bytes";

    /** Password of every account the kit creates (stored through {@link PlainHasher}). */
    public static final String PASSWORD = "correct-horse-battery";

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final String ADMIN_EMAIL = "admin@bms.test";
    static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private final Path dir;
    private final boolean ownsDir;
    private final TestDb testDb;
    private final LocalDate seedDate;
    private final Clock clock;
    private final BookingPolicy policy = BookingPolicy.defaults();
    private final PlainHasher hasher = new PlainHasher();
    private final MemoryRefreshTokens refreshTokens = new MemoryRefreshTokens();
    private final SequentialIds ids = new SequentialIds();
    private final RecordingGateway gateway = new RecordingGateway();
    private final RecordingEvents events = new RecordingEvents();
    private final RecordingCallbacks callbacks = new RecordingCallbacks();
    private final IdempotencyStore idempotency = IdempotencyStore.inMemory();
    private final FakeLive live;
    private final App app;
    private final HttpPipeline pipeline;
    private final JoltTest http;
    private final AtomicInteger addresses = new AtomicInteger();
    private final AtomicInteger keys = new AtomicInteger();
    private boolean closed;

    private SliceKit(Builder b) {
        this.ownsDir = b.dir == null;
        this.dir = ownsDir ? tempDir() : b.dir;
        this.testDb = TestDb.open(dir);
        try {
            this.seedDate = firstShowDate(testDb.db());
            this.clock =
                    b.clock != null
                            ? b.clock
                            : new MutableClock(
                                    seedDate.atTime(LocalTime.of(8, 0)).atZone(IST).toInstant());
            this.live = new FakeLive(b.liveStreams);
            this.app = Jolt.create(JoltConfig.defaults().problemJson(true));
            this.pipeline =
                    HttpPipeline.install(
                            app,
                            new HttpPipeline.Config(
                                    SECRET, clock, idempotency, b.mockGateway, b.viewsDir));
            for (Consumer<SliceKit> mount : b.mounts) {
                mount.accept(this);
            }
            this.http = JoltTest.of(app);
        } catch (RuntimeException | Error e) {
            close();
            throw e;
        }
    }

    /** A kit in its own temp directory with the routes {@code routes} builds from it. */
    public static SliceKit boot(Function<SliceKit, ? extends List<? extends RouteModule>> routes) {
        return builder().routes(routes).boot();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Options; every one has a default, so {@code builder().boot()} is a bare pipeline. */
    public static final class Builder {
        private Path dir;
        private Clock clock;
        private boolean mockGateway;
        private Path viewsDir = Path.of("src/main/resources/views");
        private int liveStreams = 5;
        private final List<Consumer<SliceKit>> mounts = new ArrayList<>();

        private Builder() {}

        /** Database directory (a JUnit {@code @TempDir}); default: a temp dir deleted on close. */
        public Builder dir(Path dir) {
            this.dir = dir;
            return this;
        }

        /** Default: a {@link MutableClock} at 08:00 IST on the seed date. */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** Guard {@code /mock-gateway} in the pipeline as MOCK_GATEWAY=true does. Default off. */
        public Builder mockGateway(boolean on) {
            this.mockGateway = on;
            return this;
        }

        public Builder viewsDir(Path viewsDir) {
            this.viewsDir = viewsDir;
            return this;
        }

        /** Stream cap of the {@link FakeLive}; default 5. */
        public Builder liveStreams(int max) {
            this.liveStreams = max;
            return this;
        }

        /** Mounts the route modules {@code routes} builds; called once the pipeline exists. */
        public Builder routes(Function<SliceKit, ? extends List<? extends RouteModule>> routes) {
            mounts.add(kit -> routes.apply(kit).forEach(kit.pipeline::module));
            return this;
        }

        /** Free-form registration on the booted kit (the full route table uses this). */
        public Builder mount(Consumer<SliceKit> mount) {
            mounts.add(mount);
            return this;
        }

        public SliceKit boot() {
            return new SliceKit(this);
        }
    }

    // ---- what a slice is built from ----

    public Db db() {
        return testDb.db();
    }

    public TestDb testDb() {
        return testDb;
    }

    public Clock clock() {
        return clock;
    }

    /** The default clock; fails if the builder was given another one. */
    public MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    public BookingPolicy policy() {
        return policy;
    }

    public PlainHasher hasher() {
        return hasher;
    }

    public MemoryRefreshTokens refreshTokens() {
        return refreshTokens;
    }

    public IdempotencyStore idempotency() {
        return idempotency;
    }

    public FakeLive live() {
        return live;
    }

    /** Deterministic ids ({@code id-1}, ...) and a seeded random source for booking codes. */
    public SequentialIds ids() {
        return ids;
    }

    /** Records sessions and refunds; checkout URLs are {@code http://pay.test/<paymentId>}. */
    public RecordingGateway gateway() {
        return gateway;
    }

    /** Records the seat events a slice publishes after commit. */
    public RecordingEvents events() {
        return events;
    }

    /** Records the mock gateway's webhook deliveries; answers 200 unless told otherwise. */
    public RecordingCallbacks callbacks() {
        return callbacks;
    }

    public App app() {
        return app;
    }

    public HttpPipeline pipeline() {
        return pipeline;
    }

    public JoltTest http() {
        return http;
    }

    /** The database directory. */
    Path dir() {
        return dir;
    }

    /** The IST date of the first seeded show: V2 seeds 14 days of shows from it. */
    public LocalDate seedDate() {
        return seedDate;
    }

    // ---- accounts and tokens ----

    /** Creates an AUDIENCE account (password {@link #PASSWORD}) and returns its access token. */
    public String audience(String email) {
        return token(createUser(email, Role.AUDIENCE));
    }

    /** The ADMIN account {@link #ADMIN_EMAIL}, created on first use; returns an access token. */
    public String admin() {
        long id = findUser(ADMIN_EMAIL);
        return token(id > 0 ? id : createUser(ADMIN_EMAIL, Role.ADMIN, Role.AUDIENCE));
    }

    /** Creates a THEATRE_OWNER linked to {@code cinemaIds}; returns its access token. */
    public String owner(String email, long... cinemaIds) {
        long id = createUser(email, Role.AUDIENCE, Role.THEATRE_OWNER);
        db().inTransaction(
                        tx -> {
                            for (long cinema : cinemaIds) {
                                tx.update(
                                        "INSERT INTO cinema_owner (cinema_id, user_id)"
                                                + " VALUES (?, ?)",
                                        cinema,
                                        id);
                            }
                            return null;
                        });
        return token(id);
    }

    /** Inserts an account with {@code roles} (password {@link #PASSWORD}); returns its id. */
    public long createUser(String email, Role... roles) {
        String stored =
                Arrays.stream(roles).map(Role::name).sorted().collect(Collectors.joining(","));
        return db().inTransaction(
                        tx ->
                                tx.insert(
                                        "INSERT INTO app_user (email, password_hash, name, roles,"
                                                + " created_at) VALUES (?, ?, ?, ?, ?)",
                                        email,
                                        PlainHasher.PREFIX + PASSWORD,
                                        "Test",
                                        stored,
                                        Db.millis(clock.instant())));
    }

    /** The id of the account with {@code email}. */
    public long idOf(String email) {
        long id = findUser(email);
        if (id <= 0) {
            throw new IllegalArgumentException("no account " + email);
        }
        return id;
    }

    private long findUser(String email) {
        return db().inTransaction(
                        tx ->
                                tx.queryOne(
                                                "SELECT id FROM app_user WHERE email = ?",
                                                rs -> rs.getLong(1),
                                                email)
                                        .orElse(0L));
    }

    /**
     * An access token for account {@code id} with the roles stored for it, signed as the auth
     * routes sign them (subject is the id, claim {@code roles}).
     */
    public String token(long id) {
        String stored =
                db().inTransaction(
                                tx ->
                                        tx.queryOne(
                                                        "SELECT roles FROM app_user WHERE id = ?",
                                                        rs -> rs.getString(1),
                                                        id)
                                                .orElseThrow());
        List<String> roles =
                Stream.of(stored.split(",")).sorted(Comparator.naturalOrder()).toList();
        return pipeline.jwt().sign(Long.toString(id), Map.of("roles", roles), TOKEN_TTL);
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    /** A distinct client address per call keeps the per-IP auth limits out of unrelated tests. */
    public String nextAddress() {
        return "10.0.0." + (addresses.incrementAndGet() % 250 + 1);
    }

    /** A fresh Idempotency-Key. */
    public String key() {
        return "key-" + keys.incrementAndGet();
    }

    // ---- lifecycle ----

    @Override
    public void afterEach(ExtensionContext context) {
        close();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        testDb.close();
        if (ownsDir) {
            deleteQuietly(dir);
        }
    }

    private static LocalDate firstShowDate(Db db) {
        Instant first =
                db.inTransaction(
                        tx ->
                                tx.queryOne(
                                                "SELECT min(starts_at) AS first FROM show",
                                                rs -> Db.instant(rs, "first"))
                                        .orElseThrow());
        return first.atZone(IST).toLocalDate();
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("bms-slice");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | UncheckedIOException e) {
            // Best effort: the OS temp dir is cleaned anyway.
        }
    }
}
