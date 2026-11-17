package dev.thiru.bms.features.admin;

import static dev.thiru.bms.features.admin.AdminRoutes.body;
import static dev.thiru.bms.features.admin.AdminRoutes.created;
import static dev.thiru.bms.features.admin.AdminRoutes.layout;
import static dev.thiru.bms.features.admin.AdminRoutes.ok;

import dev.thiru.bms.api.generated.ScreenRequest;
import dev.thiru.bms.kernel.account.CinemaScope;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.SeatEvents;
import dev.thiru.bms.web.Principal;
import dev.thiru.bms.web.Query;
import in.jolt.Middleware;
import in.jolt.Request;
import in.jolt.RouteModule;
import in.jolt.Router;
import java.time.Clock;

/**
 * Theatre owner routes under {@code /api/owner} (SPEC 7, theatre owner). The gate admits
 * THEATRE_OWNER or ADMIN; every handler passes the caller's {@link CinemaScope} to the same use
 * cases the admin routes use, and those answer 404 for a cinema outside it. The links are read on
 * every request, so an unlink takes effect at once. Request and response shapes match the admin
 * routes.
 */
public final class OwnerRoutes implements RouteModule {

    private final AdminCatalog catalog;
    private final AdminReports reports;
    private final CancelShow cancelShow;
    private final Middleware idempotent;

    public OwnerRoutes(
            Db db, Clock clock, PaymentGateway gateway, SeatEvents events, Middleware idempotent) {
        this.catalog = new AdminCatalog(db, clock);
        this.reports = new AdminReports(db, clock);
        this.cancelShow = new CancelShow(db, gateway, events, clock);
        this.idempotent = idempotent;
    }

    /** Every cinema for an admin, else the cinemas linked to the caller. */
    static CinemaScope scope(Request req) {
        Principal caller = Principal.of(req);
        return CinemaScope.forCaller(caller.userId(), caller.isAdmin());
    }

    @Override
    public void register(Router r) {
        String p = "/api/owner";

        r.get(
                p + "/cinemas",
                (req, res) ->
                        ok(
                                res,
                                catalog.cinemas(scope(req)).stream()
                                        .map(AdminDtos::ownedCinema)
                                        .toList()));
        r.post(
                p + "/cinemas/:id/screens",
                idempotent,
                (req, res) -> {
                    ScreenRequest in = body(req, ScreenRequest.class);
                    created(
                            res,
                            AdminDtos.screen(
                                    catalog.createScreen(
                                            scope(req),
                                            Query.id(req, "id"),
                                            in.name(),
                                            layout(in))));
                });
        r.put(
                p + "/screens/:id",
                (req, res) -> {
                    ScreenRequest in = body(req, ScreenRequest.class);
                    ok(
                            res,
                            AdminDtos.screen(
                                    catalog.updateScreen(
                                            scope(req),
                                            Query.id(req, "id"),
                                            in.name(),
                                            layout(in))));
                });

        r.post(
                p + "/shows",
                idempotent,
                (req, res) -> AdminRoutes.createShow(req, res, catalog, scope(req)));
        r.post(
                p + "/shows/:id/cancel",
                idempotent,
                (req, res) -> AdminRoutes.cancelShow(req, res, cancelShow, scope(req)));

        r.get(
                p + "/reports/shows",
                (req, res) -> AdminRoutes.reportList(req, res, reports, scope(req)));
        r.get(
                p + "/reports/shows/:id",
                (req, res) ->
                        ok(
                                res,
                                AdminDtos.showReportDetail(
                                        reports.show(scope(req), Query.id(req, "id")))));
    }
}
