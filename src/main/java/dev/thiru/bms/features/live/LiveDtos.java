package dev.thiru.bms.features.live;

import dev.thiru.bms.kernel.catalog.SeatMap;
import java.util.List;

/**
 * The snapshot event's JSON: copies of the {@code web.Dtos} seat map records, component for
 * component. The catalog slice has its own copy; {@code RecordGeneratorTest} holds both to {@code
 * api/openapi.yaml}.
 */
final class LiveDtos {

    private LiveDtos() {}

    record PriceDto(String category, String name, long amount) {}

    record SeatDto(String id, int number, String category, String status) {}

    record SeatRowDto(String row, List<SeatDto> seats, List<Integer> aisleAfter) {}

    record SeatMapDto(
            long showId,
            long version,
            List<PriceDto> categories,
            List<SeatRowDto> rows,
            long holdTtlSeconds) {
        static SeatMapDto of(SeatMap m) {
            return new SeatMapDto(
                    m.showId(),
                    m.version(),
                    m.categories().stream()
                            .map(p -> new PriceDto(p.category(), p.name(), p.amount()))
                            .toList(),
                    m.rows().stream().map(SeatMapDto::row).toList(),
                    m.holdTtlSeconds());
        }

        private static SeatRowDto row(SeatMap.Row r) {
            return new SeatRowDto(
                    r.row(),
                    r.seats().stream()
                            .map(
                                    s ->
                                            new SeatDto(
                                                    s.id().toString(),
                                                    s.number(),
                                                    s.category(),
                                                    s.status().name()))
                            .toList(),
                    r.aisleAfter());
        }
    }
}
