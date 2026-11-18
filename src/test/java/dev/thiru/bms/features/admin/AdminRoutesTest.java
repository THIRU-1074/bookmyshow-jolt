package dev.thiru.bms.features.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.testing.RecordingEvents;
import dev.thiru.bms.testing.RecordingGateway;
import dev.thiru.bms.testing.SliceKit;
import in.jolt.RouteModule;
import in.jolt.test.JoltTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The {@code /api/admin} routes on the V2 seed, behind the real pipeline. Cinema 2 has screens 4
 * and 5; screen 1 (Audi 1, cinema 1) is LARGE.
 */
class AdminRoutesTest {

    final RecordingGateway gateway = new RecordingGateway();
    final RecordingEvents events = new RecordingEvents();
    final AtomicInteger cityLoads = new AtomicInteger();

    @RegisterExtension
    final SliceKit kit =
            SliceKit.boot(
                    k ->
                            List.of(
                                    new AdminRoutes(
                                            k.db(),
                                            k.clock(),
                                            gateway,
                                            events,
                                            k.live(),
                                            k.pipeline().idempotent()),
                                    // A stand-in public catalog read on the shared cache.
                                    (RouteModule)
                                            r ->
                                                    r.get(
                                                            "/api/cities",
                                                            (req, res) ->
                                                                    res.json(
                                                                            k.pipeline()
                                                                                    .catalogCache()
                                                                                    .get(
                                                                                            req,
                                                                                            cityLoads
                                                                                                    ::incrementAndGet)))));

    private final String admin = kit.admin();
    private final AdminFixtures f = new AdminFixtures(kit.db(), kit.clock());

    private JoltTest.Call post(String path) {
        return kit.http()
                .post(path)
                .header("Authorization", SliceKit.bearer(admin))
                .header("Idempotency-Key", kit.key());
    }

    private JoltTest.Call call(String method, String path) {
        return kit.http().call(method, path).header("Authorization", SliceKit.bearer(admin));
    }

    static Map<String, Object> layout(int seatsInRowA) {
        return Map.of(
                "categories",
                List.of(Map.of("code", "CLASSIC", "name", "Classic")),
                "rows",
                List.of(
                        Map.of(
                                "row",
                                "A",
                                "seats",
                                seatsInRowA,
                                "category",
                                "CLASSIC",
                                "aisleAfter",
                                List.of(),
                                "blocked",
                                List.of())));
    }

