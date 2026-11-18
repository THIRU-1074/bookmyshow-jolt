package dev.thiru.bms.features.admin;

import dev.thiru.bms.api.generated.Account;
import dev.thiru.bms.api.generated.CategoryReport;
import dev.thiru.bms.api.generated.Layout;
import dev.thiru.bms.api.generated.LayoutCategory;
import dev.thiru.bms.api.generated.LayoutRow;
import dev.thiru.bms.api.generated.MovieDetail;
import dev.thiru.bms.api.generated.OwnedCinema;
import dev.thiru.bms.api.generated.ShowReport;
import dev.thiru.bms.api.generated.ShowReportDetail;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.kernel.catalog.Cinema;
import dev.thiru.bms.kernel.catalog.City;
import dev.thiru.bms.kernel.catalog.Movie;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.kernel.catalog.Show;
import java.util.List;

/**
 * Maps admin and owner values to the routes' wire types, the records generated from {@code
 * api/openapi.yaml} ({@code dev.thiru.bms.api.generated}). Where a generated record shares a name
 * with a kernel type (City, Cinema, Screen, Show) it is written in full.
 */
final class AdminDtos {

    private AdminDtos() {}

    static dev.thiru.bms.api.generated.City city(City c) {
        return new dev.thiru.bms.api.generated.City(c.id(), c.name(), c.timeZone().getId());
    }

    static dev.thiru.bms.api.generated.Cinema cinema(Cinema c) {
        return new dev.thiru.bms.api.generated.Cinema(c.id(), c.cityId(), c.name(), c.address());
    }

    static MovieDetail movieDetail(Movie m) {
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

    static dev.thiru.bms.api.generated.Screen screen(Screen s) {
        return new dev.thiru.bms.api.generated.Screen(
                s.id(), s.cinemaId(), s.name(), s.capacity(), layout(s.layout()));
    }

    static dev.thiru.bms.api.generated.Show show(Show s) {
        return new dev.thiru.bms.api.generated.Show(
                s.id(),
                s.movieId(),
                s.screenId(),
                s.startsAt(),
                s.language(),
                s.format().label(),
                s.prices(),
                s.status().name());
    }

    static ShowReport showReport(AdminReports.ShowReport r) {
        return new ShowReport(
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

    static CategoryReport categoryReport(AdminReports.CategoryStat c) {
        return new CategoryReport(c.category(), c.capacity(), c.booked(), c.revenue());
    }

    static ShowReportDetail showReportDetail(AdminReports.ShowReportDetail d) {
        AdminReports.ShowReport r = d.show();
        return new ShowReportDetail(
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
                d.byCategory().stream().map(AdminDtos::categoryReport).toList());
    }

    /** A user as the admin Users tab sees it: roles and the cinemas they own. */
    static Account account(UserAdmin.Account a) {
        User u = a.user();
        return new Account(
                u.id(),
                u.email(),
                u.name(),
                u.roles().stream().map(Enum::name).sorted().toList(),
                a.cinemaIds());
    }

    /** One of the caller's cinemas with its screens, layouts included, for the owner dashboard. */
    static OwnedCinema ownedCinema(AdminCatalog.CinemaScreens c) {
        Cinema cinema = c.cinema();
        return new OwnedCinema(
                cinema.id(),
                cinema.cityId(),
                cinema.name(),
                cinema.address(),
                c.screens().stream().map(AdminDtos::screen).toList());
    }

    // ---- layout (request and response share the shape) ----

    static Layout layout(SeatLayout l) {
        return new Layout(
                l.categories().stream().map(c -> new LayoutCategory(c.code(), c.name())).toList(),
                l.rows().stream()
                        .map(
                                r ->
                                        new LayoutRow(
                                                r.row(),
                                                r.seats(),
                                                r.category(),
                                                r.aisleAfter(),
                                                r.blocked()))
                        .toList());
    }

    /** Builds the domain layout; its constructor reports nested paths like rows[3].seats. */
    static SeatLayout toDomain(Layout layout) {
        List<LayoutCategory> categories = layout.categories();
        List<LayoutRow> rows = layout.rows();
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
