package dev.thiru.bms.web;

import dev.thiru.bms.application.AdminCatalog;
import dev.thiru.bms.application.AdminReports;
import dev.thiru.bms.application.CancelShow;
import dev.thiru.bms.application.UserAdmin;
import dev.thiru.bms.domain.Certificate;
import dev.thiru.bms.domain.Cinema;
import dev.thiru.bms.domain.City;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.Movie;
import dev.thiru.bms.domain.Role;
import dev.thiru.bms.domain.SeatLayout;
import dev.thiru.bms.domain.ShowFormat;
import dev.thiru.bms.domain.port.LiveSeats;
import in.jolt.Middleware;
import in.jolt.Page;
import in.jolt.Params;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.RouteModule;
import in.jolt.Router;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Catalog management, reports, users, roles and theatre owner links under {@code /api/admin} (SPEC
 * 7, admin). The RBAC gate admits only the ADMIN role before any of these handlers runs; every POST
 * also needs an Idempotency-Key.
 */
final class AdminModule implements RouteModule {

    record CityRequest(String id, String name, String timeZone) {}

    record RenameRequest(String name) {}

    record CinemaRequest(String cityId, String name, String address) {}

    record ScreenRequest(String name, Dtos.LayoutRequest layout) {}

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

    static final Instant UNBOUNDED_END = Instant.parse("9999-12-31T00:00:00Z");

    record CancelShowResult(long showId, int refunded, int cancelled, int holdsReleased) {}

    private final AdminCatalog admin;
    private final AdminReports reports;
    private final CancelShow cancelShow;
    private final UserAdmin users;
    private final LiveSeats live;
    private final Middleware idempotent;

    AdminModule(
            AdminCatalog admin,
            AdminReports reports,
            CancelShow cancelShow,
            UserAdmin users,
            LiveSeats live,
            Middleware idempotent) {
        this.admin = admin;
        this.reports = reports;
        this.cancelShow = cancelShow;
        this.users = users;
        this.live = live;
        this.idempotent = idempotent;
    }

    @Override
    public void register(Router r) {
        String p = "/api/admin";

        r.post(
                p + "/cities",
                idempotent,
                (req, res) -> {
                    CityRequest in = body(req, CityRequest.class);
                    created(
                            res,
                            Dtos.CityDto.of(
                                    admin.createCity(City.of(in.id(), in.name(), in.timeZone()))));
                });
        r.put(
                p + "/cities/:id",
                (req, res) ->
                        ok(
                                res,
                                Dtos.CityDto.of(
                                        admin.renameCity(
                                                req.param("id"),
                                                body(req, RenameRequest.class).name()))));

        r.post(
                p + "/cinemas",
                idempotent,
                (req, res) -> {
                    CinemaRequest in = body(req, CinemaRequest.class);
                    created(
                            res,
                            Dtos.CinemaDto.of(
                                    admin.createCinema(
                                            new Cinema(0, in.cityId(), in.name(), in.address()))));
                });
        r.put(
                p + "/cinemas/:id",
                (req, res) -> {
                    CinemaRequest in = body(req, CinemaRequest.class);
                    ok(
                            res,
                            Dtos.CinemaDto.of(
                                    admin.updateCinema(
                                            Query.id(req, "id"), in.name(), in.address())));
                });
        r.delete(
                p + "/cinemas/:id",
                (req, res) -> {
                    admin.deleteCinema(Query.id(req, "id"));
                    res.status(204);
                });

        r.post(
                p + "/cinemas/:id/screens",
                idempotent,
                (req, res) -> {
                    ScreenRequest in = body(req, ScreenRequest.class);
                    created(
                            res,
                            Dtos.ScreenDto.of(
                                    admin.createScreen(
                                            Query.id(req, "id"), in.name(), layout(in))));
                });
        r.put(
                p + "/screens/:id",
                (req, res) -> {
                    ScreenRequest in = body(req, ScreenRequest.class);
                    ok(
                            res,
                            Dtos.ScreenDto.of(
                                    admin.updateScreen(
                                            Query.id(req, "id"), in.name(), layout(in))));
                });
        r.delete(
                p + "/screens/:id",
                (req, res) -> {
                    admin.deleteScreen(Query.id(req, "id"));
                    res.status(204);
                });

        r.post(
                p + "/movies",
                idempotent,
                (req, res) ->
                        created(
                                res,
                                Dtos.MovieDetail.of(
                                        admin.createMovie(
                                                movie(0, body(req, MovieRequest.class))))));
        r.put(
                p + "/movies/:id",
                (req, res) -> {
                    long id = Query.id(req, "id");
                    ok(
                            res,
                            Dtos.MovieDetail.of(
                                    admin.updateMovie(
                                            id, movie(id, body(req, MovieRequest.class)))));
                });
        r.delete(
                p + "/movies/:id",
                (req, res) -> {
                    admin.deleteMovie(Query.id(req, "id"));
                    res.status(204);
                });

        r.post(
                p + "/shows",
                idempotent,
                (req, res) -> {
                    ShowRequest in = show(body(req, ShowRequest.class));
                    created(
                            res,
                            Dtos.ShowDto.of(
                                    admin.createShow(
                                            in.movieId(),
                                            in.screenId(),
                                            in.startsAt(),
                                            in.language(),
                                            ShowFormat.parse(in.format()),
                                            in.prices())));
                });
        r.put(
                p + "/shows/:id",
                (req, res) -> {
                    ShowRequest in = show(body(req, ShowRequest.class));
                    ok(
                            res,
                            Dtos.ShowDto.of(
                                    admin.updateShow(
                                            Query.id(req, "id"),
                                            in.movieId(),
                                            in.screenId(),
                                            in.startsAt(),
                                            in.language(),
                                            ShowFormat.parse(in.format()),
                                            in.prices())));
                });
        r.post(
                p + "/shows/:id/cancel",
                idempotent,
                (req, res) -> {
                    long id = Query.id(req, "id");
                    CancelShow.Result c = cancelShow.execute(id);
                    ok(
                            res,
                            new CancelShowResult(
                                    id, c.refunded(), c.cancelled(), c.holdsReleased()));
                });

        r.get(p + "/reports/shows", this::reportList);
        r.get(
                p + "/reports/shows/:id",
                (req, res) ->
                        ok(res, Dtos.ShowReportDetailDto.of(reports.show(Query.id(req, "id")))));
        r.get(
                p + "/stats",
                (req, res) -> {
                    AdminReports.Stats s = reports.stats();
                    ok(
                            res,
                            new Dtos.StatsDto(
                                    s.activeHolds(),
                                    s.bookingsConfirmedToday(),
                                    live.openStreams()));
                });

        r.get(
                p + "/users",
                (req, res) -> {
                    Page page = Paging.request(req);
                    ok(
                            res,
                            Paging.result(
                                    users.list(Query.text(req, "q"), page.page(), page.size()),
                                    Dtos.AccountDto::of));
                });
        r.put(
                p + "/users/:id/roles/:role",
                (req, res) ->
                        ok(res, Dtos.AccountDto.of(users.grant(Query.id(req, "id"), role(req)))));
        r.delete(
                p + "/users/:id/roles/:role",
                (req, res) ->
                        ok(
                                res,
                                Dtos.AccountDto.of(
                                        users.revoke(
                                                Principal.of(req).userId(),
                                                Query.id(req, "id"),
                                                role(req)))));
        r.put(
                p + "/cinemas/:id/owners/:userId",
                (req, res) ->
                        ok(
                                res,
                                Dtos.AccountDto.of(
                                        users.link(Query.id(req, "id"), Query.id(req, "userId")))));
        r.delete(
                p + "/cinemas/:id/owners/:userId",
                (req, res) ->
                        ok(
                                res,
                                Dtos.AccountDto.of(
                                        users.unlink(
                                                Query.id(req, "id"), Query.id(req, "userId")))));
    }

