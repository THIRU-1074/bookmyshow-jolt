package dev.thiru.bms.features.admin;

import dev.thiru.bms.features.admin.AdminDtos.AccountDto;
import dev.thiru.bms.features.admin.AdminDtos.CinemaDto;
import dev.thiru.bms.features.admin.AdminDtos.CinemaRequest;
import dev.thiru.bms.features.admin.AdminDtos.CityDto;
import dev.thiru.bms.features.admin.AdminDtos.CityRequest;
import dev.thiru.bms.features.admin.AdminDtos.MovieDetail;
import dev.thiru.bms.features.admin.AdminDtos.MovieRequest;
import dev.thiru.bms.features.admin.AdminDtos.RenameRequest;
import dev.thiru.bms.features.admin.AdminDtos.ScreenDto;
import dev.thiru.bms.features.admin.AdminDtos.ScreenRequest;
import dev.thiru.bms.features.admin.AdminDtos.ShowDto;
import dev.thiru.bms.features.admin.AdminDtos.ShowReportDetailDto;
import dev.thiru.bms.features.admin.AdminDtos.ShowRequest;
import dev.thiru.bms.features.admin.AdminDtos.StatsDto;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.account.CinemaScope;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.catalog.Certificate;
import dev.thiru.bms.kernel.catalog.Cinema;
import dev.thiru.bms.kernel.catalog.City;
import dev.thiru.bms.kernel.catalog.Movie;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.LiveSeats;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.SeatEvents;
import dev.thiru.bms.web.ApiException;
import dev.thiru.bms.web.Paging;
import dev.thiru.bms.web.Principal;
import dev.thiru.bms.web.Query;
import in.jolt.Middleware;
import in.jolt.Page;
import in.jolt.Params;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.RouteModule;
import in.jolt.Router;
import in.jolt.Rules;
import in.jolt.Validate;
import in.jolt.Validator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Catalog management, reports, users, roles and theatre owner links under {@code /api/admin} (SPEC
 * 7, admin). The RBAC gate admits only the ADMIN role before any of these handlers runs; every POST
 * also needs an Idempotency-Key. The pipeline clears the public catalog cache after every
 * successful write here.
 */
public final class AdminRoutes implements RouteModule {

    static final Instant UNBOUNDED_END = Instant.parse("9999-12-31T00:00:00Z");

    private final AdminCatalog admin;
    private final AdminReports reports;
    private final CancelShow cancelShow;
    private final UserAdmin users;
    private final LiveSeats live;
    private final Middleware idempotent;

    public AdminRoutes(
            Db db,
            Clock clock,
            PaymentGateway gateway,
            SeatEvents events,
            LiveSeats live,
            Middleware idempotent) {
        this.admin = new AdminCatalog(db, clock);
        this.reports = new AdminReports(db, clock);
        this.cancelShow = new CancelShow(db, gateway, events, clock);
        this.users = new UserAdmin(db);
        this.live = live;
        this.idempotent = idempotent;
    }

