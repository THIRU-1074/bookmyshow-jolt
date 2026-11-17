package dev.thiru.bms.web;

import dev.thiru.bms.application.AdminReports;
import dev.thiru.bms.application.BookingQueries;
import dev.thiru.bms.application.CatalogQueries;
import dev.thiru.bms.domain.Booking;
import dev.thiru.bms.domain.Cinema;
import dev.thiru.bms.domain.City;
import dev.thiru.bms.domain.Movie;
import dev.thiru.bms.domain.Screen;
import dev.thiru.bms.domain.SeatLayout;
import dev.thiru.bms.domain.Show;
import dev.thiru.bms.domain.User;
import dev.thiru.bms.domain.port.AdminRepositories;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** Response records (the JSON contract of SPEC 7) and their mappers from domain types. */
final class Dtos {

    private Dtos() {}

    // ---- auth ----

    record UserDto(long id, String email, String name, List<String> roles) {
        static UserDto of(User u) {
            return new UserDto(
                    u.id(),
                    u.email(),
                    u.name(),
                    u.roles().stream().map(Enum::name).sorted().toList());
        }
    }

    record AuthResponse(UserDto user, String accessToken, long expiresIn) {}

    record TokenResponse(String accessToken, long expiresIn) {}

    // ---- catalog ----

    record CityDto(String id, String name, String timeZone) {
        static CityDto of(City c) {
            return new CityDto(c.id(), c.name(), c.timeZone().getId());
        }
    }

