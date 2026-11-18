package dev.thiru.bms.features.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.testing.SliceKit;
import in.jolt.test.JoltTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** SSE through JoltTest's in-memory stream capture, on the V2 seed (show 1 starts at 10:00). */
class LiveRoutesTest {

    private static SliceKit boot(Duration heartbeat) {
        return SliceKit.boot(
                k ->
                        List.of(
                                new LiveRoutes(
                                        k.db(),
                                        k.live(),
                                        k.clock(),
                                        k.policy(),
                                        heartbeat,
                                        k.pipeline().mapper())));
    }

    /** Runs {@code action} on another thread once the route has subscribed. */
    private static Thread onceSubscribed(SliceKit kit, Runnable action) {
        return Thread.ofVirtual()
                .start(
                        () -> {
                            while (kit.live().subs.isEmpty()) {
                                Thread.onSpinWait();
                            }
                            action.run();
                        });
    }

    @Test
    @Timeout(20)
    void streamStartsWithRetryAndSnapshotThenForwardsEventsAndHeartbeats() throws Exception {
        try (SliceKit kit = boot(Duration.ofMillis(500))) {
            Thread pusher =
                    onceSubscribed(
                            kit,
                            () ->
                                    kit.live()
                                            .push(
                                                    1,
                                                    "41",
                                                    "seat-held",
                                                    "{\"showId\":1,\"seats\":[\"A2\"]}"));
            JoltTest.Result res =
                    kit.http()
                            .get("/api/shows/1/events")
                            .maxEvents(3)
                            .timeout(Duration.ofSeconds(10))
                            .expect(200);
            pusher.join();
            assertTrue(res.header("Content-Type").startsWith("text/event-stream"));
            assertTrue(res.text().startsWith("retry: 3000\n"), res.text());
            List<JoltTest.Event> events = res.events();
            assertEquals(3, events.size(), events.toString());
            assertEquals("snapshot", events.get(0).name());
            assertTrue(events.get(0).data().startsWith("{\"showId\":1,\"version\":"));
            assertTrue(
                    events.get(0)
                            .data()
                            .contains(
                                    "\"categories\":[{\"category\":\"CLASSIC\",\"name\":\"Classic\","
                                            + "\"amount\":23000}"),
                    events.get(0).data());
            assertTrue(
                    events.get(0)
                            .data()
                            .contains(
                                    "{\"row\":\"A\",\"seats\":[{\"id\":\"A1\",\"number\":1,"
                                            + "\"category\":\"CLASSIC\",\"status\":\"BLOCKED\"}"),
                    events.get(0).data());
            assertTrue(events.get(0).data().endsWith(",\"holdTtlSeconds\":420}"));
            assertNull(events.get(0).id());
            assertEquals(
                    new JoltTest.Event("seat-held", "{\"showId\":1,\"seats\":[\"A2\"]}", "41"),
                    events.get(1));
            assertEquals("heartbeat", events.get(2).name());
            assertEquals("{\"ts\":\"" + kit.clock().instant() + "\"}", events.get(2).data());
            assertNull(events.get(2).id());
            // The fourth write failed as for a browser that left; the route released its stream.
            assertEquals(0, kit.live().openStreams());
        }
    }

    @Test
    @Timeout(20)
    void theSnapshotShowsHeldSeats() {
        try (SliceKit kit = boot(Duration.ofMillis(100))) {
            LiveSqlTest.hold(kit.db(), 1, kit.clock().instant().plusSeconds(60), "A2");
            onceSubscribed(kit, () -> kit.live().endAll());
            String snapshot =
                    kit.http()
                            .get("/api/shows/1/events")
                            .timeout(Duration.ofSeconds(10))
                            .expect(200)
                            .events()
                            .get(0)
                            .data();
            assertTrue(
                    snapshot.contains(
                            "{\"id\":\"A2\",\"number\":2,\"category\":\"CLASSIC\",\"status\":\"HELD\"}"),
                    snapshot);
        }
    }

