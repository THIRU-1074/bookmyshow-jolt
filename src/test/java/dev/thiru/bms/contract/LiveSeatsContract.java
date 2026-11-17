package dev.thiru.bms.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.domain.SeatId;
import dev.thiru.bms.domain.port.LiveSeats;
import dev.thiru.bms.domain.port.LiveSeats.Message;
import dev.thiru.bms.domain.port.LiveSeats.Subscription;
import dev.thiru.bms.domain.port.SeatEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The SSE side of the live seat stream as LiveModule and the admin stats use it, including the SPEC
 * 7 event names and payloads and the increasing event ids the {@code id:} lines carry. Subclasses
 * build a store capped at {@link #CAP} streams and say how a domain event reaches it.
 */
public abstract class LiveSeatsContract {

    protected static final int CAP = 2;
    protected static final Duration WAIT = Duration.ofSeconds(2);
    protected static final Duration SHORT = Duration.ofMillis(50);

    private static final List<SeatId> SEATS = List.of(SeatId.parse("C7"), SeatId.parse("C8"));

    protected abstract LiveSeats newLive();

    /** Delivers {@code event} the way production does: through the publisher, or a fake push. */
    protected abstract void emit(LiveSeats live, SeatEvent event);

    @Test
    protected void subscribersOfAShowReceiveItsEventsAsSpecMessages() throws Exception {
        LiveSeats live = newLive();
        try (Subscription sub = live.subscribe(7).orElseThrow()) {
            emit(live, new SeatEvent.Held(7, SEATS, Instant.parse("2026-11-20T13:07:00Z")));
            Message held = sub.poll(WAIT);
            assertEquals("seat-held", held.name());
            assertEquals(
                    "{\"showId\":7,\"seats\":[\"C7\",\"C8\"],\"until\":\"2026-11-20T13:07:00Z\"}",
                    held.data());
            emit(live, new SeatEvent.Released(7, SEATS, SeatEvent.Reason.EXPIRED));
            Message released = sub.poll(WAIT);
            assertEquals("seat-released", released.name());
            assertEquals(
                    "{\"showId\":7,\"seats\":[\"C7\",\"C8\"],\"reason\":\"EXPIRED\"}",
                    released.data());
            emit(live, new SeatEvent.Booked(7, SEATS));
            Message booked = sub.poll(WAIT);
            assertEquals("seat-booked", booked.name());
            assertEquals("{\"showId\":7,\"seats\":[\"C7\",\"C8\"]}", booked.data());
        }
    }

    @Test
    protected void everyMessageCarriesAnEventIdThatIncreasesInPublishOrder() throws Exception {
        LiveSeats live = newLive();
        try (Subscription sub = live.subscribe(7).orElseThrow()) {
            long last = Long.MIN_VALUE;
            for (int i = 0; i < 3; i++) {
                emit(live, new SeatEvent.Booked(7, SEATS));
                Message m = sub.poll(WAIT);
                assertNotNull(m.id(), "SSE id: line");
                long id = Long.parseLong(m.id());
                assertTrue(id > last, id + " after " + last);
                last = id;
            }
        }
    }

    @Test
    protected void anotherShowsEventsDoNotArriveAndPollTimesOutWithNull() throws Exception {
        LiveSeats live = newLive();
        try (Subscription sub = live.subscribe(7).orElseThrow()) {
            emit(live, new SeatEvent.Booked(8, SEATS));
            assertNull(sub.poll(SHORT));
            assertTrue(sub.isOpen());
        }
    }

    @Test
    protected void theStreamCapRefusesExtraSubscribersUntilOneCloses() {
        LiveSeats live = newLive();
        Subscription a = live.subscribe(1).orElseThrow();
        Subscription b = live.subscribe(2).orElseThrow();
        assertEquals(2, live.openStreams());
        assertEquals(Optional.empty(), live.subscribe(3));
        a.close();
        assertFalse(a.isOpen());
        assertEquals(1, live.openStreams());
        Optional<Subscription> c = live.subscribe(3);
        assertTrue(c.isPresent());
        b.close();
        c.get().close();
        assertEquals(0, live.openStreams());
    }

    @Test
    protected void closingTwiceIsHarmless() {
        LiveSeats live = newLive();
        Subscription a = live.subscribe(1).orElseThrow();
        a.close();
        a.close();
        assertEquals(0, live.openStreams());
    }
}
