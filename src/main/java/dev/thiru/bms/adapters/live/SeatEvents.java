package dev.thiru.bms.adapters.live;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pub/sub hub for live seat events: one bounded queue per subscriber, a cap on concurrent streams
 * (SSE_MAX_STREAMS) and a drop policy for slow consumers. Publishers never block: a subscriber
 * whose queue is full is closed so one stalled browser cannot hold up booking transactions.
 *
 * <p>Event data is pre-rendered JSON supplied by the caller; the hub never inspects it.
 *
 * <p>TODO(jolt#62): delete when Jolt ships a pub/sub hub for SSE.
 */
public final class SeatEvents {

    /** Event kinds with their SSE event names (SPEC section 7). */
    public enum Type {
        SEAT_HELD("seat-held"),
        SEAT_RELEASED("seat-released"),
        SEAT_BOOKED("seat-booked"),
        HEARTBEAT("heartbeat");

        private final String sseName;

        Type(String sseName) {
            this.sseName = sseName;
        }

        public String sseName() {
            return sseName;
        }
    }

    /** One event; {@code showId} routes it, {@code data} is the JSON payload. */
    public record Event(Type type, long showId, String data) {}

    private static final Event END = new Event(Type.HEARTBEAT, -1, "");

    private final int maxSubscribers;
    private final int queueCapacity;
    private final AtomicInteger count = new AtomicInteger();
    private final ConcurrentHashMap<Subscription, Boolean> subscriptions =
            new ConcurrentHashMap<>();

    public SeatEvents(int maxSubscribers, int queueCapacity) {
        if (maxSubscribers < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("maxSubscribers and queueCapacity must be >= 1");
        }
        this.maxSubscribers = maxSubscribers;
        this.queueCapacity = queueCapacity;
    }

    /**
     * Registers a subscriber for a show; empty when the stream cap is reached (caller answers 503).
     */
    public Optional<Subscription> subscribe(long showId) {
        while (true) {
            int current = count.get();
            if (current >= maxSubscribers) {
                return Optional.empty();
            }
            if (count.compareAndSet(current, current + 1)) {
                break;
            }
        }
        Subscription s = new Subscription(showId, queueCapacity);
        subscriptions.put(s, Boolean.TRUE);
        return Optional.of(s);
    }

    /** Delivers to every subscriber of the event's show; never blocks. */
    public void publish(Event event) {
        for (Subscription s : subscriptions.keySet()) {
            if (s.showId == event.showId() && !s.offer(event)) {
                s.close();
            }
        }
    }

    /** Sends a heartbeat to every subscriber regardless of show. */
    public void heartbeat(String data) {
        for (Subscription s : subscriptions.keySet()) {
            if (!s.offer(new Event(Type.HEARTBEAT, s.showId, data))) {
                s.close();
            }
        }
    }

    /** Ends all streams of a show (it started or was cancelled). */
    public void closeShow(long showId) {
        for (Subscription s : subscriptions.keySet()) {
            if (s.showId == showId) {
                s.close();
            }
        }
    }

    public int subscriberCount() {
        return count.get();
    }

    /** A single stream; read with {@link #poll}, release with {@link #close}. */
    public final class Subscription implements AutoCloseable {
        private final long showId;
        private final BlockingQueue<Event> queue;
        private final int capacity;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Subscription(long showId, int capacity) {
            this.showId = showId;
            this.capacity = capacity;
            // One spare slot so the end marker always fits even when the data queue is full.
            this.queue = new ArrayBlockingQueue<>(capacity + 1);
        }

        // Per-subscriber lock keeps the size check and insert atomic across concurrent publishers,
        // which is what guarantees the spare slot for the end marker is never consumed by data.
        private synchronized boolean offer(Event e) {
            if (!open.get()) {
                return true;
            }
            if (queue.size() >= capacity) {
                return false;
            }
            return queue.offer(e);
        }

        /**
         * Next event, or null on timeout or once the stream has ended and been drained. Events
         * queued before a drop or close are still delivered first.
         */
        public Event poll(Duration timeout) throws InterruptedException {
            Event e = queue.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (e == END) {
                queue.offer(END);
                return null;
            }
            return e;
        }

        public boolean isOpen() {
            return open.get();
        }

        public long showId() {
            return showId;
        }

        /** Idempotent; frees the slot under the stream cap. */
        @Override
        public void close() {
            if (open.compareAndSet(true, false)) {
                subscriptions.remove(this);
                count.decrementAndGet();
                endStream();
            }
        }

        private synchronized void endStream() {
            queue.offer(END);
        }
    }
}
