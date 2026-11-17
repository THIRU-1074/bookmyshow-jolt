package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.Server;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * SSE over a real loopback socket.
 *
 * <p>TODO(jolt#69): move to {@code JoltTest} once it can read streaming responses.
 */
class LiveSseTest {

    @Test
    @Timeout(20)
    void streamStartsWithRetryAndSnapshotThenForwardsEventsAndHeartbeats() throws Exception {
        WebKit kit = new WebKit(true, Duration.ofMillis(100));
        try (Server server = kit.app.listen(0);
                HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest req =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + server.port()
                                                    + "/api/shows/9/events"))
                            .timeout(Duration.ofSeconds(10))
                            .build();
            HttpResponse<InputStream> res =
                    client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, res.statusCode());
            assertTrue(
                    res.headers()
                            .firstValue("Content-Type")
                            .orElse("")
                            .startsWith("text/event-stream"));
            List<String> seen = new ArrayList<>();
            try (BufferedReader in =
                    new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
                String line;
                boolean pushed = false;
                while ((line = in.readLine()) != null) {
                    seen.add(line);
                    if (!pushed && line.equals("event: snapshot")) {
                        kit.live.push(9, "seat-held", "{\"showId\":9,\"seats\":[\"A2\"]}");
                        pushed = true;
                    }
                    if (line.equals("event: heartbeat") && seen.contains("event: seat-held")) {
                        kit.live.endAll();
                    }
                }
            }
            assertEquals("retry: 3000", seen.get(0));
            assertTrue(seen.contains("event: snapshot"), seen.toString());
            assertTrue(
                    seen.stream().anyMatch(l -> l.startsWith("data: {\"showId\":9,\"version\":")),
                    seen.toString());
            assertTrue(seen.contains("data: {\"showId\":9,\"seats\":[\"A2\"]}"), seen.toString());
            assertTrue(
                    seen.stream().anyMatch(l -> l.startsWith("data: {\"ts\":")), seen.toString());
            assertEquals(0, kit.live.openStreams());
        }
    }

    @Test
    void unknownShowIs404AndAFullHubIs503() {
        WebKit kit = new WebKit(true, Duration.ofMillis(100));
        kit.http.get("/api/shows/404/events").expect(404).jsonEquals("code", "NOT_FOUND");
        for (int i = 0; i < 5; i++) {
            kit.live.subscribe(9);
        }
        kit.http.get("/api/shows/9/events").expect(503).jsonEquals("code", "UNAVAILABLE");
    }
}
