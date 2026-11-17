package dev.thiru.bms.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.thiru.bms.domain.SeatId;
import dev.thiru.bms.domain.port.SeatEvent;
import dev.thiru.bms.domain.port.SeatEventPublisher;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Use cases publish after commit and must never be blocked or failed by the live side. */
public abstract class SeatEventPublisherContract {

    protected abstract SeatEventPublisher newPublisher();

    static List<SeatEvent> everyKind(long showId) {
        List<SeatId> seats = List.of(SeatId.parse("C7"), SeatId.parse("C8"));
        return List.of(
                new SeatEvent.Held(showId, seats, Instant.parse("2026-11-20T13:07:00Z")),
                new SeatEvent.Released(showId, seats, SeatEvent.Reason.EXPIRED),
                new SeatEvent.Released(showId, seats, SeatEvent.Reason.RELEASED),
                new SeatEvent.Released(showId, seats, SeatEvent.Reason.CANCELLED),
                new SeatEvent.Booked(showId, seats));
    }

    @Test
    protected void everyEventKindPublishesWithNobodyListening() {
        SeatEventPublisher p = newPublisher();
        everyKind(7).forEach(e -> assertDoesNotThrow(() -> p.publish(e)));
    }

    @Test
    protected void aBurstOfEventsNeverThrows() {
        SeatEventPublisher p = newPublisher();
        assertDoesNotThrow(
                () -> {
                    for (int i = 0; i < 2000; i++) {
                        everyKind(i % 3).forEach(p::publish);
                    }
                });
    }
}
