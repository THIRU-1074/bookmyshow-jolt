package dev.thiru.bms.testing;

import dev.thiru.bms.ports.LiveSeats;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** In-memory {@link LiveSeats} with a stream cap, held to {@code LiveSeatsContract}. */
public final class FakeLive implements LiveSeats {
    public final List<Sub> subs = new CopyOnWriteArrayList<>();
    private final int max;

    public FakeLive(int max) {
        this.max = max;
    }

    @Override
    public synchronized Optional<Subscription> subscribe(long showId) {
        if (openStreams() >= max) {
            return Optional.empty();
        }
        Sub s = new Sub(showId);
        subs.add(s);
        return Optional.of(s);
    }

    @Override
    public int openStreams() {
        return (int) subs.stream().filter(Sub::isOpen).count();
    }

    public void push(long showId, String id, String name, String data) {
        subs.stream()
                .filter(s -> s.showId == showId)
                .forEach(s -> s.queue.add(new Message(id, name, data)));
    }

    public void endAll() {
        subs.forEach(Sub::close);
    }

    public static final class Sub implements Subscription {
        public final long showId;
        public final BlockingQueue<Message> queue = new LinkedBlockingQueue<>();
        private volatile boolean open = true;

        Sub(long showId) {
            this.showId = showId;
        }

        @Override
        public Message poll(Duration timeout) throws InterruptedException {
            return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
