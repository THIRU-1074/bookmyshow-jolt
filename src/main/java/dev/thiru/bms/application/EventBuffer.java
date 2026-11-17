package dev.thiru.bms.application;

import dev.thiru.bms.domain.SeatId;
import dev.thiru.bms.domain.port.SeatEvent;
import dev.thiru.bms.domain.port.SeatEventPublisher;
import java.util.ArrayList;
import java.util.List;

/** Collects seat events during a transaction and publishes them only after it commits. */
final class EventBuffer {

    private static final System.Logger LOG = System.getLogger("bms.events");

    private final List<SeatEvent> events = new ArrayList<>();

    void add(SeatEvent event) {
        events.add(event);
    }

    void released(long showId, List<SeatId> seats, SeatEvent.Reason reason) {
        if (!seats.isEmpty()) {
            events.add(new SeatEvent.Released(showId, seats, reason));
        }
    }

    /** A failing subscriber must not undo or fail a committed booking. */
    void publishTo(SeatEventPublisher publisher) {
        for (SeatEvent e : events) {
            try {
                publisher.publish(e);
            } catch (RuntimeException ex) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "seat event publish failed for show " + e.showId());
            }
        }
        events.clear();
    }
}
