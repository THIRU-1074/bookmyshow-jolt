package dev.thiru.bms.web;

import dev.thiru.bms.contract.LiveSeatsContract;
import dev.thiru.bms.domain.SeatId;
import dev.thiru.bms.domain.port.LiveSeats;
import dev.thiru.bms.domain.port.SeatEvent;
import java.util.stream.Collectors;

/**
 * The web tests' FakeLive. It has no publisher side, so {@link #emit} renders the SPEC 7 payload
 * here; what this proves is that the fake's subscribe, cap, routing and close behave like the hub.
 */
class FakeLiveContractTest extends LiveSeatsContract {

    @Override
    protected LiveSeats newLive() {
        return new WebKit.FakeLive(CAP);
    }

    @Override
    protected void emit(LiveSeats live, SeatEvent event) {
        String seats =
                (switch (event) {
                            case SeatEvent.Held h -> h.seats();
                            case SeatEvent.Released r -> r.seats();
                            case SeatEvent.Booked b -> b.seats();
                        })
                        .stream()
                                .map(SeatId::toString)
                                .map(s -> "\"" + s + "\"")
                                .collect(Collectors.joining(",", "[", "]"));
        String head = "{\"showId\":" + event.showId() + ",\"seats\":" + seats;
        WebKit.FakeLive fake = (WebKit.FakeLive) live;
        switch (event) {
            case SeatEvent.Held h ->
                    fake.push(h.showId(), "seat-held", head + ",\"until\":\"" + h.until() + "\"}");
            case SeatEvent.Released r ->
                    fake.push(
                            r.showId(),
                            "seat-released",
                            head + ",\"reason\":\"" + r.reason().name() + "\"}");
            case SeatEvent.Booked b -> fake.push(b.showId(), "seat-booked", head + "}");
        }
    }
}
