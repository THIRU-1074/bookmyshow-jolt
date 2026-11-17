package dev.thiru.bms.ports;

import dev.thiru.bms.features.live.SeatEvent;

public interface SeatEvents {
    void publish(SeatEvent event);
}
