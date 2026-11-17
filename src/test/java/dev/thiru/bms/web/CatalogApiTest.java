package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CatalogApiTest {

    @RegisterExtension final WebKit kit = new WebKit();

    @Test
    void citiesAreCacheableWithAnEtag() {
        var res =
                kit.http
                        .get("/api/cities")
                        .expect(200)
                        .jsonEquals("0.id", "bengaluru")
                        .jsonEquals("0.timeZone", "Asia/Kolkata")
                        .header("Cache-Control", "public, max-age=60");
        String etag = res.header("ETag");
        assertNotNull(etag);
        kit.http.get("/api/cities").header("If-None-Match", etag).expect(304);
    }

    @Test
    void moviesArePagedAndFiltered() {
        kit.http
                .get("/api/movies?page=1&size=5")
                .expect(200)
                .jsonEquals("items.0.title", "Kaveri Nights")
                .jsonEquals("items.0.certificate", "UA")
                .jsonEquals("page", 1)
                .jsonEquals("size", 5)
                .jsonEquals("total", 1)
                .jsonEquals("totalPages", 1);
        kit.http.get("/api/movies?genre=Comedy,Horror").expect(200).jsonEquals("total", 0);
        kit.http
                .get("/api/movies?genre=Comedy,Drama&language=ta")
                .expect(200)
                .jsonEquals("total", 1);
        kit.http
                .get("/api/movies?genre=Comedy&genre=Drama&language=ta")
                .expect(200)
                .jsonEquals("total", 1);
        kit.http
                .get("/api/movies?genre=Comedy&genre=Horror,Drama")
                .expect(200)
                .jsonEquals("total", 1);
        kit.http.get("/api/movies?certificate=U&certificate=X").expect(422);
        kit.http
                .get("/api/movies?city=bengaluru&date=2026-11-22")
                .expect(200)
                .jsonEquals("total", 1);
    }

    @Test
    void badQueryValuesAre422WithTheFieldNamed() {
        kit.http
                .get("/api/movies?size=101")
                .expect(422)
                .jsonEquals("code", "VALIDATION")
                .jsonEquals("errors.size", "must be 1 to 100");
        kit.http
                .get("/api/movies?page=0")
                .expect(422)
                .jsonEquals("errors.page", "must be 1 to 2147483647");
        kit.http.get("/api/movies?certificate=X").expect(422).jsonHas("errors.certificate");
        kit.http.get("/api/movies?format=4D").expect(422).jsonHas("errors.format");
        kit.http.get("/api/movies?sort=rating").expect(422).jsonHas("errors.sort");
        kit.http.get("/api/movies?date=tomorrow").expect(422).jsonHas("errors.date");
        kit.http.get("/api/movies?q=a").expect(422).jsonHas("errors.q");
    }

    @Test
    void movieDetailAndMissingMovie() {
        kit.http.get("/api/movies/1").expect(200).jsonEquals("description", "d");
        kit.http
                .get("/api/movies/999")
                .expect(404)
                .jsonEquals("code", "NOT_FOUND")
                .jsonEquals("status", 404);
        kit.http.get("/api/movies/abc").expect(404);
    }

    @Test
    void showTimesNeedACityAndGroupByCinema() {
        kit.http.get("/api/movies/1/shows").expect(422).jsonEquals("errors.city", "is required");
        var res =
                kit.http
                        .get("/api/movies/1/shows?city=bengaluru&date=2026-11-22")
                        .expect(200)
                        .jsonEquals("date", "2026-11-22")
                        .jsonEquals("cinemas.0.name", "PVR Orion")
                        .jsonEquals("cinemas.0.shows.0.id", 9)
                        .jsonEquals("cinemas.0.shows.0.format", "2D")
                        .jsonEquals("cinemas.0.shows.0.minPrice", 15000)
                        .jsonEquals("cinemas.0.shows.0.availability", "AVAILABLE");
        assertNotNull(res.jsonField("cinemas.0.shows.0.localTime"));
    }

    @Test
    void cinemasAndCinemaDetail() {
        kit.http
                .get("/api/cinemas?city=bengaluru")
                .expect(200)
                .jsonEquals("items.0.id", 2)
                .jsonEquals("items.0.cityId", "bengaluru");
        kit.http
                .get("/api/cinemas/2")
                .expect(200)
                .jsonEquals("screens.0.name", "Screen 1")
                .jsonEquals("screens.0.capacity", 23);
        kit.http.get("/api/cinemas/77").expect(404);
    }

    @Test
    void showDetailHasPricesInPaise() {
        kit.http
                .get("/api/shows/9")
                .expect(200)
                .jsonEquals("movie.title", "Kaveri Nights")
                .jsonEquals("cinema.name", "PVR Orion")
                .jsonEquals("screen.name", "Screen 1")
                .jsonEquals("format", "2D")
                .jsonEquals("status", "SCHEDULED")
                .jsonEquals("prices.0.category", "CLASSIC")
                .jsonEquals("prices.0.amount", 15000);
    }

    @Test
    void seatMapRevalidatesEveryTimeAndChangesWhenSeatsAreHeld() {
        var before =
                kit.http
                        .get("/api/shows/9/seats")
                        .expect(200)
                        .header("Cache-Control", "no-cache")
                        .jsonEquals("showId", 9)
                        .jsonEquals("rows.0.row", "A")
                        .jsonEquals("rows.0.seats.0.status", "BLOCKED")
                        .jsonEquals("rows.0.seats.1.status", "AVAILABLE")
                        .jsonEquals("holdTtlSeconds", 420);
        String etag = before.header("ETag");
        kit.http.get("/api/shows/9/seats").header("If-None-Match", etag).expect(304);

        kit.hold(kit.user("holder@example.com"), "A2");
        var after =
                kit.http
                        .get("/api/shows/9/seats")
                        .header("If-None-Match", etag)
                        .expect(200)
                        .jsonEquals("rows.0.seats.1.status", "HELD");
        assertEquals(false, etag.equals(after.header("ETag")));
        kit.http.get("/api/shows/404/seats").expect(404);
    }
}