    @Override
    public void register(Router r) {
        String p = "/api/admin";
        CinemaScope all = CinemaScope.everything();

        r.post(
                p + "/cities",
                idempotent,
                (req, res) -> {
                    CityRequest in = body(req, CityRequest.class);
                    created(
                            res,
                            CityDto.of(
                                    admin.createCity(City.of(in.id(), in.name(), in.timeZone()))));
                });
        r.put(
                p + "/cities/:id",
                (req, res) ->
                        ok(
                                res,
                                CityDto.of(
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
                            CinemaDto.of(
                                    admin.createCinema(
                                            new Cinema(0, in.cityId(), in.name(), in.address()))));
                });
        r.put(
                p + "/cinemas/:id",
                (req, res) -> {
                    CinemaRequest in = body(req, CinemaRequest.class);
                    ok(
                            res,
                            CinemaDto.of(
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
                            ScreenDto.of(
                                    admin.createScreen(
                                            all, Query.id(req, "id"), in.name(), layout(in))));
                });
        r.put(
                p + "/screens/:id",
                (req, res) -> {
                    ScreenRequest in = body(req, ScreenRequest.class);
                    ok(
                            res,
                            ScreenDto.of(
                                    admin.updateScreen(
                                            all, Query.id(req, "id"), in.name(), layout(in))));
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
                                MovieDetail.of(
                                        admin.createMovie(
                                                movie(0, body(req, MovieRequest.class))))));
        r.put(
                p + "/movies/:id",
                (req, res) -> {
                    long id = Query.id(req, "id");
                    ok(
                            res,
                            MovieDetail.of(
                                    admin.updateMovie(
                                            id, movie(id, body(req, MovieRequest.class)))));
                });
        r.delete(
                p + "/movies/:id",
                (req, res) -> {
                    admin.deleteMovie(Query.id(req, "id"));
                    res.status(204);
                });

        r.post(p + "/shows", idempotent, (req, res) -> createShow(req, res, admin, all));
        r.put(
                p + "/shows/:id",
                (req, res) -> {
                    ShowRequest in = show(body(req, ShowRequest.class));
                    ok(
                            res,
                            ShowDto.of(
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
                (req, res) -> cancelShow(req, res, cancelShow, all));

        r.get(p + "/reports/shows", (req, res) -> reportList(req, res, reports, all));
        r.get(
                p + "/reports/shows/:id",
                (req, res) ->
                        ok(res, ShowReportDetailDto.of(reports.show(all, Query.id(req, "id")))));
        r.get(
                p + "/stats",
                (req, res) -> {
                    AdminReports.Stats s = reports.stats();
                    ok(
                            res,
                            new StatsDto(
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
                                    AccountDto::of));
                });
        r.put(
                p + "/users/:id/roles/:role",
                (req, res) -> ok(res, AccountDto.of(users.grant(Query.id(req, "id"), role(req)))));
        r.delete(
                p + "/users/:id/roles/:role",
                (req, res) ->
                        ok(
                                res,
                                AccountDto.of(
                                        users.revoke(
                                                Principal.of(req).userId(),
                                                Query.id(req, "id"),
                                                role(req)))));
        r.put(
                p + "/cinemas/:id/owners/:userId",
                (req, res) ->
                        ok(
                                res,
                                AccountDto.of(
                                        users.link(Query.id(req, "id"), Query.id(req, "userId")))));
        r.delete(
                p + "/cinemas/:id/owners/:userId",
                (req, res) ->
                        ok(
                                res,
                                AccountDto.of(
                                        users.unlink(
                                                Query.id(req, "id"), Query.id(req, "userId")))));
    }

    // ---- handlers the owner routes share, with the caller's scope ----

    static void createShow(Request req, Response res, AdminCatalog catalog, CinemaScope scope) {
        ShowRequest in = show(body(req, ShowRequest.class));
        created(
                res,
                ShowDto.of(
                        catalog.createShow(
                                scope,
                                in.movieId(),
                                in.screenId(),
                                in.startsAt(),
                                in.language(),
                                ShowFormat.parse(in.format()),
                                in.prices())));
    }

    static void cancelShow(Request req, Response res, CancelShow cancel, CinemaScope scope) {
        long id = Query.id(req, "id");
        CancelShow.Result c = cancel.execute(scope, id);
        ok(res, new AdminDtos.CancelShowResult(id, c.refunded(), c.cancelled(), c.holdsReleased()));
    }

    static void reportList(Request req, Response res, AdminReports reports, CinemaScope scope) {
        Page page = Paging.request(req);
        ok(
                res,
                Paging.result(
                        reports.shows(
                                scope,
                                Query.text(req, "city"),
                                instant(req, "from", false),
                                instant(req, "to", true),
                                page.page(),
                                page.size()),
                        AdminDtos.ShowReportDto::of));
    }

    // ---- request parsing ----

    private static Role role(Request req) {
        try {
            return Role.valueOf(req.param("role").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("role", "must be one of AUDIENCE, THEATRE_OWNER, ADMIN");
        }
    }

    /**
     * {@code from}/{@code to} take an ISO instant or a UTC date; a date in {@code to} includes the
     * whole day. An absent bound is unbounded.
     */
    static Instant instant(Params p, String name, boolean endOfDay) {
        String raw = Query.text(p, name);
        if (raw == null) {
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

    /** Every missing field in one 422; the use case validates values (overlap, prices). */
    static final Validator<ShowRequest> SHOW =
            (in, r) -> {
                required(r, "movieId", in.movieId());
                required(r, "screenId", in.screenId());
                required(r, "startsAt", in.startsAt());
                required(r, "format", in.format());
                // A null price is "prices.<category>: is required".
                r.nested(
                        "prices",
                        in.prices(),
                        (prices, pr) -> prices.forEach((cat, amount) -> required(pr, cat, amount)));
            };

    static ShowRequest show(ShowRequest in) {
        Validate.check(r -> SHOW.validate(in, r));
        return in;
    }

    private static void required(Rules r, String field, Object value) {
        if (value == null) {
            r.error(field, "is required");
        }
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
