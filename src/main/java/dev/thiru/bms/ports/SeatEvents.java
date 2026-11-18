package dev.thiru.bms.ports;

public interface SeatEvents {

    /** Must not block or throw for slow subscribers; fan-out is the adapter's problem. */
    void publish(SeatEvent event);
}
