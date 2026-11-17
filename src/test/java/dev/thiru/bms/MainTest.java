package dev.thiru.bms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.JoltConfig;
import in.jolt.test.JoltTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir Path dir;
    private Main.Booted booted;

    private AppConfig config(String... kv) {
        Map<String, String> env = new HashMap<>();
        env.put("JWT_SECRET", AppConfigTest.JWT);
        env.put("WEBHOOK_SECRET", AppConfigTest.HOOK);
        env.put("DB_URL", "jdbc:sqlite:" + dir.resolve("nested/data/bms.db"));
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        return AppConfig.fromEnv(env);
    }

    private JoltTest boot(String... kv) {
        AppConfig c = config(kv);
        booted = Main.app(c, JoltConfig.defaults());
        return JoltTest.of(booted.app());
    }

    @AfterEach
    void close() {
        if (booted != null) {
            booted.close();
        }
    }

    @Test
    void healthChecksTheDatabaseAndTheDbDirectoryIsCreated() {
        boot().get("/api/health").expect(200).jsonEquals("status", "UP").jsonEquals("db", "UP");
        assertTrue(Files.isRegularFile(dir.resolve("nested/data/bms.db")));
    }

    @Test
    void unknownRouteIsProblemJson() {
        boot().get("/api/nope")
                .expect(404)
                .header("Content-Type", "application/problem+json")
                .jsonEquals("status", 404);
    }

    @Test
    void rootAndClientRoutesServeIndexHtml() {
        JoltTest http = boot();
        http.get("/").expect(200).bodyContains("<title>BookMyShow on Jolt</title>");
        http.get("/movies/1")
                .header("Accept", "text/html")
                .expect(200)
                .bodyContains("<title>BookMyShow on Jolt</title>");
        http.get("/css/app.css").expect(200);
    }

    @Test
    void apiPathsNeverFallBackToTheSpaEvenForBrowsers() {
        boot().get("/api/nope")
                .header("Accept", "text/html")
                .expect(404)
                .header("Content-Type", "application/problem+json");
    }

    @Test
    void seededCatalogIsServed() {
        boot().get("/api/cities").expect(200).bodyContains("bengaluru");
    }

    @Test
    void metricsNeedTheBearerToken() {
        JoltTest http = boot("METRICS_TOKEN", "metrics-token");
        http.get("/_jolt/metrics").expect(401);
        http.get("/_jolt/metrics").header("Authorization", "Bearer metrics-token").expect(200);
    }

    @Test
    void metricsAreHiddenWithoutAToken() {
        boot().get("/_jolt/metrics").expect(404);
    }

    @Test
    void missingStaticOrViewsDirectoryFailsFast() {
        AppConfig noStatic = config("STATIC_DIR", dir.resolve("missing").toString());
        var e =
                assertThrows(
                        IllegalStateException.class,
                        () -> Main.app(noStatic, JoltConfig.defaults()));
        assertTrue(e.getMessage().contains("STATIC_DIR"));
        AppConfig noViews = config("VIEWS_DIR", dir.resolve("missing").toString());
        assertThrows(IllegalStateException.class, () -> Main.app(noViews, JoltConfig.defaults()));
    }

    @Test
    void streamsAreNotCutByTheDefaultMaxDuration() {
        JoltConfig c = Main.joltConfig(config(), JoltConfig.defaults());
        assertEquals(Duration.ZERO, c.streamMaxDuration());
        assertEquals(Main.STREAM_IDLE, c.streamIdleTimeout());
        assertTrue(c.problemJson());
        assertEquals(
                java.util.List.of("10.0.0.0/8"),
                Main.joltConfig(config("TRUST_PROXY", "10.0.0.0/8"), JoltConfig.defaults())
                        .trustedProxies());
    }

    @Test
    void closeIsIdempotent() {
        boot();
        booted.close();
        booted.close();
    }
}
