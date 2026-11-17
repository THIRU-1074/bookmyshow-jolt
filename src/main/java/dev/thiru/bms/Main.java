package dev.thiru.bms;

import dev.thiru.bms.adapters.auth.Pbkdf2PasswordHasher;
import dev.thiru.bms.adapters.db.SchemaRunner;
import dev.thiru.bms.adapters.db.SqlIdempotency;
import dev.thiru.bms.adapters.db.SqlRefreshTokens;
import dev.thiru.bms.adapters.db.Sqlite;
import dev.thiru.bms.adapters.db.SqliteDb;
import dev.thiru.bms.adapters.db.SqliteUnitOfWork;
import dev.thiru.bms.adapters.ids.SecureIds;
import dev.thiru.bms.adapters.live.LiveSeatHub;
import dev.thiru.bms.adapters.payment.MockGatewayClient;
import dev.thiru.bms.adapters.payment.MockGatewaySender;
import dev.thiru.bms.adapters.payment.MockPaymentGateway;
import dev.thiru.bms.adapters.payment.WebhookSigner;
import dev.thiru.bms.application.ExpireSweep;
import dev.thiru.bms.features.accounts.Accounts;
import dev.thiru.bms.web.WebDeps;
import dev.thiru.bms.web.WebModules;
import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.Jolt;
import in.jolt.JoltConfig;
import in.jolt.Middleware;
import in.jolt.Server;
import in.jolt.Static;
import in.jolt.db.Db;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;

/**
 * Composition root: the only class that names concrete adapters. Startup follows SPEC section 9:
 * validate config (secrets fail fast), open SQLite, migrate, seed the admin, start the sweeper,
 * install the web layer, then listen. {@link Booted#close} reverses it.
 */
public final class Main {

    private static final System.Logger LOG = System.getLogger("bms");

    /** SSE heartbeats every 15 s keep the 2 min idle limit from closing a quiet stream. */
    static final Duration SSE_HEARTBEAT = Duration.ofSeconds(15);

    static final Duration STREAM_IDLE = Duration.ofMinutes(2);

    /** Idempotency keys are remembered this long; clients retry within seconds, not days. */
    static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    static final int SSE_QUEUE = 256;
    static final int DB_POOL = 8;

    private Main() {}

    /** A built, not yet listening application and everything it must release on shutdown. */
    public static final class Booted implements AutoCloseable {
        private final App app;
        private final Db db;
        private final LiveSeatHub live;
        private volatile Server server;
        private boolean closed;

        private Booted(App app, Db db, LiveSeatHub live) {
            this.app = app;
            this.db = db;
            this.live = live;
        }

        public App app() {
            return app;
        }

        public Server listen(int port) {
            server = app.listen(port);
            return server;
        }

