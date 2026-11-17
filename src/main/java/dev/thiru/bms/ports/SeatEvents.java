package dev.thiru.bms.domain.port;

public interface SeatEventPublisher {

    /** Must not block or throw for slow subscribers; fan-out is the adapter's problem. */
    void publish(SeatEvent event);
}
