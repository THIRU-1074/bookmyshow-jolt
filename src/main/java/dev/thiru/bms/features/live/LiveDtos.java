package dev.thiru.bms.features.live;

import dev.thiru.bms.api.generated.Price;
import dev.thiru.bms.api.generated.Seat;
import dev.thiru.bms.api.generated.SeatRow;
import dev.thiru.bms.kernel.catalog.SeatMap;

/**
 * Maps the seat map to the snapshot event's JSON, the records generated from {@code
 * api/openapi.yaml} ({@code dev.thiru.bms.api.generated}).
 */
final class LiveDtos {

    private LiveDtos() {}

    static dev.thiru.bms.api.generated.SeatMap seatMap(SeatMap m) {
        return new dev.thiru.bms.api.generated.SeatMap(
                m.showId(),
                m.version(),
                m.categories().stream()
                        .map(p -> new Price(p.category(), p.name(), p.amount()))
                        .toList(),
                m.rows().stream().map(LiveDtos::row).toList(),
                m.holdTtlSeconds());
    }

    private static SeatRow row(SeatMap.Row r) {
        return new SeatRow(
                r.row(),
                r.seats().stream()
                        .map(
                                s ->
                                        new Seat(
                                                s.id().toString(),
                                                s.number(),
                                                s.category(),
                                                s.status().name()))
                        .toList(),
                r.aisleAfter());
    }
}
