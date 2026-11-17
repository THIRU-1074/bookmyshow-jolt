package dev.thiru.bms;

import dev.thiru.bms.domain.BookingPolicy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Every setting from SPEC section 9, read once at startup. A bad or missing value fails here,
 * before anything opens, with a message that names the variable but never echoes a secret.
 *
 * <p>TODO(jolt#60): replace with Jolt's configuration loader once released.
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
    static final String DEFAULT_STATIC_DIR = "src/main/resources/static";
    static final String DEFAULT_VIEWS_DIR = "src/main/resources/views";
    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    public static AppConfig fromEnv(Map<String, String> env) {
        int port = port(get(env, "PORT"));
        String jwt = secret(env, "JWT_SECRET");
        String hook = secret(env, "WEBHOOK_SECRET");
        String adminEmail = get(env, "ADMIN_EMAIL");
        String adminPassword = get(env, "ADMIN_PASSWORD");
        if ((adminEmail == null) != (adminPassword == null)) {
            throw new IllegalArgumentException(
                    "Set both ADMIN_EMAIL and ADMIN_PASSWORD, or neither");
        }
        if (adminPassword != null && adminPassword.length() < MIN_ADMIN_PASSWORD) {
            throw new IllegalArgumentException(
                    "ADMIN_PASSWORD must be at least " + MIN_ADMIN_PASSWORD + " characters");
        }
        String dbUrl = orDefault(get(env, "DB_URL"), DEFAULT_DB_URL);
        sqliteFile(dbUrl);
        BookingPolicy policy;
        try {
            policy =
                    new BookingPolicy(
                            duration(env, "HOLD_TTL", "PT7M"),
                            duration(env, "PAYMENT_WINDOW", "PT5M"),
                            positiveInt(env, "MAX_SEATS", 10),
                            MAX_CHECKOUTS);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid booking limits: " + e.getMessage(), e);
        }
        String trust = get(env, "TRUST_PROXY");
        return new AppConfig(
                port,
                dbUrl,
                jwt,
                hook,
                adminEmail,
                adminPassword,
                policy,
                duration(env, "SWEEP_INTERVAL", "PT30S"),
                positiveInt(env, "SSE_MAX_STREAMS", 1000),
                publicBaseUrl(env, port),
                bool(env, "MOCK_GATEWAY", true),
                get(env, "METRICS_TOKEN"),
                trust == null
                        ? List.of()
                        : Arrays.stream(trust.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList(),
                Path.of(orDefault(get(env, "STATIC_DIR"), DEFAULT_STATIC_DIR)),
                Path.of(orDefault(get(env, "VIEWS_DIR"), DEFAULT_VIEWS_DIR)),
                "1".equals(get(env, "JOLT_DEV")));
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
    static URI publicBaseUrl(Map<String, String> env, int port) {
        String raw = get(env, "PUBLIC_BASE_URL");
        String source = "PUBLIC_BASE_URL";
        if (raw == null) {
            raw = get(env, "RENDER_EXTERNAL_URL");
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

    static int port(String value) {
        if (value == null) {
            return 8080;
        }
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PORT must be a number", e);
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("PORT out of range: " + value);
        }
        return port;
    }

    private static String secret(Map<String, String> env, String name) {
        String value = get(env, name);
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    name + " is required and must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        return value;
    }

    private static Duration duration(Map<String, String> env, String name, String fallback) {
        String raw = orDefault(get(env, name), fallback);
        Duration d;
        try {
            d = Duration.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    name + " must be an ISO-8601 duration like PT30S", e);
        }
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return d;
    }

    private static int positiveInt(Map<String, String> env, String name, int fallback) {
        String raw = get(env, name);
        if (raw == null) {
            return fallback;
        }
        int n;
        try {
            n = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number", e);
        }
        if (n < 1) {
            throw new IllegalArgumentException(name + " must be at least 1");
        }
        return n;
    }

    private static boolean bool(Map<String, String> env, String name, boolean fallback) {
        String raw = get(env, name);
        if (raw == null) {
            return fallback;
        }
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(name + " must be true or false");
        };
    }

    /** Blank counts as unset, so an empty dashboard field behaves like a missing one. */
    private static String get(Map<String, String> env, String name) {
        String v = env.get(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
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
                + viewsDir
                + ", joltDev="
                + joltDev
                + "]";
    }
}
