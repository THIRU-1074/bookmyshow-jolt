package dev.thiru.bms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.JoltConfig;
import in.jolt.Server;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * SYSTEM_DESIGN 16 end to end: a booted app with a live SSE connection held open closes promptly,
 * and the client's stream ends. The two halves (the hub ends streams, {@code close} runs in that
 * order) are tested apart elsewhere; this is the two together on a real socket.
 */
class ShutdownWithLiveStreamTest {

    @TempDir Path dir;

    /** The latest scheduled show: its stream stays open however late the test runs. */
    private long lastShow() throws Exception {
        try (java.sql.Connection c =
                        java.sql.DriverManager.getConnection(
                                "jdbc:sqlite:" + dir.resolve("bms.db"));
                java.sql.ResultSet rs =
                        c.createStatement()
                                .executeQuery(
                                        "SELECT id FROM show WHERE status = 'SCHEDULED'"
                                                + " ORDER BY starts_at DESC LIMIT 1")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    @Test
    @Timeout(60)
    void closingTheAppWithAnOpenStreamReturnsPromptlyAndEndsTheStream() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("JWT_SECRET", AppConfigTest.JWT);
        env.put("WEBHOOK_SECRET", AppConfigTest.HOOK);
        env.put("DB_URL", "jdbc:sqlite:" + dir.resolve("bms.db"));
        env.put("STATIC_DIR", MainTest.STATIC_FIXTURE);
        Main.Booted booted = Main.app(AppConfig.fromEnv(env), JoltConfig.defaults());
        try {
            Server server = booted.listen(0);
            long show = lastShow();
            HttpResponse<java.io.InputStream> res =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:"
                                                                    + server.port()
                                                                    + "/api/shows/"
                                                                    + show
                                                                    + "/events"))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, res.statusCode());

            CountDownLatch snapshot = new CountDownLatch(1);
            CountDownLatch ended = new CountDownLatch(1);
            AtomicBoolean sawSnapshot = new AtomicBoolean();
            Thread reader =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try (BufferedReader in =
                                                new BufferedReader(
                                                        new InputStreamReader(
                                                                res.body(),
                                                                StandardCharsets.UTF_8))) {
                                            String line;
                                            while ((line = in.readLine()) != null) {
                                                if (line.startsWith("event: snapshot")) {
                                                    sawSnapshot.set(true);
                                                    snapshot.countDown();
                                                }
                                            }
                                        } catch (java.io.IOException closed) {
                                            // A reset also ends the stream.
                                        } finally {
                                            ended.countDown();
                                        }
                                    });
            assertTrue(snapshot.await(10, TimeUnit.SECONDS), "the stream opened with a snapshot");
            Thread.sleep(500);
            assertFalse(ended.await(0, TimeUnit.SECONDS), "the stream is held open by the server");

            long start = System.nanoTime();
            booted.close();
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            assertTrue(took.compareTo(Duration.ofSeconds(10)) < 0, "close took " + took);
            assertTrue(ended.await(5, TimeUnit.SECONDS), "the client's stream ended");
            assertTrue(sawSnapshot.get());
            reader.join(5000);
        } finally {
            booted.close();
        }
    }
}
