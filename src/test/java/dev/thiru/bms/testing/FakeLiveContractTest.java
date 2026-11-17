package dev.thiru.bms.testing;

import dev.thiru.bms.contract.LiveSeatsContract;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.ports.LiveSeats;
import dev.thiru.bms.ports.SeatEvent;
import java.util.stream.Collectors;

/**
 * The slice and web tests' FakeLive. It has no publisher side, so {@link #emit} renders the SPEC 7
 * payload here and numbers events as the hub does; what this proves is that the fake's subscribe,
 * cap, routing and close behave like the hub.
 */
class FakeLiveContractTest extends LiveSeatsContract {

    private long nextId;

    @Override
    protected LiveSeats newLive() {
        return new FakeLive(CAP);
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
        FakeLive fake = (FakeLive) live;
        String id = Long.toString(++nextId);
        switch (event) {
            case SeatEvent.Held h ->
                    fake.push(
                            h.showId(),
                            id,
                            "seat-held",
                            head + ",\"until\":\"" + h.until() + "\"}");
            case SeatEvent.Released r ->
                    fake.push(
                            r.showId(),
                            id,
                            "seat-released",
                            head + ",\"reason\":\"" + r.reason().name() + "\"}");
            case SeatEvent.Booked b -> fake.push(b.showId(), id, "seat-booked", head + "}");
        }
    }
}
