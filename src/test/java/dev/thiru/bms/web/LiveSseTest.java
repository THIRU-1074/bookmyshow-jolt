package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** SSE through JoltTest's in-memory stream capture. */
class LiveSseTest {

    @Test
    @Timeout(20)
    void streamStartsWithRetryAndSnapshotThenForwardsEventsAndHeartbeats() throws Exception {
        WebKit kit = new WebKit(true, Duration.ofMillis(500));
        // The handler blocks while streaming, so the event arrives from another thread once the
        // route has subscribed.
        Thread pusher =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    while (kit.live.subs.isEmpty()) {
                                        Thread.onSpinWait();
                                    }
                                    kit.live.push(
                                            9,
                                            "41",
                                            "seat-held",
                                            "{\"showId\":9,\"seats\":[\"A2\"]}");
                                });
        JoltTest.Result res =
                kit.http
                        .get("/api/shows/9/events")
                        .maxEvents(3)
                        .timeout(Duration.ofSeconds(10))
                        .expect(200);
        pusher.join();
        assertTrue(res.header("Content-Type").startsWith("text/event-stream"));
        assertTrue(res.text().startsWith("retry: 3000\n"), res.text());
        List<JoltTest.Event> events = res.events();
        assertEquals(3, events.size(), events.toString());
        assertEquals("snapshot", events.get(0).name());
        assertTrue(events.get(0).data().startsWith("{\"showId\":9,\"version\":"));
        assertNull(events.get(0).id());
        assertEquals(
                new JoltTest.Event("seat-held", "{\"showId\":9,\"seats\":[\"A2\"]}", "41"),
                events.get(1));
        assertEquals("heartbeat", events.get(2).name());
        assertTrue(events.get(2).data().startsWith("{\"ts\":"));
        assertNull(events.get(2).id());
        // The fourth write failed as for a browser that left; the route released its stream.
        assertEquals(0, kit.live.openStreams());
    }

    @Test
    @Timeout(20)
    void aClosedSubscriptionEndsTheStream() {
        WebKit kit = new WebKit(true, Duration.ofMillis(100));
        Thread.ofVirtual()
                .start(
                        () -> {
                            while (kit.live.subs.isEmpty()) {
                                Thread.onSpinWait();
                            }
                            kit.live.endAll();
                        });
        JoltTest.Result res =
                kit.http.get("/api/shows/9/events").timeout(Duration.ofSeconds(10)).expect(200);
        assertEquals("snapshot", res.events().get(0).name());
        assertNull(res.streamError());
        assertEquals(0, kit.live.openStreams());
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
