package dev.thiru.bms.adapters.live;

import dev.thiru.bms.domain.SeatId;
import dev.thiru.bms.domain.port.LiveSeats;
import dev.thiru.bms.domain.port.SeatEvent;
import dev.thiru.bms.domain.port.SeatEventPublisher;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Both ends of the live seat stream on one {@link SeatEvents} hub: use cases publish domain {@link
 * SeatEvent}s after commit, and the SSE route reads them back as the {@code seat-held}, {@code
 * seat-released} and {@code seat-booked} messages of SPEC 7. Heartbeats are the route's job.
 */
public final class LiveSeatHub implements LiveSeats, SeatEventPublisher {

    private final SeatEvents hub;

    public LiveSeatHub(SeatEvents hub) {
        this.hub = hub;
    }

    @Override
    public void publish(SeatEvent event) {
        hub.publish(toHubEvent(event));
    }

    static SeatEvents.Event toHubEvent(SeatEvent event) {
        return switch (event) {
            case SeatEvent.Held h ->
                    new SeatEvents.Event(
                            SeatEvents.Type.SEAT_HELD,
                            h.showId(),
                            prefix(h.showId(), h.seats()) + ",\"until\":\"" + h.until() + "\"}");
            case SeatEvent.Released r ->
                    new SeatEvents.Event(
                            SeatEvents.Type.SEAT_RELEASED,
                            r.showId(),
                            prefix(r.showId(), r.seats())
                                    + ",\"reason\":\""
                                    + r.reason().name()
                                    + "\"}");
            case SeatEvent.Booked b ->
                    new SeatEvents.Event(
                            SeatEvents.Type.SEAT_BOOKED,
                            b.showId(),
                            prefix(b.showId(), b.seats()) + "}");
        };
    }

    // Seat ids are [A-Z][0-9]{1,2} by construction, so they need no JSON escaping.
    private static String prefix(long showId, List<SeatId> seats) {
        StringBuilder sb = new StringBuilder("{\"showId\":").append(showId).append(",\"seats\":[");
        for (int i = 0; i < seats.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(seats.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    @Override
    public Optional<Subscription> subscribe(long showId) {
        return hub.subscribe(showId).map(Stream::new);
    }

    @Override
    public int openStreams() {
        return hub.subscriberCount();
    }

    private record Stream(SeatEvents.Subscription sub) implements Subscription {
        @Override
        public Message poll(Duration timeout) throws InterruptedException {
            SeatEvents.Event e = sub.poll(timeout);
            return e == null ? null : new Message(e.type().sseName(), e.data());
        }

        @Override
        public boolean isOpen() {
            return sub.isOpen();
        }

        @Override
        public void close() {
            sub.close();
        }
    }
}
