package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.fakes.FakeWorld;
import in.jolt.test.JoltTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class AdminApiTest {

    @RegisterExtension final WebKit kit = new WebKit();
    private final String admin = kit.admin();

    private JoltTest.Call post(String path) {
        return kit.http
                .post(path)
                .header("Authorization", WebKit.bearer(admin))
                .header("Idempotency-Key", kit.key());
    }

    private JoltTest.Call put(String path) {
        return kit.http.put(path).header("Authorization", WebKit.bearer(admin));
    }

    private static Map<String, Object> layout(int seatsInRowA) {
        return Map.of(
                "categories", List.of(Map.of("code", "CLASSIC", "name", "Classic")),
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
                .jsonEquals("id", "pune");
        put("/api/admin/cities/pune")
                .json(Map.of("name", "Poona"))
                .expect(200)
                .jsonEquals("name", "Poona");

        Number cinema =
                (Number)
                        post("/api/admin/cinemas")
                                .json(Map.of("cityId", "pune", "name", "Ozone", "address", "MG Rd"))
                                .expect(201)
                                .jsonField("id");
        put("/api/admin/cinemas/" + cinema)
                .json(Map.of("name", "Ozone 2", "address", "FC Rd"))
                .expect(200)
                .jsonEquals("name", "Ozone 2");

        Number screen =
                (Number)
                        post("/api/admin/cinemas/" + cinema + "/screens")
                                .json(Map.of("name", "Audi 1", "layout", layout(12)))
                                .expect(201)
                                .jsonEquals("capacity", 12)
                                .jsonField("id");
        put("/api/admin/screens/" + screen)
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
                                                "certificate", "U",
                                                "durationMinutes", 110,
                                                "releaseDate", "2026-11-01",
                                                "posterUrl", "/p/hampi.png",
                                                "description", "A train."))
                                .expect(201)
                                .jsonEquals("certificate", "U")
                                .jsonField("id");

        String startsAt = FakeWorld.T0.plus(Duration.ofDays(2)).toString();
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
                                .jsonEquals("prices.CLASSIC", 20000)
                                .jsonField("id");

        // In use: cannot delete.
        kit.http
                .delete("/api/admin/movies/" + movie)
                .header("Authorization", WebKit.bearer(admin))
                .expect(409);
        kit.http
                .delete("/api/admin/cinemas/" + cinema)
                .header("Authorization", WebKit.bearer(admin))
                .expect(409);

        post("/api/admin/shows/" + show + "/cancel")
                .expect(200)
                .jsonEquals("showId", show)
                .jsonEquals("refunded", 0);
        // Idempotent: cancelling again is still 200.
        post("/api/admin/shows/" + show + "/cancel").expect(200);
        kit.http.get("/api/shows/" + show).expect(200).jsonEquals("status", "CANCELLED");
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
                .jsonHas("errors.certificate");
        post("/api/admin/shows")
                .json(Map.of())
                .expect(422)
                .jsonEquals("errors.movieId", "is required")
                .jsonEquals("errors.screenId", "is required")
                .jsonEquals("errors.startsAt", "is required")
                .jsonEquals("errors.format", "is required")
                .jsonEquals("errors.prices", "is required");
        post("/api/admin/shows")
                .json(
                        Map.of(
                                "movieId",
                                1,
                                "screenId",
                                FakeWorld.SCREEN_ID,
                                "startsAt",
                                FakeWorld.T0.plus(Duration.ofDays(3)).toString(),
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
    void adminPostsNeedAnIdempotencyKey() {
        kit.http
                .post("/api/admin/cities")
                .header("Authorization", WebKit.bearer(admin))
                .json(Map.of("id", "pune", "name", "Pune", "timeZone", "Asia/Kolkata"))
                .expect(400)
                .jsonEquals("code", "IDEMPOTENCY");
    }

    @Test
    void cancellingAShowRefundsConfirmedBookings() {
        String ada = kit.user("ada@example.com");
        kit.confirmedBooking(ada);
        post("/api/admin/shows/9/cancel").expect(200).jsonEquals("refunded", 1);
        kit.http
                .get("/api/bookings?status=REFUNDED")
                .header("Authorization", WebKit.bearer(ada))
                .expect(200)
                .jsonEquals("items.0.refundAmount", 30000);
    }

    @Test
    void reportsAndStats() {
        String ada = kit.user("ada@example.com");
        kit.confirmedBooking(ada);
        kit.http
                .get("/api/admin/reports/shows?city=bengaluru&from=2026-11-19&to=2026-11-30")
                .header("Authorization", WebKit.bearer(admin))
                .expect(200)
                .jsonEquals("items.0.showId", 9)
                .jsonEquals("items.0.booked", 2)
                .jsonEquals("items.0.revenue", 30000);
        kit.http
                .get("/api/admin/reports/shows")
                .header("Authorization", WebKit.bearer(admin))
                .expect(200)
                .jsonEquals("total", 1);
        kit.http
                .get("/api/admin/reports/shows?from=yesterday")
                .header("Authorization", WebKit.bearer(admin))
                .expect(422)
                .jsonHas("errors.from");
        kit.http
                .get("/api/admin/reports/shows/9")
                .header("Authorization", WebKit.bearer(admin))
                .expect(200)
                .jsonEquals("capacity", 23)
                .jsonEquals("byCategory.0.category", "CLASSIC");
        kit.http
                .get("/api/admin/stats")
                .header("Authorization", WebKit.bearer(admin))
                .expect(200)
                .jsonEquals("bookingsConfirmedToday", 1)
                .jsonEquals("sseStreams", 0);
    }
}
