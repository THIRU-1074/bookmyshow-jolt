package dev.thiru.bms.features.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.thiru.bms.testing.RecordingEvents;
import dev.thiru.bms.testing.RecordingGateway;
import dev.thiru.bms.testing.SliceKit;
import in.jolt.test.JoltTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The {@code /api/owner} routes for an owner of cinema 2 (Whitefield Galaxy: screens 4 "Screen 1"
 * SMALL and 5 "Screen 2" LARGE). Cinema 1 (screen 1) belongs to someone else.
 */
class OwnerRoutesTest {

    final RecordingGateway gateway = new RecordingGateway();
    final RecordingEvents events = new RecordingEvents();

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
                                    new OwnerRoutes(
                                            k.db(),
                                            k.clock(),
                                            gateway,
                                            events,
                                            k.pipeline().idempotent())));

    private final String owner = kit.owner("owner@example.com", 2);
    private final AdminFixtures f = new AdminFixtures(kit.db(), kit.clock());

    /** A show body for movie 1 (en, 138 min) with prices for CLASSIC and PRIME. */
    static Map<String, Object> show(long screenId, Instant startsAt) {
        return Map.of(
                "movieId",
                1,
                "screenId",
                screenId,
                "startsAt",
                startsAt.toString(),
                "language",
                "en",
                "format",
                "2D",
                "prices",
                Map.of("CLASSIC", 15000, "PRIME", 25000));
    }

    private JoltTest.Call post(String path) {
        return post(owner, path);
    }

    private JoltTest.Call post(String token, String path) {
        return kit.http()
                .post(path)
                .header("Authorization", SliceKit.bearer(token))
                .header("Idempotency-Key", kit.key());
    }

    private JoltTest.Call get(String path) {
        return get(owner, path);
    }

    private JoltTest.Call get(String token, String path) {
        return kit.http().get(path).header("Authorization", SliceKit.bearer(token));
    }

    private Instant later(int days) {
        return kit.clock().instant().plus(Duration.ofDays(20 + days));
    }

    @Test
    void myCinemasListsTheLinkedCinemaWithScreensAndLayouts() {
        get("/api/owner/cinemas")
                .expect(200)
                .header("Cache-Control", "no-store")
                .jsonEquals("0.id", 2)
                .jsonEquals("0.name", "Whitefield Galaxy")
                .jsonEquals("0.screens.0.id", 4)
                .jsonEquals("0.screens.0.capacity", 94)
                .jsonEquals("0.screens.0.layout.rows.0.row", "A")
                .jsonEquals("0.screens.0.layout.rows.0.blocked.1", 12)
                .jsonEquals("0.screens.1.name", "Screen 2");
        assertEquals(1, get("/api/owner/cinemas").expect(200).json(List.class).size());
        assertEquals(6, get(kit.admin(), "/api/owner/cinemas").expect(200).json(List.class).size());
    }

    @Test
    void screensAreCreatedAndTheirLayoutsUpdated() {
        Number screen =
                (Number)
                        post("/api/owner/cinemas/2/screens")
                                .json(
                                        Map.of(
                                                "name",
                                                "Screen 9",
                                                "layout",
                                                AdminRoutesTest.layout(12)))
                                .expect(201)
                                .jsonEquals("cinemaId", 2)
                                .jsonEquals("capacity", 12)
                                .jsonField("id");
        kit.http()
                .put("/api/owner/screens/" + screen)
                .header("Authorization", SliceKit.bearer(owner))
                .json(Map.of("name", "Screen 9", "layout", AdminRoutesTest.layout(20)))
                .expect(200)
                .jsonEquals("capacity", 20);
        post("/api/owner/cinemas/2/screens")
                .json(Map.of("name", "Bad", "layout", AdminRoutesTest.layout(0)))
                .expect(422)
                .jsonEquals("code", "VALIDATION");
        kit.http()
                .post("/api/owner/cinemas/2/screens")
                .header("Authorization", SliceKit.bearer(owner))
                .json(Map.of("name", "No key", "layout", AdminRoutesTest.layout(12)))
                .expect(400);
        get("/api/owner/cinemas").expect(200).jsonEquals("0.screens.2.name", "Screen 9");
        // The seeded screen has future shows, so its layout is frozen.
        kit.http()
                .put("/api/owner/screens/5")
                .header("Authorization", SliceKit.bearer(owner))
                .json(Map.of("name", "Screen 2", "layout", AdminRoutesTest.layout(20)))
                .expect(409);
    }

    @Test
    void anythingOutsideMyCinemasIsNotFound() {
        long other = f.show(1, 1);
        post("/api/owner/cinemas/1/screens")
                .json(Map.of("name", "X", "layout", AdminRoutesTest.layout(12)))
                .expect(404)
                .jsonEquals("detail", "Cinema not found");
        post("/api/owner/cinemas/999/screens")
                .json(Map.of("name", "X", "layout", AdminRoutesTest.layout(12)))
                .expect(404)
                .jsonEquals("detail", "Cinema not found");
        kit.http()
                .put("/api/owner/screens/1")
                .header("Authorization", SliceKit.bearer(owner))
                .json(Map.of("name", "X", "layout", AdminRoutesTest.layout(12)))
                .expect(404)
                .jsonEquals("detail", "Screen not found");
        post("/api/owner/shows")
                .json(show(1, later(0)))
                .expect(404)
                .jsonEquals("detail", "Screen not found");
        post("/api/owner/shows/" + other + "/cancel")
                .expect(404)
                .jsonEquals("detail", "Show not found");
        get("/api/owner/reports/shows/" + other).expect(404).jsonEquals("detail", "Show not found");
        assertEquals("SCHEDULED", f.status("show", other));
        // The admin, through the same routes, reaches every cinema.
        post(kit.admin(), "/api/owner/shows/" + other + "/cancel").expect(200);
    }

    @Test
    void onlyOwnersAndAdminsGetIn() {
        String audience = kit.audience("ada@example.com");
        get(audience, "/api/owner/cinemas").expect(403);
        kit.http().get("/api/owner/cinemas").expect(401);
        get("/api/admin/stats").expect(403);
    }

    @Test
    void showsAreScheduledWithTheOverlapRuleAndCancelledWithRefunds() {
        Instant start = later(0);
        post("/api/owner/shows")
                .json(show(5, start))
                .expect(201)
                .jsonEquals("screenId", 5)
                .jsonEquals("status", "SCHEDULED");
        post("/api/owner/shows")
                .json(show(5, start.plus(Duration.ofMinutes(30))))
                .expect(422)
                .jsonEquals("errors.startsAt", "overlaps another show on this screen");
        post("/api/owner/shows")
                .json(Map.of())
                .expect(422)
                .jsonEquals("errors.movieId", "is required");

        long mine = f.show(5, 1);
        String paid = f.confirmed(f.user("ada@x.in"), mine, "A2", "A3");
        post("/api/owner/shows/" + mine + "/cancel")
                .expect(200)
                .jsonEquals("showId", (int) mine)
                .jsonEquals("refunded", 1);
        assertEquals("CANCELLED", f.status("show", mine));
        assertEquals("REFUNDED", f.status("booking", paid));
        assertEquals(1, gateway.refunds.size());
    }

    @Test
    void reportsShowTheSalesOfMyCinemasOnly() {
        long mine = f.show(5, 1);
        String paid = f.confirmed(f.user("ada@x.in"), mine, "A2", "A3");
        f.confirmed(f.user("bob@x.in"), f.show(1, 1), "A2");
        String day = AdminFixtures.seedDate(kit.db()).plusDays(1).toString();
        get("/api/owner/reports/shows?from=" + day + "&to=" + day + "&size=100")
                .expect(200)
                .jsonEquals("items.0.cinemaName", "Whitefield Galaxy")
                .jsonEquals(
                        "total",
                        f.count(
                                "SELECT count(*) FROM show WHERE screen_id IN (4, 5) AND starts_at >= ?"
                                        + " AND starts_at < ?",
                                dev.thiru.bms.ports.Db.millis(
                                        AdminFixtures.seedDate(kit.db())
                                                .plusDays(1)
                                                .atStartOfDay(java.time.ZoneOffset.UTC)
                                                .toInstant()),
                                dev.thiru.bms.ports.Db.millis(
                                        AdminFixtures.seedDate(kit.db())
                                                .plusDays(2)
                                                .atStartOfDay(java.time.ZoneOffset.UTC)
                                                .toInstant())));
        get("/api/owner/reports/shows/" + mine)
                .expect(200)
                .jsonEquals("booked", 2)
                .jsonEquals("revenue", (int) f.amount(paid))
                .jsonEquals("byCategory.0.category", "CLASSIC")
                .jsonEquals("byCategory.0.booked", 2);
        get("/api/owner/reports/shows/12345678").expect(404);
        get("/api/owner/reports/shows?from=nope").expect(422);
    }

    @Test
    void anUnlinkedOwnerLosesScopeAtOnceEvenWithTheOldToken() {
        long mine = f.show(5, 1);
        get("/api/owner/reports/shows/" + mine).expect(200);
        kit.http()
                .delete("/api/admin/cinemas/2/owners/" + kit.idOf("owner@example.com"))
                .header("Authorization", SliceKit.bearer(kit.admin()))
                .expect(200)
                .jsonEquals("roles.1", "THEATRE_OWNER");
        assertEquals(0, get("/api/owner/cinemas").expect(200).json(List.class).size());
        get("/api/owner/reports/shows/" + mine).expect(404);
        get("/api/owner/reports/shows").expect(200).jsonEquals("total", 0);
    }

    @Test
    void aRevokedOwnerLosesTheLinksAtOnceEvenWithTheOldToken() {
        kit.http()
                .delete(
                        "/api/admin/users/"
                                + kit.idOf("owner@example.com")
                                + "/roles/THEATRE_OWNER")
                .header("Authorization", SliceKit.bearer(kit.admin()))
                .expect(200);
        // The old token still says THEATRE_OWNER, so the gate admits it, but nothing is in scope.
        assertEquals(0, get("/api/owner/cinemas").expect(200).json(List.class).size());
        // A fresh token no longer has the role.
        get(kit.token(kit.idOf("owner@example.com")), "/api/owner/cinemas").expect(403);
    }
}
