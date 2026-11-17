package dev.thiru.bms.adapters.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.ports.LiveSeats;
import dev.thiru.bms.ports.SeatEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LiveSeatHubTest {

    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final Duration SHORT = Duration.ofMillis(200);
    private static final List<SeatId> SEATS = List.of(SeatId.parse("C7"), SeatId.parse("C8"));

    private final LiveSeatHub hub = new LiveSeatHub(2, 8);

    @Test
    void heldEventsBecomeSeatHeldMessages() throws Exception {
        try (LiveSeats.Subscription sub = hub.subscribe(7).orElseThrow()) {
            hub.publish(new SeatEvent.Held(7, SEATS, Instant.parse("2026-11-20T13:07:00Z")));
            LiveSeats.Message m = sub.poll(WAIT);
            assertEquals("seat-held", m.name());
            assertEquals(
                    "{\"showId\":7,\"seats\":[\"C7\",\"C8\"],"
                            + "\"until\":\"2026-11-20T13:07:00Z\"}",
                    m.data());
        }
    }

    @Test
    void releasedAndBookedEventsCarryTheirReasonAndSeatsInOrder() throws Exception {
        try (LiveSeats.Subscription sub = hub.subscribe(7).orElseThrow()) {
            hub.publish(
                    new SeatEvent.Released(
                            7, List.of(SeatId.parse("C7")), SeatEvent.Reason.EXPIRED));
            hub.publish(new SeatEvent.Booked(7, SEATS));
            LiveSeats.Message released = sub.poll(WAIT);
            LiveSeats.Message booked = sub.poll(WAIT);
            assertEquals("seat-released", released.name());
            assertEquals(
                    "{\"showId\":7,\"seats\":[\"C7\"],\"reason\":\"EXPIRED\"}", released.data());
            assertEquals("seat-booked", booked.name());
            assertEquals("{\"showId\":7,\"seats\":[\"C7\",\"C8\"]}", booked.data());
            // Ids come from the hub and increase in publish order.
            assertTrue(Long.parseLong(booked.id()) > Long.parseLong(released.id()));
        }
    }

    @Test
    void subscribersOnlySeeTheirShow() throws Exception {
        try (LiveSeats.Subscription sub = hub.subscribe(7).orElseThrow()) {
            hub.publish(new SeatEvent.Booked(8, SEATS));
            assertNull(sub.poll(SHORT));
        }
    }

    @Test
    void streamCapAndCloseAreReflectedInOpenStreams() {
        LiveSeats.Subscription a = hub.subscribe(7).orElseThrow();
        LiveSeats.Subscription b = hub.subscribe(8).orElseThrow();
        assertEquals(2, hub.openStreams());
        assertTrue(hub.subscribe(9).isEmpty());
        a.close();
        assertFalse(a.isOpen());
        assertEquals(1, hub.openStreams());
        b.close();
        assertEquals(0, hub.openStreams());
        assertTrue(hub.subscribe(9).isPresent());
    }

    @Test
    void publishingWithNoSubscribersIsANoOp() {
        hub.publish(new SeatEvent.Booked(7, SEATS));
        assertEquals(0, hub.openStreams());
    }

    @Test
    void aSubscriberThatNeverReadsIsDroppedWithoutBlockingPublishers() throws Exception {
        LiveSeats.Subscription slow = hub.subscribe(7).orElseThrow();
        long start = System.nanoTime();
        // Queue 8 in the hub plus the hand-off: far more than fits.
        for (int i = 0; i < 200; i++) {
            hub.publish(new SeatEvent.Booked(7, SEATS));
        }
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "publish blocked");
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (slow.isOpen() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(slow.isOpen());
        assertEquals(0, hub.openStreams());
    }

    @Test
    void closeWakesABlockedPoller() throws Exception {
        LiveSeats.Subscription sub = hub.subscribe(7).orElseThrow();
        CompletableFuture<LiveSeats.Message> polled =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return sub.poll(Duration.ofSeconds(30));
                            } catch (InterruptedException e) {
                                throw new IllegalStateException(e);
                            }
                        });
        Thread.sleep(50);
        sub.close();
        assertNull(polled.get(2, TimeUnit.SECONDS));
        assertNull(sub.poll(SHORT));
    }

    @Test
    void closeAllEndsEveryStreamAndFreesTheirSlots() {
        List<LiveSeats.Subscription> subs = new ArrayList<>();
        subs.add(hub.subscribe(1).orElseThrow());
        subs.add(hub.subscribe(2).orElseThrow());
        hub.closeAll();
        subs.forEach(s -> assertFalse(s.isOpen()));
        assertEquals(0, hub.openStreams());
    }
}
