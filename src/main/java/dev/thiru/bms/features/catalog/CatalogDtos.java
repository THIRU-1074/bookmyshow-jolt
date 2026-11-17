package dev.thiru.bms.features.catalog;

import dev.thiru.bms.kernel.catalog.SeatMap;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * The catalog's JSON contract (SPEC 7). Each record is a copy of the {@code web.Dtos} record of the
 * same name, component for component, so the wire format is unchanged; {@code RecordGeneratorTest}
 * holds them to {@code api/openapi.yaml}.
 */
final class CatalogDtos {

    private CatalogDtos() {}

    record CityDto(String id, String name, String timeZone) {}

    record MovieSummary(
            long id,
            String title,
            List<String> languages,
            List<String> genres,
            String certificate,
            int durationMinutes,
            LocalDate releaseDate,
            String posterUrl) {}

    record MovieDetail(
            long id,
            String title,
            List<String> languages,
            List<String> genres,
            String certificate,
            int durationMinutes,
            LocalDate releaseDate,
            String posterUrl,
            String description) {

        MovieSummary summary() {
            return new MovieSummary(
                    id,
                    title,
                    languages,
                    genres,
                    certificate,
                    durationMinutes,
                    releaseDate,
                    posterUrl);
        }
    }

    record CinemaDto(long id, String cityId, String name, String address) {}

    record ScreenSummary(long id, String name, int capacity) {}

    record CinemaDetailDto(
            long id, String cityId, String name, String address, List<ScreenSummary> screens) {}

    record SlotDto(
            long id,
            Instant startsAt,
            LocalTime localTime,
            String language,
            String format,
            long minPrice,
            String availability) {}

    record CinemaShowsDto(long id, String name, String address, List<SlotDto> shows) {}

    record ShowTimesDto(LocalDate date, List<CinemaShowsDto> cinemas) {}

    record MovieRef(long id, String title, String certificate, int durationMinutes) {}

    record NamedRef(long id, String name) {}

    record PriceDto(String category, String name, long amount) {
        static PriceDto of(SeatMap.Price p) {
            return new PriceDto(p.category(), p.name(), p.amount());
        }
    }

    record ShowDetailDto(
            long id,
            MovieRef movie,
            NamedRef cinema,
            NamedRef screen,
            Instant startsAt,
            LocalTime localTime,
            String language,
            String format,
            List<PriceDto> prices,
            String status) {}

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
                    m.categories().stream().map(PriceDto::of).toList(),
                    m.rows().stream()
                            .map(
                                    r ->
                                            new SeatRowDto(
                                                    r.row(),
                                                    r.seats().stream()
                                                            .map(
                                                                    s ->
                                                                            new SeatDto(
                                                                                    s.id().toString(),
                                                                                    s.number(),
                                                                                    s.category(),
                                                                                    s.status()
                                                                                            .name()))
                                                            .toList(),
                                                    r.aisleAfter()))
                            .toList(),
                    m.holdTtlSeconds());
        }
    }
}
