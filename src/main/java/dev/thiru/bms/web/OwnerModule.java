package dev.thiru.bms.web;

import static dev.thiru.bms.web.AdminModule.body;
import static dev.thiru.bms.web.AdminModule.created;
import static dev.thiru.bms.web.AdminModule.ok;

import dev.thiru.bms.application.AdminCatalog;
import dev.thiru.bms.application.AdminReports;
import dev.thiru.bms.application.CancelShow;
import dev.thiru.bms.domain.ShowFormat;
import in.jolt.Middleware;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.RouteModule;
import in.jolt.Router;

/**
 * Theatre owner routes under {@code /api/owner} (SPEC 7, theatre owner). The gate admits
 * THEATRE_OWNER or ADMIN; every handler passes the caller's {@link Principal#cinemaScope()} to the
 * same use cases the admin routes use, and those answer 404 for a cinema outside it. Request and
 * response shapes match the admin routes.
 */
final class OwnerModule implements RouteModule {

    private final AdminCatalog catalog;
    private final AdminReports reports;
    private final CancelShow cancelShow;
    private final Middleware idempotent;

    OwnerModule(
            AdminCatalog catalog,
            AdminReports reports,
            CancelShow cancelShow,
            Middleware idempotent) {
        this.catalog = catalog;
        this.reports = reports;
        this.cancelShow = cancelShow;
        this.idempotent = idempotent;
    }

    @Override
    public void register(Router r) {
        String p = "/api/owner";

        r.get(
                p + "/cinemas",
                (req, res) ->
                        ok(
                                res,
                                catalog.cinemas(Principal.of(req).cinemaScope()).stream()
                                        .map(Dtos.OwnedCinemaDto::of)
                                        .toList()));
        r.post(
                p + "/cinemas/:id/screens",
                idempotent,
                (req, res) -> {
                    AdminModule.ScreenRequest in = body(req, AdminModule.ScreenRequest.class);
                    created(
                            res,
                            Dtos.ScreenDto.of(
                                    catalog.createScreen(
                                            Principal.of(req).cinemaScope(),
                                            Query.id(req, "id"),
                                            in.name(),
                                            AdminModule.layout(in))));
                });
        r.put(
                p + "/screens/:id",
                (req, res) -> {
                    AdminModule.ScreenRequest in = body(req, AdminModule.ScreenRequest.class);
                    ok(
                            res,
                            Dtos.ScreenDto.of(
                                    catalog.updateScreen(
                                            Principal.of(req).cinemaScope(),
                                            Query.id(req, "id"),
                                            in.name(),
                                            AdminModule.layout(in))));
                });

        r.post(
                p + "/shows",
                idempotent,
                (req, res) -> {
                    AdminModule.ShowRequest in =
                            AdminModule.show(body(req, AdminModule.ShowRequest.class));
                    created(
                            res,
                            Dtos.ShowDto.of(
                                    catalog.createShow(
                                            Principal.of(req).cinemaScope(),
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
                    CancelShow.Result c = cancelShow.execute(Principal.of(req).cinemaScope(), id);
                    ok(
                            res,
                            new AdminModule.CancelShowResult(
                                    id, c.refunded(), c.cancelled(), c.holdsReleased()));
                });

        r.get(p + "/reports/shows", this::reportList);
        r.get(
                p + "/reports/shows/:id",
                (req, res) ->
                        ok(
                                res,
                                Dtos.ShowReportDetailDto.of(
                                        reports.show(
                                                Principal.of(req).cinemaScope(),
                                                Query.id(req, "id")))));
    }

    private void reportList(Request req, Response res) {
        PageRequest page = PageRequest.of(req);
        ok(
                res,
                PageResult.of(
                        reports.shows(
                                Principal.of(req).cinemaScope(),
                                Query.text(req, "city"),
                                AdminModule.instant(req, "from", false),
                                AdminModule.instant(req, "to", true),
                                page.page(),
                                page.size()),
                        Dtos.ShowReportDto::of));
    }
}
