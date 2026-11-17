package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.thiru.bms.application.fakes.FakeWorld;
import in.jolt.test.JoltTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The /api/owner routes for an owner of the seeded cinema 2 (screen 5, show 9). */
class OwnerApiTest {

    private final WebKit kit = new WebKit();
    private final String owner = kit.owner("owner@example.com", 2);

    static Map<String, Object> layout(int seats) {
        return Map.of(
                "categories",
                List.of(Map.of("code", "CLASSIC", "name", "Classic")),
                "rows",
                List.of(
                        Map.of(
                                "row",
                                "A",
                                "seats",
                                seats,
                                "category",
                                "CLASSIC",
                                "aisleAfter",
                                List.of(),
                                "blocked",
                                List.of())));
    }

    /** A show body for the seeded movie on a screen with the seeded CLASSIC and PRIME prices. */
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
        return kit.http
                .post(path)
                .header("Authorization", WebKit.bearer(owner))
                .header("Idempotency-Key", kit.key());
    }

    private JoltTest.Call get(String path) {
        return kit.http.get(path).header("Authorization", WebKit.bearer(owner));
    }

    @Test
    void myCinemasListsTheLinkedCinemaWithScreensAndLayouts() {
        get("/api/owner/cinemas")
                .expect(200)
                .jsonEquals("0.id", 2)
                .jsonEquals("0.name", "PVR Orion")
                .jsonEquals("0.screens.0.id", (int) FakeWorld.SCREEN_ID)
                .jsonEquals("0.screens.0.capacity", 23)
                .jsonEquals("0.screens.0.layout.rows.0.row", "A");
    }

    @Test
    void screensAreCreatedAndTheirLayoutsUpdated() {
        Number screen =
                (Number)
                        post("/api/owner/cinemas/2/screens")
                                .json(Map.of("name", "Audi 9", "layout", layout(12)))
                                .expect(201)
                                .jsonEquals("cinemaId", 2)
                                .jsonEquals("capacity", 12)
                                .jsonField("id");
        kit.http
                .put("/api/owner/screens/" + screen)
                .header("Authorization", WebKit.bearer(owner))
                .json(Map.of("name", "Audi 9", "layout", layout(20)))
                .expect(200)
                .jsonEquals("capacity", 20);
        post("/api/owner/cinemas/2/screens")
                .json(Map.of("name", "Bad", "layout", layout(0)))
                .expect(422)
                .jsonEquals("code", "VALIDATION");
        kit.http
                .post("/api/owner/cinemas/2/screens")
                .header("Authorization", WebKit.bearer(owner))
                .json(Map.of("name", "No key", "layout", layout(12)))
                .expect(400);
        get("/api/owner/cinemas").expect(200).jsonEquals("0.screens.1.name", "Audi 9");
    }

    @Test
    void showsAreScheduledWithTheOverlapRuleAndCancelledWithRefunds() {
        Instant start = FakeWorld.T0.plus(Duration.ofDays(5));
        post("/api/owner/shows")
                .json(show(FakeWorld.SCREEN_ID, start))
                .expect(201)
                .jsonEquals("screenId", (int) FakeWorld.SCREEN_ID)
                .jsonEquals("status", "SCHEDULED");
        post("/api/owner/shows")
                .json(show(FakeWorld.SCREEN_ID, start.plus(Duration.ofMinutes(30))))
                .expect(422)
                .jsonEquals("code", "VALIDATION");

        String audience = kit.user("ada@example.com");
        kit.confirmedBooking(audience);
        post("/api/owner/shows/" + FakeWorld.SHOW_ID + "/cancel")
                .expect(200)
                .jsonEquals("refunded", 1);
        assertEquals("CANCELLED", kit.world.show(FakeWorld.SHOW_ID).status().name());
    }

    @Test
    void reportsShowTheSalesOfMyCinemas() {
        String audience = kit.user("ada@example.com");
        kit.confirmedBooking(audience);
        get("/api/owner/reports/shows")
                .expect(200)
                .jsonEquals("total", 1)
                .jsonEquals("items.0.showId", (int) FakeWorld.SHOW_ID)
                .jsonEquals("items.0.booked", 2)
                .jsonEquals("items.0.revenue", 30000);
        get("/api/owner/reports/shows/" + FakeWorld.SHOW_ID)
                .expect(200)
                .jsonEquals("booked", 2)
                .jsonEquals("byCategory.0.category", "CLASSIC")
                .jsonEquals("byCategory.0.booked", 2);
        get("/api/owner/reports/shows/12345").expect(404);
    }

    @Test
    void anUnlinkedOwnerLosesScopeAtOnceEvenWithTheOldToken() {
        String admin = kit.admin();
        kit.http
                .delete("/api/admin/cinemas/2/owners/" + kit.idOf("owner@example.com"))
                .header("Authorization", WebKit.bearer(admin))
                .expect(200)
                .jsonEquals("roles.1", "THEATRE_OWNER");
        assertEquals(0, get("/api/owner/cinemas").expect(200).json(List.class).size());
        get("/api/owner/reports/shows/" + FakeWorld.SHOW_ID).expect(404);
    }
}