    @Test
    @Timeout(20)
    void aClosedSubscriptionEndsTheStream() {
        try (SliceKit kit = boot(Duration.ofMillis(100))) {
            onceSubscribed(kit, () -> kit.live().endAll());
            JoltTest.Result res =
                    kit.http()
                            .get("/api/shows/1/events")
                            .timeout(Duration.ofSeconds(10))
                            .expect(200);
            assertEquals("snapshot", res.events().get(0).name());
            assertNull(res.streamError());
            assertEquals(0, kit.live().openStreams());
        }
    }

    @Test
    @Timeout(20)
    void aStartedShowGetsOnlyTheSnapshot() {
        try (SliceKit kit = boot(Duration.ofMillis(100))) {
            kit.mutableClock().set(showStart(kit));
            JoltTest.Result res =
                    kit.http()
                            .get("/api/shows/1/events")
                            .timeout(Duration.ofSeconds(10))
                            .expect(200);
            assertEquals(
                    List.of("snapshot"), res.events().stream().map(JoltTest.Event::name).toList());
            assertNull(res.streamError());
            assertEquals(0, kit.live().openStreams());
        }
    }

    @Test
    @Timeout(20)
    void theStreamEndsWhenTheShowStarts() {
        try (SliceKit kit = boot(Duration.ofMillis(50))) {
            Instant start = showStart(kit);
            onceSubscribed(kit, () -> kit.mutableClock().set(start));
            JoltTest.Result res =
                    kit.http()
                            .get("/api/shows/1/events")
                            .timeout(Duration.ofSeconds(10))
                            .expect(200);
            assertEquals("snapshot", res.events().get(0).name());
            assertNull(res.streamError());
            assertEquals(0, kit.live().openStreams());
        }
    }

    @Test
    @Timeout(20)
    void aCancelledShowGetsOnlyTheSnapshot() {
        try (SliceKit kit = boot(Duration.ofMillis(100))) {
            cancel(kit);
            JoltTest.Result res =
                    kit.http()
                            .get("/api/shows/1/events")
                            .timeout(Duration.ofSeconds(10))
                            .expect(200);
            assertEquals(
                    List.of("snapshot"), res.events().stream().map(JoltTest.Event::name).toList());
            assertEquals(0, kit.live().openStreams());
        }
    }

    @Test
    @Timeout(20)
    void cancellingTheShowEndsAnOpenStreamAtTheNextHeartbeat() {
        try (SliceKit kit = boot(Duration.ofMillis(50))) {
            onceSubscribed(kit, () -> cancel(kit));
            JoltTest.Result res =
                    kit.http()
                            .get("/api/shows/1/events")
                            .timeout(Duration.ofSeconds(10))
                            .expect(200);
            assertEquals("snapshot", res.events().get(0).name());
            assertNull(res.streamError());
            assertEquals(0, kit.live().openStreams());
        }
    }

    @Test
    void unknownShowIs404AndAFullHubIs503() {
        try (SliceKit kit = boot(Duration.ofMillis(100))) {
            kit.http().get("/api/shows/404404/events").expect(404).jsonEquals("code", "NOT_FOUND");
            kit.http().get("/api/shows/abc/events").expect(404);
            assertEquals(0, kit.live().subs.size(), "no subscription for a missing show");
            for (int i = 0; i < 5; i++) {
                kit.live().subscribe(1);
            }
            kit.http()
                    .get("/api/shows/1/events")
                    .expect(503)
                    .jsonEquals("code", "UNAVAILABLE")
                    .jsonEquals("detail", "Too many live streams");
        }
    }

    private static Instant showStart(SliceKit kit) {
        return kit.db()
                .inTransaction(
                        tx ->
                                tx.queryOne(
                                                "SELECT starts_at FROM show WHERE id = 1",
                                                rs -> Instant.ofEpochMilli(rs.getLong(1)))
                                        .orElseThrow());
    }

    private static void cancel(SliceKit kit) {
        kit.db()
                .inTransaction(
                        tx -> tx.update("UPDATE show SET status = 'CANCELLED' WHERE id = 1"));
    }
}
