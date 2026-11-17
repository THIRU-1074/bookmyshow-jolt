package dev.thiru.bms.adapters.live;

import dev.thiru.bms.contract.LiveSeatsContract;
import dev.thiru.bms.domain.port.LiveSeats;
import dev.thiru.bms.domain.port.SeatEvent;

/** The production hub: events go in through the publisher side and come out as SSE messages. */
class LiveSeatHubContractTest extends LiveSeatsContract {

    @Override
    protected LiveSeats newLive() {
        return new LiveSeatHub(CAP, 16);
    }

    @Override
    protected void emit(LiveSeats live, SeatEvent event) {
        ((LiveSeatHub) live).publish(event);
    }
}