    @Test
    void cityCinemaScreenMovieAndShowLifecycle() {
        post("/api/admin/cities")
                .json(Map.of("id", "pune", "name", "Pune", "timeZone", "Asia/Kolkata"))
                .expect(201)
                .header("Cache-Control", "no-store")
                .jsonEquals("id", "pune")
                .jsonEquals("timeZone", "Asia/Kolkata");
        call("PUT", "/api/admin/cities/pune")
                .json(Map.of("name", "Poona"))
                .expect(200)
                .jsonEquals("name", "Poona");
        call("PUT", "/api/admin/cities/nowhere").json(Map.of("name", "X")).expect(404);

        Number cinema =
                (Number)
                        post("/api/admin/cinemas")
                                .json(Map.of("cityId", "pune", "name", "Ozone", "address", "MG Rd"))
                                .expect(201)
                                .jsonEquals("cityId", "pune")
                                .jsonField("id");
        call("PUT", "/api/admin/cinemas/" + cinema)
                .json(Map.of("name", "Ozone 2", "address", "FC Rd"))
                .expect(200)
                .jsonEquals("name", "Ozone 2")
                .jsonEquals("address", "FC Rd");

        Number screen =
                (Number)
                        post("/api/admin/cinemas/" + cinema + "/screens")
                                .json(Map.of("name", "Audi 1", "layout", layout(12)))
                                .expect(201)
                                .jsonEquals("capacity", 12)
                                .jsonEquals("layout.rows.0.row", "A")
                                .jsonField("id");
        call("PUT", "/api/admin/screens/" + screen)
                .json(Map.of("name", "Audi 1", "layout", layout(14)))
                .expect(200)
                .jsonEquals("capacity", 14);

        Number movie =
                (Number)
                        post("/api/admin/movies")
                                .json(
                                        Map.of(
                                                "title", "Last Train to Hampi",
                                                "languages", List.of("kn"),
                                                "genres", List.of("Drama"),
                                                "certificate", "u",
                                                "durationMinutes", 110,
                                                "releaseDate", "2026-11-01",
                                                "posterUrl", "/p/hampi.png",
                                                "description", "A train."))
                                .expect(201)
                                .jsonEquals("certificate", "U")
                                .jsonEquals("releaseDate", "2026-11-01")
                                .jsonField("id");
        call("PUT", "/api/admin/movies/" + movie)
                .json(
                        Map.of(
                                "title", "Hampi",
                                "languages", List.of("kn"),
                                "genres", List.of("Drama"),
                                "certificate", "UA",
                                "durationMinutes", 110,
                                "releaseDate", "2026-11-01",
                                "posterUrl", "/p/hampi.png"))
                .expect(200)
                .jsonEquals("id", movie)
                .jsonEquals("description", "");

        String startsAt = kit.clock().instant().plus(Duration.ofDays(2)).toString();
        Number show =
                (Number)
                        post("/api/admin/shows")
                                .json(
                                        Map.of(
                                                "movieId",
                                                movie,
                                                "screenId",
                                                screen,
                                                "startsAt",
                                                startsAt,
                                                "language",
                                                "kn",
                                                "format",
                                                "2D",
                                                "prices",
                                                Map.of("CLASSIC", 20000)))
                                .expect(201)
                                .jsonEquals("status", "SCHEDULED")
                                .jsonEquals("format", "2D")
                                .jsonEquals("prices.CLASSIC", 20000)
                                .jsonField("id");
        call("PUT", "/api/admin/shows/" + show)
                .json(
                        Map.of(
                                "movieId",
                                movie,
                                "screenId",
                                screen,
                                "startsAt",
                                startsAt,
                                "language",
                                "kn",
                                "format",
                                "IMAX",
                                "prices",
                                Map.of("CLASSIC", 30000)))
                .expect(200)
                .jsonEquals("format", "IMAX")
                .jsonEquals("prices.CLASSIC", 30000);

        // In use: cannot delete.
        call("DELETE", "/api/admin/movies/" + movie).expect(409).jsonEquals("code", "CONFLICT");
        call("DELETE", "/api/admin/cinemas/" + cinema).expect(409);
        call("DELETE", "/api/admin/screens/" + screen).expect(409);
        // A changed layout with a future show is refused.
        call("PUT", "/api/admin/screens/" + screen)
                .json(Map.of("name", "Audi 1", "layout", layout(16)))
                .expect(409)
                .jsonEquals("detail", "Layout cannot change while future shows exist");

        post("/api/admin/shows/" + show + "/cancel")
                .expect(200)
                .jsonEquals("showId", show)
                .jsonEquals("refunded", 0)
                .jsonEquals("cancelled", 0)
                .jsonEquals("holdsReleased", 0);
        // Idempotent: cancelling again is still 200.
        post("/api/admin/shows/" + show + "/cancel").expect(200);
        assertEquals("CANCELLED", f.status("show", show.longValue()));
        call("PUT", "/api/admin/shows/" + show)
                .json(
                        Map.of(
                                "movieId",
                                movie,
                                "screenId",
                                screen,
                                "startsAt",
                                startsAt,
                                "format",
                                "2D",
                                "language",
                                "kn",
                                "prices",
                                Map.of("CLASSIC", 30000)))
                .expect(409);

        Number spare =
                (Number)
                        post("/api/admin/movies")
                                .json(
                                        Map.of(
                                                "title", "Spare",
                                                "languages", List.of("en"),
                                                "genres", List.of("Drama"),
                                                "certificate", "A",
                                                "durationMinutes", 90,
                                                "releaseDate", "2026-11-01",
                                                "posterUrl", "https://x/p.png"))
                                .expect(201)
                                .jsonField("id");
        call("DELETE", "/api/admin/movies/" + spare).expect(204);
        call("DELETE", "/api/admin/movies/" + spare).expect(404);
        Number empty =
                (Number)
                        post("/api/admin/cinemas")
                                .json(Map.of("cityId", "pune", "name", "Empty", "address", "-"))
                                .expect(201)
                                .jsonField("id");
        call("DELETE", "/api/admin/cinemas/" + empty).expect(204);
    }