    private static Role role(Request req) {
        try {
            return Role.valueOf(req.param("role").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("role", "must be one of AUDIENCE, THEATRE_OWNER, ADMIN");
        }
    }

    private void reportList(Request req, Response res) {
        Page page = Paging.request(req);
        ok(
                res,
                Paging.result(
                        reports.shows(
                                Query.text(req, "city"),
                                instant(req, "from", false),
                                instant(req, "to", true),
                                page.page(),
                                page.size()),
                        Dtos.ShowReportDto::of));
    }

    /**
     * {@code from}/{@code to} take an ISO instant or a UTC date; a date in {@code to} includes the
     * whole day.
     */
    static Instant instant(Params p, String name, boolean endOfDay) {
        String raw = Query.text(p, name);
        if (raw == null) {
            // An absent bound means unbounded; the port takes concrete instants.
            return endOfDay ? UNBOUNDED_END : Instant.EPOCH;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException notInstant) {
            try {
                LocalDate d = LocalDate.parse(raw);
                return (endOfDay ? d.plusDays(1) : d).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException e) {
                throw ApiException.validation(name, "must be an ISO instant or date");
            }
        }
    }

    static <T> T body(Request req, Class<T> type) {
        T in = req.body(type);
        if (in == null) {
            throw ApiException.validation("body", "is required");
        }
        return in;
    }

    static SeatLayout layout(ScreenRequest in) {
        if (in.layout() == null) {
            throw ApiException.validation("layout", "is required");
        }
        return in.layout().toDomain();
    }

    // TODO(jolt#61): declare required fields with nested validation rules.
    static ShowRequest show(ShowRequest in) {
        if (in.movieId() == null) {
            throw ApiException.validation("movieId", "is required");
        }
        if (in.screenId() == null) {
            throw ApiException.validation("screenId", "is required");
        }
        if (in.startsAt() == null) {
            throw ApiException.validation("startsAt", "is required");
        }
        if (in.format() == null) {
            throw ApiException.validation("format", "is required");
        }
        if (in.prices() == null) {
            throw ApiException.validation("prices", "is required");
        }
        return in;
    }

    private static Movie movie(long id, MovieRequest in) {
        Certificate cert;
        try {
            cert =
                    in.certificate() == null
                            ? null
                            : Certificate.valueOf(in.certificate().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw DomainException.validation("certificate", "must be one of U, UA, A, S");
        }
        return new Movie(
                id,
                in.title(),
                in.languages(),
                in.genres(),
                cert,
                in.durationMinutes() == null ? 0 : in.durationMinutes(),
                in.releaseDate(),
                in.posterUrl(),
                in.description() == null ? "" : in.description());
    }

    static void created(Response res, Object body) {
        res.status(201).noStore().json(body);
    }

    static void ok(Response res, Object body) {
        res.noStore().json(body);
    }
}