    record MovieSummary(
            long id,
            String title,
            List<String> languages,
            List<String> genres,
            String certificate,
            int durationMinutes,
            LocalDate releaseDate,
            String posterUrl) {
        static MovieSummary of(Movie m) {
            return new MovieSummary(
                    m.id(),
                    m.title(),
                    m.languages(),
                    m.genres(),
                    m.certificate().name(),
                    m.durationMinutes(),
                    m.releaseDate(),
                    m.posterUrl());
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

    record CinemaDto(long id, String cityId, String name, String address) {
        static CinemaDto of(Cinema c) {
            return new CinemaDto(c.id(), c.cityId(), c.name(), c.address());
        }
    }

    record ScreenSummary(long id, String name, int capacity) {}

    record CinemaDetailDto(
            long id, String cityId, String name, String address, List<ScreenSummary> screens) {
        static CinemaDetailDto of(CatalogQueries.CinemaDetail d) {
            Cinema c = d.cinema();
            return new CinemaDetailDto(
                    c.id(),
                    c.cityId(),
                    c.name(),
                    c.address(),
                    d.screens().stream()
                            .map(s -> new ScreenSummary(s.id(), s.name(), s.capacity()))
                            .toList());
        }
    }

    record SlotDto(
            long id,
            Instant startsAt,
            LocalTime localTime,
            String language,
            String format,
            long minPrice,
            String availability) {}

    record CinemaShowsDto(long id, String name, String address, List<SlotDto> shows) {}

    record ShowTimesDto(LocalDate date, List<CinemaShowsDto> cinemas) {
        static ShowTimesDto of(CatalogQueries.ShowTimes t) {
            return new ShowTimesDto(
                    t.date(),
                    t.cinemas().stream()
                            .map(
                                    c ->
                                            new CinemaShowsDto(
                                                    c.cinema().id(),
                                                    c.cinema().name(),
                                                    c.cinema().address(),
                                                    c.shows().stream()
                                                            .map(
                                                                    s ->
                                                                            new SlotDto(
                                                                                    s.show().id(),
                                                                                    s.show()
                                                                                            .startsAt(),
                                                                                    s.localTime(),
                                                                                    s.show()
                                                                                            .language(),
                                                                                    s.show()
                                                                                            .format()
                                                                                            .label(),
                                                                                    s.minPrice(),
                                                                                    s.availability()
                                                                                            .name()))
                                                            .toList()))
                            .toList());
        }
    }

    record MovieRef(long id, String title, String certificate, int durationMinutes) {}

    record NamedRef(long id, String name) {}

    record PriceDto(String category, String name, long amount) {}

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
            String status) {
        static ShowDetailDto of(CatalogQueries.ShowDetail d) {
            Show s = d.show();
            return new ShowDetailDto(
                    s.id(),
                    new MovieRef(
                            d.movie().id(),
                            d.movie().title(),
                            d.movie().certificate().name(),
                            d.movie().durationMinutes()),
                    new NamedRef(d.cinema().id(), d.cinema().name()),
                    new NamedRef(d.screen().id(), d.screen().name()),
                    s.startsAt(),
                    d.localTime(),
                    s.language(),
                    s.format().label(),
                    d.prices().stream()
                            .map(p -> new PriceDto(p.category(), p.name(), p.amount()))
                            .toList(),
                    s.status().name());
        }
    }

    // ---- seats ----

    record SeatDto(String id, int number, String category, String status) {}

    record SeatRowDto(String row, List<SeatDto> seats, List<Integer> aisleAfter) {}

    record SeatMapDto(
            long showId,
            long version,
            List<PriceDto> categories,
            List<SeatRowDto> rows,
            long holdTtlSeconds) {
        static SeatMapDto of(CatalogQueries.SeatMap m) {
            return new SeatMapDto(
                    m.showId(),
                    m.version(),
                    m.categories().stream()
                            .map(p -> new PriceDto(p.category(), p.name(), p.amount()))
                            .toList(),
                    m.rows().stream()
                            .map(
                                    r ->
                                            new SeatRowDto(
                                                    r.row(),
                                                    r.seats().stream()
                                                            .map(
                                                                    c ->
                                                                            new SeatDto(
                                                                                    c.id().toString(),
                                                                                    c.number(),
                                                                                    c.category(),
                                                                                    c.status()
                                                                                            .name()))
                                                            .toList(),
                                                    r.aisleAfter()))
                            .toList(),
                    m.holdTtlSeconds());
        }
    }

    // ---- bookings ----

    record HoldDto(
            String id,
            long showId,
            List<String> seats,
            long amount,
            String currency,
            Instant expiresAt) {}

    record ShowInfoDto(
            long id,
            String movieTitle,
            String cinemaName,
            String screenName,
            Instant startsAt,
            LocalTime localTime) {}

    record BookingSeatDto(String id, String category, long price) {}

    record BookingDto(
            String id,
            String code,
            String status,
            ShowInfoDto show,
            List<BookingSeatDto> seats,
            long amount,
            String currency,
            long refundAmount,
            Instant expiresAt,
            long version,
            Instant createdAt) {
        static BookingDto of(BookingQueries.BookingView v) {
            Booking b = v.booking();
            BookingQueries.ShowInfo s = v.show();
            return new BookingDto(
                    b.id(),
                    b.code(),
                    b.status().name(),
                    new ShowInfoDto(
                            s.id(),
                            s.movieTitle(),
                            s.cinemaName(),
                            s.screenName(),
                            s.startsAt(),
                            s.localTime()),
                    b.seats().stream()
                            .map(
                                    x ->
                                            new BookingSeatDto(
                                                    x.seat().toString(), x.category(), x.price()))
                            .toList(),
                    b.amount(),
                    b.currency(),
                    b.refundAmount(),
                    b.expiresAt(),
                    b.version(),
                    b.createdAt());
        }
    }

    record CheckoutDto(
            String paymentId,
            String checkoutUrl,
            long amount,
            String currency,
            Instant expiresAt) {}

    // ---- admin ----

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
                    r.capacity(),
                    r.booked(),
                    r.occupancyPercent(),
                    r.revenue(),
                    r.refunded());
        }
    }

    record CategoryReportDto(String category, int capacity, int booked, long revenue) {
        static CategoryReportDto of(AdminRepositories.CategoryStat c) {
            return new CategoryReportDto(c.category(), c.capacity(), c.booked(), c.revenue());
        }
    }

    record ShowReportDetailDto(
            long showId,
            String movieTitle,
            String cinemaName,
            Instant startsAt,
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
                    r.capacity(),
                    r.booked(),
                    r.occupancyPercent(),
                    r.revenue(),
                    r.refunded(),
                    d.byCategory().stream().map(CategoryReportDto::of).toList());
        }
    }

    record StatsDto(long activeHolds, long bookingsConfirmedToday, int sseStreams) {}

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
