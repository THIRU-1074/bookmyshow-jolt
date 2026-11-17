package dev.thiru.bms.testing;

import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.ports.SeatEvents;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every published {@link SeatEvent}; {@link #fail} makes every publish throw, as a broken
 * subscriber would. Held to {@code SeatEventPublisherContract}.
 */
public final class RecordingEvents implements SeatEvents {
    public final List<SeatEvent> published = new CopyOnWriteArrayList<>();
    public volatile boolean fail;

    @Override
    public void publish(SeatEvent event) {
        if (fail) {
            throw new IllegalStateException("subscriber down");
        }
        published.add(event);
    }

    /** The published events of one kind, in order. */
    public <T extends SeatEvent> List<T> of(Class<T> kind) {
        return published.stream().filter(kind::isInstance).map(kind::cast).toList();
    }
}
