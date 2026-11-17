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

    /** The React build needs Node; tests serve this small stand-in so Maven runs without it. */
    static final String STATIC_FIXTURE = "src/test/resources/static-fixture";

    @TempDir Path dir;
    private Main.Booted booted;

    private AppConfig config(String... kv) {
        Map<String, String> env = new HashMap<>();
        env.put("JWT_SECRET", AppConfigTest.JWT);
        env.put("WEBHOOK_SECRET", AppConfigTest.HOOK);
        env.put("DB_URL", "jdbc:sqlite:" + dir.resolve("nested/data/bms.db"));
        env.put("STATIC_DIR", STATIC_FIXTURE);
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
                .header("Content-Type", "application/problem+json; charset=utf-8")
                .jsonEquals("status", 404);
    }

    @Test
    void rootAndClientRoutesServeIndexHtml() {
        JoltTest http = boot();
        http.get("/").expect(200).bodyContains("<title>Houselights</title>");
        http.get("/movies/1")
                .header("Accept", "text/html")
                .expect(200)
                .bodyContains("<title>Houselights</title>");
        http.get("/css/app.css").expect(200);
    }

    @Test
    void hashedAssetsAreImmutableAndTheShellIsRevalidated() {
        JoltTest http = boot();
        http.get("/assets/index-test.js")
                .expect(200)
                .header("Cache-Control", "public, max-age=31536000, immutable");
        http.get("/").expect(200).header("Cache-Control", "no-cache");
        http.get("/owner")
                .header("Accept", "text/html")
                .expect(200)
                .header("Cache-Control", "no-cache");
        http.get("/css/app.css").expect(200).header("Cache-Control", "no-cache");
    }

    @Test
    void apiPathsNeverFallBackToTheSpaEvenForBrowsers() {
        boot().get("/api/nope")
                .header("Accept", "text/html")
                .expect(404)
                .header("Content-Type", "application/problem+json; charset=utf-8");
    }

    @Test
    void seededCatalogIsServed() {
        boot().get("/api/cities").expect(200).bodyContains("bengaluru");
    }

    @Test
    void metricsNeedTheBearerToken() {
        JoltTest http = boot("METRICS_TOKEN", "metrics-token");
        http.get("/_jolt/metrics").expect(401);
        http.get("/_jolt/metrics")
                .header("Authorization", "Bearer metrics-token")
                .expect(200)
                .bodyContains("bms_sse_subscribers 0");
        http.get("/_jolt/health").expect(200).jsonEquals("checks.db", "UP");
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
    void theSweepRunsOnJoltsSchedulerOnceListeningAndStopsOnClose() throws Exception {
        JoltTest http = boot("SWEEP_INTERVAL", "PT0.1S", "METRICS_TOKEN", "metrics-token");
        booted.listen(0);
        String series = "jolt_task_runs_total{task=\"expiry-sweep\"}";
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String metrics = "";
        while (System.nanoTime() < deadline && !ranOnce(metrics, series)) {
            Thread.sleep(50);
            metrics =
                    http.get("/_jolt/metrics")
                            .header("Authorization", "Bearer metrics-token")
                            .expect(200)
                            .text();
        }
        assertTrue(ranOnce(metrics, series), metrics);
        booted.close();
        // The pool is closed by the stop hook after the sweeper stopped.
        http.get("/api/health").expect(503).jsonEquals("db", "DOWN");
    }

    private static boolean ranOnce(String metrics, String series) {
        return metrics.lines().anyMatch(l -> l.startsWith(series + " ") && !l.endsWith(" 0"));
    }

    @Test
    void closeIsIdempotent() {
        boot();
        booted.close();
        booted.close();
    }
}
