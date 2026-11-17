package dev.thiru.bms.adapters.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.adapters.live.SeatEvents.Event;
import dev.thiru.bms.adapters.live.SeatEvents.Subscription;
import dev.thiru.bms.adapters.live.SeatEvents.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SeatEventsTest {

    private static Event held(long showId, String data) {
        return new Event(Type.SEAT_HELD, showId, data);
    }

    @Test
    void eventTypesCarrySseNames() {
        assertEquals("seat-held", Type.SEAT_HELD.sseName());
        assertEquals("seat-released", Type.SEAT_RELEASED.sseName());
        assertEquals("seat-booked", Type.SEAT_BOOKED.sseName());
        assertEquals("heartbeat", Type.HEARTBEAT.sseName());
    }

    @Test
    void deliversOnlyToSubscribersOfTheShow() throws Exception {
        SeatEvents hub = new SeatEvents(10, 8);
        Subscription a = hub.subscribe(7).orElseThrow();
        Subscription b = hub.subscribe(8).orElseThrow();

        hub.publish(held(7, "x"));

        assertEquals("x", a.poll(Duration.ofSeconds(1)).data());
        assertNull(b.poll(Duration.ofMillis(20)));
    }

    @Test
    void preservesOrderPerSubscriber() throws Exception {
        SeatEvents hub = new SeatEvents(10, 8);
        Subscription a = hub.subscribe(7).orElseThrow();
        for (int i = 0; i < 5; i++) {
            hub.publish(held(7, "e" + i));
        }
        for (int i = 0; i < 5; i++) {
            assertEquals("e" + i, a.poll(Duration.ofSeconds(1)).data());
        }
    }

    @Test
    void capsSubscribersAndFreesSlotOnClose() {
        SeatEvents hub = new SeatEvents(2, 8);
        Subscription a = hub.subscribe(1).orElseThrow();
        hub.subscribe(2).orElseThrow();
        assertEquals(Optional.empty(), hub.subscribe(1));
        assertEquals(2, hub.subscriberCount());

        a.close();
        a.close();
        assertEquals(1, hub.subscriberCount());
        assertTrue(hub.subscribe(3).isPresent());
    }

    @Test
    void dropsSlowSubscriberWhenQueueIsFull() throws Exception {
        SeatEvents hub = new SeatEvents(10, 2);
        Subscription slow = hub.subscribe(7).orElseThrow();
        Subscription fast = hub.subscribe(7).orElseThrow();

        hub.publish(held(7, "1"));
        hub.publish(held(7, "2"));
        assertEquals("1", fast.poll(Duration.ofSeconds(1)).data());
        assertEquals("2", fast.poll(Duration.ofSeconds(1)).data());
        hub.publish(held(7, "3"));

        assertFalse(slow.isOpen());
        assertTrue(fast.isOpen());
        assertEquals(1, hub.subscriberCount());
        assertEquals("3", fast.poll(Duration.ofSeconds(1)).data());
        // A dropped subscriber drains what it has and then sees the end of stream.
        assertEquals("1", slow.poll(Duration.ofSeconds(1)).data());
        assertEquals("2", slow.poll(Duration.ofSeconds(1)).data());
        assertNull(slow.poll(Duration.ofMillis(20)));
    }

    @Test
    void closeWakesBlockedPoller() throws Exception {
        SeatEvents hub = new SeatEvents(10, 2);
        Subscription a = hub.subscribe(7).orElseThrow();
        Thread t =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        assertNull(a.poll(Duration.ofSeconds(10)));
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
        Thread.sleep(50);
        a.close();
        t.join(2000);
        assertFalse(t.isAlive());
    }

    @Test
    void heartbeatGoesToEverySubscriber() throws Exception {
        SeatEvents hub = new SeatEvents(10, 8);
        Subscription a = hub.subscribe(1).orElseThrow();
        Subscription b = hub.subscribe(2).orElseThrow();
        hub.heartbeat("{\"ts\":\"t\"}");
        assertEquals(Type.HEARTBEAT, a.poll(Duration.ofSeconds(1)).type());
        assertEquals(Type.HEARTBEAT, b.poll(Duration.ofSeconds(1)).type());
    }

    @Test
    void closeShowEndsStreamsOfThatShowOnly() throws Exception {
        SeatEvents hub = new SeatEvents(10, 8);
        Subscription a = hub.subscribe(1).orElseThrow();
        Subscription b = hub.subscribe(2).orElseThrow();
        hub.closeShow(1);
        assertFalse(a.isOpen());
        assertTrue(b.isOpen());
        assertEquals(1, hub.subscriberCount());
    }

    @Test
    void rejectsBadConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new SeatEvents(0, 8));
        assertThrows(IllegalArgumentException.class, () -> new SeatEvents(1, 0));
    }

    @Test
    void concurrentPublishAndChurnNeverExceedsCapOrLosesEvents() throws Exception {
        int cap = 50;
        SeatEvents hub = new SeatEvents(cap, 10_000);
        int publishers = 8;
        int perPublisher = 500;
        int stable = 20;

        List<Subscription> stableSubs = new ArrayList<>();
        for (int i = 0; i < stable; i++) {
            stableSubs.add(hub.subscribe(7).orElseThrow());
        }

        AtomicInteger overCap = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int p = 0; p < publishers; p++) {
                vt.submit(
                        () -> {
                            start.await();
                            for (int i = 0; i < perPublisher; i++) {
                                hub.publish(held(7, "m"));
                            }
                            return null;
                        });
            }
            // Churners try to exceed the cap while events flow.
            for (int c = 0; c < 200; c++) {
                vt.submit(
                        () -> {
                            start.await();
                            hub.subscribe(7)
                                    .ifPresent(
                                            s -> {
                                                if (hub.subscriberCount() > cap) {
                                                    overCap.incrementAndGet();
                                                }
                                                s.close();
                                            });
                            return null;
                        });
            }
            start.countDown();
            vt.shutdown();
            assertTrue(vt.awaitTermination(30, TimeUnit.SECONDS));
        }

        assertEquals(0, overCap.get());
        assertEquals(stable, hub.subscriberCount());
        for (Subscription s : stableSubs) {
            int got = 0;
            while (s.poll(Duration.ofMillis(10)) != null) {
                got++;
            }
            assertEquals(publishers * perPublisher, got);
        }
    }
}