    @Test
    void conflictsAndUnknownIds() {
        post("/api/admin/cities")
                .json(Map.of("id", "bengaluru", "name", "B", "timeZone", "Asia/Kolkata"))
                .expect(409)
                .jsonEquals("detail", "City already exists");
        post("/api/admin/cities")
                .json(Map.of("id", "x", "name", "B", "timeZone", "Nowhere/Zone"))
                .expect(422)
                .jsonHas("errors.timeZone");
        post("/api/admin/cinemas")
                .json(Map.of("cityId", "bengaluru", "name", "whitefield galaxy", "address", "-"))
                .expect(409)
                .jsonEquals("detail", "Cinema name is taken in this city");
        post("/api/admin/cinemas")
                .json(Map.of("cityId", "nowhere", "name", "X", "address", "-"))
                .expect(404);
        post("/api/admin/cinemas/2/screens")
                .json(Map.of("name", "screen 1", "layout", layout(10)))
                .expect(409)
                .jsonEquals("detail", "Screen name is taken in this cinema");
        post("/api/admin/cinemas/999/screens")
                .json(Map.of("name", "X", "layout", layout(10)))
                .expect(404)
                .jsonEquals("detail", "Cinema not found");
        call("PUT", "/api/admin/screens/999")
                .json(Map.of("name", "X", "layout", layout(10)))
                .expect(404);
        call("DELETE", "/api/admin/screens/999").expect(404);
        call("DELETE", "/api/admin/cinemas/abc").expect(404);
        call("PUT", "/api/admin/cinemas/2").expect(400);
    }

    @Test
    void layoutErrorsUseNestedPaths() {
        Map<?, ?> errors =
                (Map<?, ?>)
                        post("/api/admin/cinemas/2/screens")
                                .json(Map.of("name", "Bad", "layout", layout(99)))
                                .expect(422)
                                .jsonEquals("code", "VALIDATION")
                                .jsonField("errors");
        assertTrue(errors.containsKey("rows[0].seats"), errors.toString());
        post("/api/admin/cinemas/2/screens")
                .json(Map.of("name", "No layout"))
                .expect(422)
                .jsonEquals("errors.layout", "is required");
    }

    @Test
    void movieAndShowValidation() {
        post("/api/admin/movies")
                .json(Map.of("title", "X", "certificate", "PG"))
                .expect(422)
                .jsonEquals("errors.certificate", "must be one of U, UA, A, S");
        post("/api/admin/shows")
                .json(Map.of())
                .expect(422)
                .jsonEquals("errors.movieId", "is required")
                .jsonEquals("errors.screenId", "is required")
                .jsonEquals("errors.startsAt", "is required")
                .jsonEquals("errors.format", "is required")
                .jsonEquals("errors.prices", "is required");
        String firstShowStart =
                kit.db()
                        .inTransaction(
                                tx ->
                                        tx.queryOne(
                                                "SELECT starts_at FROM show WHERE screen_id = 5"
                                                        + " ORDER BY starts_at LIMIT 1",
                                                rs ->
                                                        java.time.Instant.ofEpochMilli(
                                                                rs.getLong(1))))
                        .orElseThrow()
                        .toString();
        post("/api/admin/shows")
                .json(
                        Map.of(
                                "movieId",
                                1,
                                "screenId",
                                5,
                                "startsAt",
                                firstShowStart,
                                "language",
                                "en",
                                "format",
                                "2D",
                                "prices",
                                Map.of("CLASSIC", 15000, "PRIME", 25000)))
                .expect(422)
                .jsonEquals("errors.startsAt", "overlaps another show on this screen");
    }

    @Test
    void adminPostsNeedAnIdempotencyKeyAndReplay() {
        kit.http()
                .post("/api/admin/cities")
                .header("Authorization", SliceKit.bearer(admin))
                .json(Map.of("id", "pune", "name", "Pune", "timeZone", "Asia/Kolkata"))
                .expect(400)
                .jsonEquals("code", "IDEMPOTENCY");
        String key = kit.key();
        for (int i = 0; i < 2; i++) {
            kit.http()
                    .post("/api/admin/cities")
                    .header("Authorization", SliceKit.bearer(admin))
                    .header("Idempotency-Key", key)
                    .json(Map.of("id", "pune", "name", "Pune", "timeZone", "Asia/Kolkata"))
                    .expect(201);
        }
    }

