package dev.thiru.bms.features.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.SliceKit;
import in.jolt.test.JoltTest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The ordering guarantee of SYSTEM_DESIGN 7: the route subscribes before it reads the snapshot, so
 * an event published in between is not lost. The database handed to the route publishes an event
 * the moment the snapshot read begins, which is exactly the window between subscription and
 * snapshot, and records whether a subscription already existed then.
 */
class SubscribeBeforeSnapshotTest {

    private final AtomicInteger subscribersWhenSnapshotWasRead = new AtomicInteger(-1);
    private final AtomicBoolean raced = new AtomicBoolean();

    private SliceKit boot() {
        return SliceKit.boot(
                k ->
                        List.of(
                                new LiveRoutes(
                                        racing(k),
                                        k.live(),
                                        k.clock(),
                                        k.policy(),
                                        Duration.ofMillis(100),
                                        k.pipeline().mapper())));
    }

    /** Passes the first call (the show lookup); on the second (the snapshot) a seat event lands. */
    private Db racing(SliceKit k) {
        AtomicInteger calls = new AtomicInteger();
        Db real = k.db();
        return new Db() {
            @Override
            public <T> T inTransaction(Function<Tx, T> work) {
                if (calls.incrementAndGet() == 2) {
                    subscribersWhenSnapshotWasRead.set(k.live().openStreams());
                    k.live().push(1, "77", "seat-held", "{\"showId\":1,\"seats\":[\"C7\"]}");
                    raced.set(true);
                }
                return real.inTransaction(work);
            }
        };
    }

    @Test
    @Timeout(20)
    void anEventPublishedBetweenSubscriptionAndSnapshotIsStillDelivered() throws Exception {
        try (SliceKit kit = boot()) {
            Thread ender =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        // End the stream once the raced event has been forwarded.
                                        while (!raced.get()
                                                || !kit.live().subs.get(0).queue.isEmpty()) {
                                            Thread.onSpinWait();
                                        }
                                        kit.live().endAll();
                                    });
            JoltTest.Result res =
                    kit.http()
                            .get("/api/shows/1/events")
                            .timeout(Duration.ofSeconds(10))
                            .expect(200);
            ender.join();
            assertEquals(
                    1,
                    subscribersWhenSnapshotWasRead.get(),
                    "the subscription existed before the snapshot was read");
            List<JoltTest.Event> events = res.events();
            assertEquals("snapshot", events.get(0).name());
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    e ->
                                            e.name().equals("seat-held")
                                                    && "77".equals(e.id())
                                                    && e.data().contains("C7")),
                    "the raced event reaches the client after the snapshot: " + events);
            assertFalse(events.get(0).data().isEmpty());
        }
    }
}