        /**
         * Ends live streams, then closes the server: it drains requests and runs the stop hooks,
         * which stop the sweeper and close the pool. Never listened: closes the pool directly.
         */
        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            // TODO(jolt#82): move to a pre-drain hook once Jolt has one.
            // Open SSE streams would otherwise hold the drain for its full grace period; Jolt's
            // stop hooks only run after the drain.
            live.closeAll();
            Server s = server;
            if (s != null) {
                s.close();
            } else {
                db.close();
            }
            LOG.log(Level.INFO, "Stopped");
        }
    }

    /** Jolt settings for this app on top of {@code base} (env-derived in production). */
    public static JoltConfig joltConfig(AppConfig config, JoltConfig base) {
        // Max 0 = no cap: a stream ends when its show starts or is cancelled, and the 15 s
        // heartbeat keeps it under the idle limit.
        JoltConfig c = base.problemJson(true).streamTimeouts(STREAM_IDLE, Duration.ZERO);
        return config.trustProxy().isEmpty()
                ? c
                : c.trustProxy(config.trustProxy().toArray(String[]::new));
    }

    /** Builds the whole application on real adapters; the caller listens and closes. */
    public static Booted app(AppConfig config, JoltConfig jolt) {
        // The UI is the React build in STATIC_DIR; views come from the class path unless VIEWS_DIR
        // names a directory.
        Path staticDir = existingDir(config.staticDir(), "STATIC_DIR");
        Path viewsDir =
                config.viewsDir() == null ? null : existingDir(config.viewsDir(), "VIEWS_DIR");
        Clock clock = Clock.systemUTC();

        String url = Sqlite.withPragmas(config.dbUrl());
        createParent(config.sqliteFile());
        java.util.List<Integer> applied = SchemaRunner.migrate(url);
        LOG.log(Level.INFO, "Migrations applied: " + applied);
        Db db = Sqlite.open(url, DB_POOL, Duration.ofSeconds(10));
        try {
            SqliteUnitOfWork uow = new SqliteUnitOfWork(db);
            // The same pool behind ports.Db for the feature slices; one BEGIN IMMEDIATE per call.
            SqliteDb sliceDb = new SqliteDb(db);
            Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();
            if (config.seedAdmin()) {
                boolean created =
                        new Accounts(sliceDb, hasher, clock)
                                .ensureAdmin(config.adminEmail(), config.adminPassword());
                LOG.log(Level.INFO, created ? "Admin account created" : "Admin account kept");
            }

            LiveSeatHub live = new LiveSeatHub(config.sseMaxStreams(), SSE_QUEUE);
            SqlRefreshTokens refreshTokens = new SqlRefreshTokens(db);
            SqlIdempotency idempotency = new SqlIdempotency(db, clock);

            WebhookSigner signer = new WebhookSigner(config.webhookSecret());
            URI webhook = URI.create(config.publicBaseUrl() + "/api/payments/webhook");
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            WebDeps deps =
                    new WebDeps(
                            uow,
                            sliceDb,
                            clock,
                            config.policy(),
                            new SecureIds(),
                            hasher,
                            refreshTokens,
                            idempotency,
                            new MockPaymentGateway(config.publicBaseUrl()),
                            live,
                            new MockGatewaySender(
                                    new MockGatewayClient(http, signer, clock, webhook)),
                            live,
                            () -> dbUp(db),
                            new WebDeps.Settings(
                                    config.jwtSecret(),
                                    config.webhookSecret(),
                                    config.mockGateway(),
                                    viewsDir,
                                    SSE_HEARTBEAT));

            App app = Jolt.create(joltConfig(config, jolt));
            LOG.log(Level.INFO, ProxyDiagnostics.describe(app.config().trustedProxies()));
            // First, so it sees every request, including those the gate refuses.
            app.use(ProxyDiagnostics.firstRequest(line -> LOG.log(Level.INFO, line)));
            // Stop hooks run in reverse: the sweeper stops before the pool closes.
            app.onStop(db::close);
            live.metrics(app.metrics());
            scheduleSweep(app, config, clock, uow, live, refreshTokens, idempotency);
            // Registers the SPEC 9 middleware chain first, then every API route.
            WebModules.install(app, deps);
            if (config.metricsToken() != null) {
                app.exposeMetrics(bearer(config.metricsToken()));
            }
            if (config.joltDev()) {
                app.exposeRoutes();
            }
            serveStatic(app, staticDir);
            return new Booted(app, db, live);
        } catch (RuntimeException | Error e) {
            db.close();
            throw e;
        }
    }

    /**
     * Expires holds and bookings, purges dead refresh tokens and old idempotency keys every {@code
     * SWEEP_INTERVAL}, on Jolt's scheduler from {@code listen} until {@code Server.close()}. A
     * failed run is logged by Jolt and the next one still happens.
     */
    private static void scheduleSweep(
            App app,
            AppConfig config,
            Clock clock,
            SqliteUnitOfWork uow,
            LiveSeatHub live,
            SqlRefreshTokens refreshTokens,
            SqlIdempotency idempotency) {
        ExpireSweep sweep = new ExpireSweep(uow, live, clock);
        app.every(
                "expiry-sweep",
                config.sweepInterval(),
                () -> {
                    ExpireSweep.Result r = sweep.run();
                    refreshTokens.purgeExpired(clock.instant());
                    idempotency.purgeBefore(clock.instant().minus(IDEMPOTENCY_RETENTION));
                    if (r.holds() + r.bookings() > 0) {
                        LOG.log(
                                Level.INFO,
                                "Expired " + r.holds() + " holds, " + r.bookings() + " bookings");
                    }
                });
    }

    /** The React build in STATIC_DIR, including {@code /css/app.css} for the server views. */
    private static void serveStatic(App app, Path staticDir) {
        Static files = Static.serve("/", staticDir).spaFallback("index.html");
        app.use(
                (req, res, next) -> {
                    // Jolt's fallback answers any browser navigation (Accept: text/html) with
                    // index.html, so a mistyped /api URL would hide the problem+json 404.
                    if (isBackendPath(req.path())) {
                        next.run();
                    } else {
                        if ("GET".equals(req.method()) || "HEAD".equals(req.method())) {
                            res.header("Cache-Control", cacheControl(req.path()));
                        }
                        files.handle(req, res, next);
                    }
                });
    }

    /**
     * The UI build fingerprints everything under /assets/ (the file name changes with the content),
     * so those are cached for a year. Everything else, index.html above all, is revalidated on each
     * load so a deploy is picked up at once; ETag keeps that a cheap 304.
     */
    static String cacheControl(String path) {
        return path.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "no-cache";
    }

    static boolean isBackendPath(String path) {
        return path.equals("/api") || path.startsWith("/api/") || path.startsWith("/_jolt/");
    }

    static Path existingDir(Path dir, String variable) {
        Path abs = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(abs)) {
            throw new IllegalStateException(
                    variable
                            + " is not a directory: "
                            + abs
                            + " (run from the repo root or set it)");
        }
        return abs;
    }

    private static void createParent(Path dbFile) {
        Path parent = dbFile.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create database directory " + parent, e);
            }
        }
    }

    static boolean dbUp(Db db) {
        try {
            return db.queryOne("SELECT 1", rs -> rs.getInt(1)).orElse(0) == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Guards /_jolt/metrics with {@code Authorization: Bearer <METRICS_TOKEN>}. */
    static Middleware bearer(String token) {
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return (req, res, next) -> {
            String h = req.header("Authorization");
            if (h != null && MessageDigest.isEqual(expected, h.getBytes(StandardCharsets.UTF_8))) {
                next.run();
            } else {
                throw new HttpException(401, "Unauthorized");
            }
        };
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        LOG.log(Level.INFO, "Starting with " + config);
        Booted booted = app(config, JoltConfig.fromEnv());
        Server server = booted.listen(config.port());
        Runtime.getRuntime().addShutdownHook(new Thread(booted::close, "bms-shutdown"));
        LOG.log(Level.INFO, "Listening on port " + server.port() + " at " + config.publicBaseUrl());
    }
}
