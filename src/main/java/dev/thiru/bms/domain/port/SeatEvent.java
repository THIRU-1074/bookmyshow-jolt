package dev.thiru.bms.domain.port;

import dev.thiru.bms.domain.SeatId;
import java.time.Instant;
import java.util.List;

/** Live seat change, published only after the transaction commits and never carrying user data. */
public sealed interface SeatEvent {

    long showId();

    record Held(long showId, List<SeatId> seats, Instant until) implements SeatEvent {}

    enum Reason {
        EXPIRED,
        RELEASED,
        CANCELLED
    }

    record Released(long showId, List<SeatId> seats, Reason reason) implements SeatEvent {}

    record Booked(long showId, List<SeatId> seats) implements SeatEvent {}
}
