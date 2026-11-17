package dev.thiru.bms.features.admin;

import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.kernel.catalog.Cinema;
import dev.thiru.bms.kernel.catalog.City;
import dev.thiru.bms.kernel.catalog.Movie;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.kernel.catalog.Show;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Request and response records of the admin and owner routes, copied component for component from
 * {@code web.AdminModule} and {@code web.Dtos} so the JSON is unchanged ({@code
 * RecordGeneratorTest} checks them against the schema).
 */
final class AdminDtos {

    private AdminDtos() {}

    // ---- requests ----

    record CityRequest(String id, String name, String timeZone) {}

    record RenameRequest(String name) {}

    record CinemaRequest(String cityId, String name, String address) {}

    record ScreenRequest(String name, LayoutRequest layout) {}

    record MovieRequest(
            String title,
            List<String> languages,
            List<String> genres,
            String certificate,
            Integer durationMinutes,
            LocalDate releaseDate,
            String posterUrl,
            String description) {}

    record ShowRequest(
            Long movieId,
            Long screenId,
            Instant startsAt,
            String language,
            String format,
            Map<String, Long> prices) {}

    // ---- responses ----

    record CancelShowResult(long showId, int refunded, int cancelled, int holdsReleased) {}

    record CityDto(String id, String name, String timeZone) {
        static CityDto of(City c) {
            return new CityDto(c.id(), c.name(), c.timeZone().getId());
        }
    }

    record CinemaDto(long id, String cityId, String name, String address) {
        static CinemaDto of(Cinema c) {
            return new CinemaDto(c.id(), c.cityId(), c.name(), c.address());
        }
    }

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
        static MovieDetail of(Movie m) {
            return new MovieDetail(
                    m.id(),
                    m.title(),
                    m.languages(),
                    m.genres(),
                    m.certificate().name(),
                    m.durationMinutes(),
                    m.releaseDate(),
                    m.posterUrl(),
                    m.description());
        }
    }

    record ScreenDto(long id, long cinemaId, String name, int capacity, LayoutRequest layout) {
        static ScreenDto of(Screen s) {
            return new ScreenDto(
                    s.id(), s.cinemaId(), s.name(), s.capacity(), LayoutRequest.of(s.layout()));
        }
    }

    record ShowDto(
            long id,
            long movieId,
            long screenId,
            Instant startsAt,
            String language,
            String format,
            Map<String, Long> prices,
            String status) {
        static ShowDto of(Show s) {
            return new ShowDto(
                    s.id(),
                    s.movieId(),
                    s.screenId(),
                    s.startsAt(),
                    s.language(),
                    s.format().label(),
                    s.prices(),
                    s.status().name());
        }
    }

    record ShowReportDto(
            long showId,
            String movieTitle,
            String cinemaName,
            Instant startsAt,
            String status,
            int capacity,
            int booked,
            double occupancyPercent,
            long revenue,
            long refunded) {
        static ShowReportDto of(AdminReports.ShowReport r) {
            return new ShowReportDto(
                    r.showId(),
                    r.movieTitle(),
                    r.cinemaName(),
                    r.startsAt(),
                    r.status().name(),
                    r.capacity(),
                    r.booked(),
                    r.occupancyPercent(),
                    r.revenue(),
                    r.refunded());
        }
    }

    record CategoryReportDto(String category, int capacity, int booked, long revenue) {
        static CategoryReportDto of(AdminReports.CategoryStat c) {
            return new CategoryReportDto(c.category(), c.capacity(), c.booked(), c.revenue());
        }
    }

    record ShowReportDetailDto(
            long showId,
            String movieTitle,
            String cinemaName,
            Instant startsAt,
            String status,
            int capacity,
            int booked,
            double occupancyPercent,
            long revenue,
            long refunded,
            List<CategoryReportDto> byCategory) {
        static ShowReportDetailDto of(AdminReports.ShowReportDetail d) {
            AdminReports.ShowReport r = d.show();
            return new ShowReportDetailDto(
                    r.showId(),
                    r.movieTitle(),
                    r.cinemaName(),
                    r.startsAt(),
                    r.status().name(),
                    r.capacity(),
                    r.booked(),
                    r.occupancyPercent(),
                    r.revenue(),
                    r.refunded(),
                    d.byCategory().stream().map(CategoryReportDto::of).toList());
        }
    }

    record StatsDto(long activeHolds, long bookingsConfirmedToday, int sseStreams) {}

    /** A user as the admin Users tab sees it: roles and the cinemas they own. */
    record AccountDto(
            long id, String email, String name, List<String> roles, List<Long> cinemaIds) {
        static AccountDto of(UserAdmin.Account a) {
            User u = a.user();
            return new AccountDto(
                    u.id(),
                    u.email(),
                    u.name(),
                    u.roles().stream().map(Enum::name).sorted().toList(),
                    a.cinemaIds());
        }
    }

    /** One of the caller's cinemas with its screens, layouts included, for the owner dashboard. */
    record OwnedCinemaDto(
            long id, String cityId, String name, String address, List<ScreenDto> screens) {
        static OwnedCinemaDto of(AdminCatalog.CinemaScreens c) {
            Cinema cinema = c.cinema();
            return new OwnedCinemaDto(
                    cinema.id(),
                    cinema.cityId(),
                    cinema.name(),
                    cinema.address(),
                    c.screens().stream().map(ScreenDto::of).toList());
        }
    }

    // ---- layout (request and response share the shape) ----

    record CategoryRequest(String code, String name) {}

    record RowRequest(
            String row,
            int seats,
            String category,
            List<Integer> aisleAfter,
            List<Integer> blocked) {}

    record LayoutRequest(List<CategoryRequest> categories, List<RowRequest> rows) {

        static LayoutRequest of(SeatLayout l) {
            return new LayoutRequest(
                    l.categories().stream()
                            .map(c -> new CategoryRequest(c.code(), c.name()))
                            .toList(),
                    l.rows().stream()
                            .map(
                                    r ->
                                            new RowRequest(
                                                    r.row(),
                                                    r.seats(),
                                                    r.category(),
                                                    r.aisleAfter(),
                                                    r.blocked()))
                            .toList());
        }

        /** Builds the domain layout; its constructor reports nested paths like rows[3].seats. */
        SeatLayout toDomain() {
            return new SeatLayout(
                    categories == null
                            ? List.of()
                            : categories.stream()
                                    .map(c -> new SeatLayout.Category(c.code(), c.name()))
                                    .toList(),
                    rows == null
                            ? List.of()
                            : rows.stream()
                                    .map(
                                            r ->
                                                    new SeatLayout.Row(
                                                            r.row(),
                                                            r.seats(),
                                                            r.category(),
                                                            r.aisleAfter(),
                                                            r.blocked()))
                                    .toList());
        }
    }
}
