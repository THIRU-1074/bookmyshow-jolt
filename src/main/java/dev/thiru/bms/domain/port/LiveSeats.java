package dev.thiru.bms.domain.port;

import java.time.Duration;
import java.util.Optional;

/**
 * Live seat stream for server-sent events. The adapter owns fan-out, the per-subscriber queue and
 * the cap on open streams; payloads are pre-rendered JSON and never carry user data.
 */
public interface LiveSeats {

    /** One SSE message: event name and JSON data. */
    record Message(String name, String data) {}

    interface Subscription extends AutoCloseable {
        /** Next message, or null on timeout or when the stream has ended. */
        Message poll(Duration timeout) throws InterruptedException;

        boolean isOpen();

        @Override
        void close();
    }

    /** Empty when the stream cap is reached; the caller answers 503. */
    Optional<Subscription> subscribe(long showId);

    int openStreams();
}