    @Test
    void onlyAdminsGetIn() {
        String owner = kit.owner("owner@x.in", 2);
        kit.http()
                .get("/api/admin/stats")
                .header("Authorization", SliceKit.bearer(owner))
                .expect(403);
        kit.http().get("/api/admin/stats").expect(401);
    }

    @Test
    void cancellingAShowRefundsConfirmedBookingsAndPublishesAfterCommit() {
        long show = f.show(1, 1);
        String paid = f.confirmed(f.user("ada@x.in"), show, "A2", "A3");
        f.pending(f.user("bob@x.in"), show, "A5");
        f.hold(f.user("cy@x.in"), show, "B1");
        post("/api/admin/shows/" + show + "/cancel")
                .expect(200)
                .header("Cache-Control", "no-store")
                .jsonEquals("refunded", 1)
                .jsonEquals("cancelled", 1)
                .jsonEquals("holdsReleased", 1);
        assertEquals("REFUNDED", f.status("booking", paid));
        assertEquals(List.of("gw_p-3:" + f.amount(paid)), gateway.refunds);
        assertEquals(3, events.published.size());
        post("/api/admin/shows/999999/cancel").expect(404).jsonEquals("detail", "Show not found");
    }

    @Test
    void reportsAndStatsAfterRealBookings() {
        long show = f.show(1, 1);
        String paid = f.confirmed(f.user("ada@x.in"), show, "A2", "A3");
        f.hold(f.user("bob@x.in"), show, "B1");
        String day = f.seedDate(kit.db()).plusDays(1).toString();
        call("GET", "/api/admin/reports/shows?city=bengaluru&from=" + day + "&to=" + day)
                .expect(200)
                .header("Cache-Control", "no-store")
                .jsonEquals("items.0.showId", (int) show)
                .jsonEquals("items.0.booked", 2)
                .jsonEquals("items.0.revenue", (int) f.amount(paid))
                .jsonEquals("items.0.occupancyPercent", 1.0);
        call("GET", "/api/admin/reports/shows?size=1")
                .expect(200)
                .jsonEquals("size", 1)
                .jsonHas("totalPages");
        call("GET", "/api/admin/reports/shows?from=yesterday")
                .expect(422)
                .jsonEquals("errors.from", "must be an ISO instant or date");
        call("GET", "/api/admin/reports/shows/" + show)
                .expect(200)
                .jsonEquals("capacity", 198)
                .jsonEquals("byCategory.0.category", "CLASSIC")
                .jsonEquals("byCategory.0.booked", 2)
                .jsonEquals("byCategory.1.booked", 0);
        call("GET", "/api/admin/reports/shows/999999").expect(404);
        kit.live().subscribe(show);
        call("GET", "/api/admin/stats")
                .expect(200)
                .jsonEquals("activeHolds", 1)
                .jsonEquals("bookingsConfirmedToday", 1)
                .jsonEquals("sseStreams", 1);
    }

    @Test
    void successfulWritesClearThePublicCatalogCacheAndFailedOnesDoNot() {
        kit.http().get("/api/cities").expect(200);
        kit.http().get("/api/cities").expect(200);
        assertEquals(1, cityLoads.get());
        post("/api/admin/cities")
                .json(Map.of("id", "bengaluru", "name", "B", "timeZone", "Asia/Kolkata"))
                .expect(409);
        kit.http().get("/api/cities").expect(200);
        assertEquals(1, cityLoads.get());
        call("PUT", "/api/admin/cities/bengaluru").json(Map.of("name", "Bangalore")).expect(200);
        kit.http().get("/api/cities").expect(200);
        assertEquals(2, cityLoads.get());
    }

    // ---- users, roles and owner links ----

