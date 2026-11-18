package dev.thiru.bms;

import dev.thiru.bms.kernel.booking.BookingPolicy;
import in.jolt.Config;
import in.jolt.ConfigException;
import in.jolt.JoltConfig;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Every setting from SPEC section 9, read once at startup through Jolt's {@link Config}: the
 * environment, then {@code application.properties} (and a {@code JOLT_PROFILE} file), keyed by the
 * SPEC variable names. A bad or missing value fails here, before anything opens, with an {@link
 * IllegalArgumentException} that names the variable but never echoes a secret.
 */
public record AppConfig(
        int port,
        String dbUrl,
        String jwtSecret,
        String webhookSecret,
        String adminEmail,
        String adminPassword,
        BookingPolicy policy,
        Duration sweepInterval,
        int sseMaxStreams,
        URI publicBaseUrl,
        boolean mockGateway,
        String metricsToken,
        List<String> trustProxy,
        Path staticDir,
        Path viewsDir,
        boolean joltDev) {

    static final int MIN_SECRET_BYTES = 32;
    static final int MIN_ADMIN_PASSWORD = 12;
    static final int MAX_CHECKOUTS = 3;
    static final String DEFAULT_DB_URL = "jdbc:sqlite:data/bms.db";

    /** The React build output (npm run build in web/); the Docker image sets /app/static. */
    static final String DEFAULT_STATIC_DIR = "web/dist";

    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    /** The process environment and properties files, as {@code main} reads them. */
    public static AppConfig load() {
        return from(Config.load());
    }

    /** {@code env} as the environment, plus any properties files in the working directory. */
    public static AppConfig fromEnv(Map<String, String> env) {
        return from(Config.load(env, Path.of("").toAbsolutePath()));
    }

    public static AppConfig from(Config c) {
        try {
            return read(c);
        } catch (ConfigException e) {
            // One failure type for every bad setting; Jolt's message names the key, not the value.
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static AppConfig read(Config c) {
        int port = port(c.integer("PORT", 8080));
        String jwt = secret(c, "JWT_SECRET");
        String hook = secret(c, "WEBHOOK_SECRET");
        String adminEmail = c.string("ADMIN_EMAIL", null);
        String adminPassword = c.string("ADMIN_PASSWORD", null);
        if ((adminEmail == null) != (adminPassword == null)) {
            throw new IllegalArgumentException(
                    "Set both ADMIN_EMAIL and ADMIN_PASSWORD, or neither");
        }
        if (adminPassword != null && adminPassword.length() < MIN_ADMIN_PASSWORD) {
            throw new IllegalArgumentException(
                    "ADMIN_PASSWORD must be at least " + MIN_ADMIN_PASSWORD + " characters");
        }
        String dbUrl = c.string("DB_URL", DEFAULT_DB_URL);
        sqliteFile(dbUrl);
        BookingPolicy policy;
        try {
            policy =
                    new BookingPolicy(
                            duration(c, "HOLD_TTL", Duration.ofMinutes(7)),
                            duration(c, "PAYMENT_WINDOW", Duration.ofMinutes(5)),
                            positiveInt(c, "MAX_SEATS", 10),
                            MAX_CHECKOUTS);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid booking limits: " + e.getMessage(), e);
        }
        String trust = c.string("TRUST_PROXY", null);
        return new AppConfig(
                port,
                dbUrl,
                jwt,
                hook,
                adminEmail,
                adminPassword,
                policy,
                duration(c, "SWEEP_INTERVAL", Duration.ofSeconds(30)),
                positiveInt(c, "SSE_MAX_STREAMS", 1000),
                publicBaseUrl(c, port),
                c.bool("MOCK_GATEWAY", true),
                c.string("METRICS_TOKEN", null),
                trustProxy(trust),
                Path.of(c.string("STATIC_DIR", DEFAULT_STATIC_DIR)),
                optionalPath(c, "VIEWS_DIR"),
                "1".equals(c.string("JOLT_DEV", null)));
    }

    /** Unset means the class path ({@code /views} inside the app). */
    private static Path optionalPath(Config c, String name) {
        String v = c.string(name, null);
        return v == null ? null : Path.of(v);
    }

    public boolean seedAdmin() {
        return adminEmail != null && adminPassword != null;
    }

    /** The database file named by {@link #dbUrl}; its parent directory is created at startup. */
    public Path sqliteFile() {
        return sqliteFile(dbUrl);
    }

    private static Path sqliteFile(String url) {
        if (!url.startsWith(SQLITE_PREFIX)) {
            throw new IllegalArgumentException("DB_URL must be a jdbc:sqlite: URL");
        }
        String rest = url.substring(SQLITE_PREFIX.length());
        int q = rest.indexOf('?');
        String file = q < 0 ? rest : rest.substring(0, q);
        if (file.isBlank() || file.contains(":memory:") || file.startsWith("file::memory")) {
            // Every pooled connection would see its own empty database.
            throw new IllegalArgumentException("DB_URL must name a database file, not memory");
        }
        return Path.of(file);
    }

    /**
     * PUBLIC_BASE_URL, else Render's RENDER_EXTERNAL_URL, else {@code http://localhost:PORT}. It
     * builds checkout URLs and is where the mock gateway posts its webhook.
     */
    static URI publicBaseUrl(Config c, int port) {
        String raw = c.string("PUBLIC_BASE_URL", null);
        String source = "PUBLIC_BASE_URL";
        if (raw == null) {
            raw = c.string("RENDER_EXTERNAL_URL", null);
            source = "RENDER_EXTERNAL_URL";
        }
        if (raw == null) {
            return URI.create("http://localhost:" + port);
        }
        String trimmed = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source + " is not a valid URL", e);
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException(source + " must be an absolute http(s) URL");
        }
        return uri;
    }

    static int port(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("PORT out of range: " + port);
        }
        return port;
    }

    private static String secret(Config c, String name) {
        String value = c.string(name, null);
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    name + " is required and must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        return value;
    }

    /** ISO-8601 ({@code PT30S}) or Jolt's short form ({@code 30s}); must be positive. */
    private static Duration duration(Config c, String name, Duration fallback) {
        Duration d = c.duration(name, fallback);
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return d;
    }

    private static int positiveInt(Config c, String name, int fallback) {
        int n = c.integer(name, fallback);
        if (n < 1) {
            throw new IllegalArgumentException(name + " must be at least 1");
        }
        return n;
    }

    /**
     * The TRUST_PROXY entries, checked by Jolt's own parser now so a bad entry stops startup with a
     * message naming the variable, rather than later inside {@code Main.joltConfig}.
     */
    private static List<String> trustProxy(String value) {
        if (value == null) {
            return List.of();
        }
        List<String> specs =
                Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        try {
            JoltConfig.defaults().trustProxy(specs.toArray(String[]::new));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("TRUST_PROXY: " + e.getMessage(), e);
        }
        return specs;
    }

    @Override
    public String toString() {
        return "AppConfig[port="
                + port
                + ", dbUrl="
                + dbUrl
                + ", admin="
                + (seedAdmin() ? "set" : "unset")
                + ", policy="
                + policy
                + ", sweepInterval="
                + sweepInterval
                + ", sseMaxStreams="
                + sseMaxStreams
                + ", publicBaseUrl="
                + publicBaseUrl
                + ", mockGateway="
                + mockGateway
                + ", metrics="
                + (metricsToken == null ? "off" : "on")
                + ", trustProxy="
                + trustProxy
                + ", staticDir="
                + staticDir
                + ", viewsDir="
                + (viewsDir == null ? "classpath" : viewsDir)
                + ", joltDev="
                + joltDev
                + "]";
    }
}
