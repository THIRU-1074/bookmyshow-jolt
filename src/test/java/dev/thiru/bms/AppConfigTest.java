package dev.thiru.bms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.Config;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {

    static final String JWT = "jwt-secret-that-is-at-least-32-bytes!!";
    static final String HOOK = "webhook-secret-that-is-at-least-32-bytes";

    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        m.put("JWT_SECRET", JWT);
        m.put("WEBHOOK_SECRET", HOOK);
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] == null) {
                m.remove(kv[i]);
            } else {
                m.put(kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    @Test
    void defaultsFollowSpecSection9() {
        AppConfig c = AppConfig.fromEnv(env());
        assertEquals(8080, c.port());
        assertEquals("jdbc:sqlite:data/bms.db", c.dbUrl());
        assertEquals(Duration.ofMinutes(7), c.policy().holdTtl());
        assertEquals(Duration.ofMinutes(5), c.policy().paymentWindow());
        assertEquals(10, c.policy().maxSeats());
        assertEquals(Duration.ofSeconds(30), c.sweepInterval());
        assertEquals(1000, c.sseMaxStreams());
        assertEquals(URI.create("http://localhost:8080"), c.publicBaseUrl());
        assertTrue(c.mockGateway());
        assertNull(c.metricsToken());
        assertEquals(List.of(), c.trustProxy());
        // Unset: the UI and views are served from the class path.
        assertNull(c.staticDir());
        assertNull(c.viewsDir());
        assertFalse(c.seedAdmin());
        assertFalse(c.joltDev());
    }

    @Test
    void secretsAreRequiredAndAtLeast32Bytes() {
        var missing =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AppConfig.fromEnv(env("JWT_SECRET", null)));
        assertTrue(missing.getMessage().contains("JWT_SECRET"));
        var shortHook =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AppConfig.fromEnv(env("WEBHOOK_SECRET", "short")));
        assertTrue(shortHook.getMessage().contains("WEBHOOK_SECRET"));
        // The value itself must never reach logs through the message.
        assertFalse(shortHook.getMessage().contains("short"));
    }

    @Test
    void toStringNeverPrintsSecrets() {
        String s =
                AppConfig.fromEnv(env("ADMIN_EMAIL", "a@b.c", "ADMIN_PASSWORD", "pw-long-enough"))
                        .toString();
        assertFalse(s.contains(JWT));
        assertFalse(s.contains(HOOK));
        assertFalse(s.contains("pw-long-enough"));
    }

    @Test
    void publicBaseUrlFallsBackToRenderThenLocalhost() {
        assertEquals(
                URI.create("https://bms.example.com"),
                AppConfig.fromEnv(
                                env(
                                        "PUBLIC_BASE_URL", "https://bms.example.com/",
                                        "RENDER_EXTERNAL_URL", "https://x.onrender.com"))
                        .publicBaseUrl());
        assertEquals(
                URI.create("https://x.onrender.com"),
                AppConfig.fromEnv(env("RENDER_EXTERNAL_URL", "https://x.onrender.com"))
                        .publicBaseUrl());
        assertEquals(
                URI.create("http://localhost:9090"),
                AppConfig.fromEnv(env("PORT", "9090", "PUBLIC_BASE_URL", " ")).publicBaseUrl());
        assertThrows(
                IllegalArgumentException.class,
                () -> AppConfig.fromEnv(env("PUBLIC_BASE_URL", "ftp://x")));
    }

    @Test
    void adminNeedsBothVariablesAndALongPassword() {
        assertTrue(
                AppConfig.fromEnv(env("ADMIN_EMAIL", "a@b.c", "ADMIN_PASSWORD", "pw-long-enough"))
                        .seedAdmin());
        assertThrows(
                IllegalArgumentException.class,
                () -> AppConfig.fromEnv(env("ADMIN_EMAIL", "a@b.c")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppConfig.fromEnv(env("ADMIN_EMAIL", "a@b.c", "ADMIN_PASSWORD", "short")));
    }

    @Test
    void parsesTunablesAndRejectsBadValues() {
        AppConfig c =
                AppConfig.fromEnv(
                        env(
                                "HOLD_TTL", "PT3M",
                                "PAYMENT_WINDOW", "PT2M",
                                "MAX_SEATS", "4",
                                "SWEEP_INTERVAL", "PT5S",
                                "SSE_MAX_STREAMS", "7",
                                "MOCK_GATEWAY", "false",
                                "METRICS_TOKEN", "m",
                                "TRUST_PROXY", "10.0.0.0/8, ::1",
                                "STATIC_DIR", "/app/static",
                                "VIEWS_DIR", "/app/views",
                                "JOLT_DEV", "1"));
        assertEquals(Duration.ofMinutes(3), c.policy().holdTtl());
        assertEquals(Duration.ofMinutes(2), c.policy().paymentWindow());
        assertEquals(4, c.policy().maxSeats());
        assertEquals(Duration.ofSeconds(5), c.sweepInterval());
        assertEquals(7, c.sseMaxStreams());
        assertFalse(c.mockGateway());
        assertEquals("m", c.metricsToken());
        assertEquals(List.of("10.0.0.0/8", "::1"), c.trustProxy());
        assertEquals(Path.of("/app/static"), c.staticDir());
        assertEquals(Path.of("/app/views"), c.viewsDir());
        assertTrue(c.joltDev());
        // Jolt's Config also reads the short forms; anything else still fails naming the key.
        assertEquals(
                Duration.ofMinutes(7), AppConfig.fromEnv(env("HOLD_TTL", "7m")).policy().holdTtl());
        var badTtl =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AppConfig.fromEnv(env("HOLD_TTL", "seven")));
        assertTrue(badTtl.getMessage().contains("HOLD_TTL"), badTtl.getMessage());
        assertThrows(IllegalArgumentException.class, () -> AppConfig.fromEnv(env("PORT", "abc")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppConfig.fromEnv(env("SWEEP_INTERVAL", "PT0S")));
        assertThrows(
                IllegalArgumentException.class, () -> AppConfig.fromEnv(env("MAX_SEATS", "0")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppConfig.fromEnv(env("MOCK_GATEWAY", "yes")));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.fromEnv(env("PORT", "70000")));
    }

    @Test
    void propertiesFilesSupplyValuesAndTheEnvironmentWins(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("application.properties"),
                String.join("\n", "MAX_SEATS=6", "SSE_MAX_STREAMS=50", "JWT_SECRET=" + JWT));
        AppConfig c =
                AppConfig.from(Config.load(env("SSE_MAX_STREAMS", "9", "JWT_SECRET", null), dir));
        assertEquals(6, c.policy().maxSeats());
        assertEquals(9, c.sseMaxStreams());
        assertEquals(JWT, c.jwtSecret());
    }

    @Test
    void sqliteFileIsTakenFromTheJdbcUrl() {
        assertEquals(Path.of("data/bms.db"), AppConfig.fromEnv(env()).sqliteFile());
        assertEquals(
                Path.of("/data/bms.db"),
                AppConfig.fromEnv(env("DB_URL", "jdbc:sqlite:/data/bms.db?foo=1")).sqliteFile());
        assertThrows(
                IllegalArgumentException.class,
                () -> AppConfig.fromEnv(env("DB_URL", "jdbc:sqlite::memory:")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppConfig.fromEnv(env("DB_URL", "jdbc:postgresql://x/y")));
    }
}