    @Test
    void usersAreListedWithRolesAndCinemasAndSearchable() {
        long ada = kit.createUser("ada@example.com", Role.AUDIENCE);
        kit.createUser("bob@example.com", Role.AUDIENCE);
        call("PUT", "/api/admin/cinemas/2/owners/" + ada).expect(200);
        call("GET", "/api/admin/users?size=2")
                .expect(200)
                .jsonEquals("total", 3)
                .jsonEquals("totalPages", 2)
                .jsonEquals("items.0.email", "admin@bms.test")
                .jsonEquals("items.0.roles.0", "ADMIN")
                .jsonEquals("items.0.roles.1", "AUDIENCE")
                .jsonEquals("items.1.email", "ada@example.com")
                .jsonEquals("items.1.roles.0", "AUDIENCE")
                .jsonEquals("items.1.roles.1", "THEATRE_OWNER")
                .jsonEquals("items.1.cinemaIds.0", 2);
        call("GET", "/api/admin/users?q=BOB")
                .expect(200)
                .jsonEquals("total", 1)
                .jsonEquals("items.0.email", "bob@example.com");
    }

    @Test
    void grantingAndRevokingTheatreOwnerClearsTheLinks() {
        long ada = kit.createUser("ada@example.com", Role.AUDIENCE);
        call("PUT", "/api/admin/users/" + ada + "/roles/theatre_owner")
                .expect(200)
                .jsonEquals("roles.1", "THEATRE_OWNER");
        call("PUT", "/api/admin/cinemas/2/owners/" + ada).expect(200).jsonEquals("cinemaIds.0", 2);
        call("PUT", "/api/admin/cinemas/1/owners/" + ada)
                .expect(200)
                .jsonEquals("cinemaIds.0", 1)
                .jsonEquals("cinemaIds.1", 2);
        JoltTest.Result revoked =
                call("DELETE", "/api/admin/users/" + ada + "/roles/THEATRE_OWNER").expect(200);
        assertEquals(List.of("AUDIENCE"), revoked.jsonField("roles"));
        assertEquals(List.of(), revoked.jsonField("cinemaIds"));
        assertEquals(0, f.count("SELECT count(*) FROM cinema_owner WHERE user_id = ?", ada));
    }

    @Test
    void unlinkKeepsTheRole() {
        long ada = kit.createUser("ada@example.com", Role.AUDIENCE);
        call("PUT", "/api/admin/cinemas/2/owners/" + ada).expect(200);
        call("DELETE", "/api/admin/cinemas/2/owners/" + ada)
                .expect(200)
                .jsonEquals("roles.1", "THEATRE_OWNER")
                .jsonEquals("cinemaIds", List.of());
    }

    @Test
    void errorsForBadRolesUnknownIdsAudienceAndSelfDemotion() {
        long ada = kit.createUser("ada@example.com", Role.AUDIENCE);
        call("PUT", "/api/admin/users/" + ada + "/roles/ROOT")
                .expect(422)
                .jsonEquals("code", "VALIDATION")
                .jsonEquals("errors.role", "must be one of AUDIENCE, THEATRE_OWNER, ADMIN");
        call("DELETE", "/api/admin/users/" + ada + "/roles/AUDIENCE")
                .expect(422)
                .jsonEquals("errors.role", "AUDIENCE cannot be removed");
        call("PUT", "/api/admin/users/999/roles/THEATRE_OWNER").expect(404);
        call("PUT", "/api/admin/cinemas/999/owners/" + ada).expect(404);
        call("PUT", "/api/admin/cinemas/2/owners/999").expect(404);
        call("DELETE", "/api/admin/cinemas/999/owners/" + ada).expect(404);
        call("DELETE", "/api/admin/users/" + kit.idOf(SliceKit.ADMIN_EMAIL) + "/roles/admin")
                .expect(409)
                .jsonEquals("code", "CONFLICT")
                .jsonEquals("detail", "You cannot remove your own ADMIN role");
    }

    @Test
    void anAdminMayDemoteAnotherAdmin() {
        long ada = kit.createUser("ada@example.com", Role.AUDIENCE);
        call("PUT", "/api/admin/users/" + ada + "/roles/ADMIN").expect(200);
        kit.http()
                .get("/api/admin/stats")
                .header("Authorization", SliceKit.bearer(kit.token(ada)))
                .expect(200);
        call("DELETE", "/api/admin/users/" + ada + "/roles/ADMIN")
                .expect(200)
                .jsonEquals("roles.0", "AUDIENCE");
    }
}
