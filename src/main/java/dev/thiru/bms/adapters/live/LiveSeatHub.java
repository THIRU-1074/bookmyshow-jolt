package dev.thiru.bms.adapters.live;

import dev.thiru.bms.domain.SeatId;
import dev.thiru.bms.domain.port.LiveSeats;
import dev.thiru.bms.domain.port.SeatEvent;
import dev.thiru.bms.domain.port.SeatEventPublisher;
import in.jolt.AppMetrics;
import in.jolt.HttpException;
import in.jolt.Hub;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Both ends of the live seat stream on one Jolt {@link Hub}, one topic per show: use cases publish
 * domain {@link SeatEvent}s after commit, and the SSE route reads them back as the {@code
 * seat-held}, {@code seat-released} and {@code seat-booked} messages of SPEC 7. Heartbeats are the
 * route's job.
 *
 * <p>The hub owns fan-out, the cap on open streams (SSE_MAX_STREAMS) and the bounded queue per
 * subscriber; publishing never blocks, and a subscriber that falls {@code queueSize} messages
 * behind is dropped so one stalled browser cannot hold up booking transactions. Each stream hands
 * messages to the route through a small local queue so the {@link LiveSeats} port can stay pull
 * based. Each message carries the hub's event id, which increases in publish order. Replay is off:
 * every connect starts with a full snapshot (SPEC 7), so events from before it add nothing.
 *
 * <p>TODO(jolt#83): serve with {@code hub.sse} and drop the stream tracking once Hub can end a
 * topic's subscribers and send a first event.
 */
public final class LiveSeatHub implements LiveSeats, SeatEventPublisher {

    /** Messages a stream holds for the route; the hub's own queue absorbs bursts behind it. */
    static final int HANDOFF = 16;

    private static final Message END = new Message("", "");

    private final Hub<Message> hub;
    private final Set<Stream> streams = ConcurrentHashMap.newKeySet();

    /**
     * @param maxStreams open SSE streams across all shows; one more is refused
     * @param queueSize messages a subscriber may fall behind before it is dropped
     */
    public LiveSeatHub(int maxStreams, int queueSize) {
        this.hub = Hub.create(queueSize, maxStreams, 0);
    }

    static String topic(long showId) {
        return "show:" + showId;
    }

    @Override
    public void publish(SeatEvent event) {
        hub.publish(topic(event.showId()), toMessage(event));
    }

    static Message toMessage(SeatEvent event) {
        return switch (event) {
            case SeatEvent.Held h ->
                    new Message(
                            "seat-held",
                            prefix(h.showId(), h.seats()) + ",\"until\":\"" + h.until() + "\"}");
            case SeatEvent.Released r ->
                    new Message(
                            "seat-released",
                            prefix(r.showId(), r.seats())
                                    + ",\"reason\":\""
                                    + r.reason().name()
                                    + "\"}");
            case SeatEvent.Booked b ->
                    new Message("seat-booked", prefix(b.showId(), b.seats()) + "}");
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
        Stream s = new Stream();
        try {
            s.sub = hub.subscribe(topic(showId), null, s::deliver);
        } catch (HttpException full) {
            // The hub answers 503 over its cap; the route turns empty into its own 503.
            return Optional.empty();
        }
        streams.add(s);
        return Optional.of(s);
    }

    @Override
    public int openStreams() {
        return hub.subscribers();
    }

    /** Adds {@code bms_sse_subscribers} and {@code bms_sse_dropped_total} to {@code metrics}. */
    public void metrics(AppMetrics metrics) {
        hub.metrics(metrics, "bms_sse");
    }

    /** Ends every stream (shutdown), so open SSE responses finish instead of holding a drain. */
    public void closeAll() {
        for (Stream s : streams) {
            s.close();
        }
    }

    private final class Stream implements Subscription {
        // One spare slot so the end marker always fits.
        private final BlockingQueue<Message> handoff = new ArrayBlockingQueue<>(HANDOFF + 1);
        private volatile Hub<Message>.Subscription sub;
        private volatile boolean closed;

        /** Runs on the hub's thread for this subscriber; blocking here backs up the hub queue. */
        private void deliver(long id, Message m) throws InterruptedException {
            Message withId = m.withId(Long.toString(id));
            while (!handoff.offer(withId, 100, TimeUnit.MILLISECONDS)) {
                if (!isOpen()) {
                    // A throw ends the hub subscription.
                    throw new IllegalStateException("stream closed");
                }
            }
        }

        @Override
        public Message poll(Duration timeout) throws InterruptedException {
            Message m = handoff.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (m == END) {
                handoff.offer(END);
                return null;
            }
            return m;
        }

        @Override
        public boolean isOpen() {
            Hub<Message>.Subscription s = sub;
            return !closed && (s == null || s.isOpen());
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            streams.remove(this);
            Hub<Message>.Subscription s = sub;
            if (s != null) {
                s.close();
            }
            // Wake a poller at once instead of after its heartbeat timeout.
            if (!handoff.offer(END)) {
                handoff.clear();
                handoff.offer(END);
            }
        }
    }
}
