package dev.thiru.bms.features.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.testing.SliceKit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The public catalog and seat map over HTTP, on the V2 seed (show 1: Paper Lanterns, 3D). */
class CatalogRoutesTest {

    private static final String PUBLIC = "public, max-age=60";

    @RegisterExtension
    final SliceKit kit =
            SliceKit.boot(
                    k ->
                            List.of(
                                    new CatalogRoutes(
                                            k.db(),
                                            k.clock(),
                                            k.policy(),
                                            k.pipeline().catalogCache())));

    private String shows(String movie) {
        return "/api/movies/" + movie + "/shows?city=bengaluru&date=" + kit.seedDate();
    }

    @Test
    void citiesAreCacheableWithAnEtag() {
        var res =
                kit.http()
                        .get("/api/cities")
                        .expect(200)
                        .jsonEquals("0.id", "bengaluru")
                        .jsonEquals("0.name", "Bengaluru")
                        .jsonEquals("0.timeZone", "Asia/Kolkata")
                        .jsonEquals("2.id", "mumbai")
                        .header("Cache-Control", PUBLIC);
        String etag = res.header("ETag");
        assertNotNull(etag);
        assertTrue(etag.startsWith("W/"), etag);
        kit.http().get("/api/cities").header("If-None-Match", etag).expect(304);
    }

    @Test
    void citiesComeFromTheCatalogCacheForAMinute() {
        assertEquals(3, cityCount());
        kit.db()
                .inTransaction(
                        tx ->
                                tx.update(
                                        "INSERT INTO city (id, name, time_zone)"
                                                + " VALUES ('pune', 'Pune', 'Asia/Kolkata')"));
        assertEquals(3, cityCount());
        kit.mutableClock().advance(Duration.ofSeconds(61));
        kit.http().get("/api/cities").expect(200).jsonEquals("3.id", "pune");
    }

    @Test
    void moviesArePagedFilteredAndCached() {
        kit.http()
                .get("/api/movies?page=1&size=5")
                .expect(200)
                .header("Cache-Control", PUBLIC)
                .jsonEquals("items.0.id", 12)
                .jsonEquals("items.0.title", "The Wedding Ledger")
                .jsonEquals("items.0.certificate", "UA")
                .jsonEquals("items.0.releaseDate", "2026-11-27")
                .jsonEquals("items.0.languages.0", "hi")
                .jsonEquals("items.0.posterUrl", "/img/posters/the-wedding-ledger.jpg")
                .jsonEquals("page", 1)
                .jsonEquals("size", 5)
                .jsonEquals("total", 12)
                .jsonEquals("totalPages", 3);
        kit.http().get("/api/movies?sort=title").expect(200).jsonEquals("items.0.id", 5);
        kit.http().get("/api/movies?sort=TITLE").expect(200).jsonEquals("items.0.id", 5);
        kit.http().get("/api/movies?sort=release").expect(200).jsonEquals("items.0.id", 12);
        kit.http().get("/api/movies?genre=Horror,Animation").expect(200).jsonEquals("total", 3);
        kit.http()
                .get("/api/movies?genre=Comedy&genre=Drama&language=ta")
                .expect(200)
                .jsonEquals("total", 1);
        kit.http().get("/api/movies?genre=Comedy&language=ta").expect(200).jsonEquals("total", 0);
        kit.http().get("/api/movies?genre=Romance&language=ta").expect(200).jsonEquals("total", 1);
        kit.http().get("/api/movies?certificate=a").expect(200).jsonEquals("items.0.id", 8);
        kit.http().get("/api/movies?format=3d").expect(200).jsonEquals("total", 2);
        kit.http().get("/api/movies?q=kaveri").expect(200).jsonEquals("items.0.id", 2);
        kit.http()
                .get("/api/movies?city=bengaluru&date=" + kit.seedDate().plusDays(14))
                .expect(200)
                .jsonEquals("total", 0);
        Object total =
                kit.http()
                        .get("/api/movies?city=bengaluru&date=" + kit.seedDate())
                        .expect(200)
                        .jsonField("total");
        assertTrue(((Number) total).intValue() > 0);
    }

    @Test
    void badQueryValuesAre422WithTheFieldNamed() {
        kit.http()
                .get("/api/movies?size=101")
                .expect(422)
                .jsonEquals("code", "VALIDATION")
                .jsonEquals("errors.size", "must be 1 to 100");
        kit.http()
                .get("/api/movies?page=0")
                .expect(422)
                .jsonEquals("errors.page", "must be 1 to 2147483647");
        kit.http()
                .get("/api/movies?certificate=X")
                .expect(422)
                .jsonEquals("errors.certificate", "unknown value 'X'");
        kit.http().get("/api/movies?certificate=U&certificate=X").expect(422);
        kit.http()
                .get("/api/movies?format=4D")
                .expect(422)
                .jsonEquals("errors.format", "unknown value '4D'");
        kit.http()
                .get("/api/movies?sort=rating")
                .expect(422)
                .jsonEquals("errors.sort", "must be release or title");
        kit.http()
                .get("/api/movies?date=tomorrow")
                .expect(422)
                .jsonEquals("errors.date", "must be a date like 2026-11-20");
        kit.http()
                .get("/api/movies?q=a")
                .expect(422)
                .jsonEquals("errors.q", "must be at least 2 characters");
        kit.http().get("/api/movies?city=nowhere").expect(404).jsonEquals("code", "NOT_FOUND");
    }

    @Test
    void movieDetailAndMissingMovie() {
        var res =
                kit.http()
                        .get("/api/movies/2")
                        .expect(200)
                        .header("Cache-Control", PUBLIC)
                        .jsonEquals("title", "Kaveri Nights")
                        .jsonEquals("genres.1", "Romance")
                        .jsonEquals("durationMinutes", 126);
        assertTrue(((String) res.jsonField("description")).startsWith("Two ferry"));
        kit.http().get("/api/movies/2").header("If-None-Match", res.header("ETag")).expect(304);
        kit.http()
                .get("/api/movies/999")
                .expect(404)
                .jsonEquals("code", "NOT_FOUND")
                .jsonEquals("status", 404);
        kit.http().get("/api/movies/abc").expect(404);
    }

    @Test
    void showTimesNeedACityAndGroupByCinema() {
        kit.http()
                .get("/api/movies/4/shows")
                .expect(422)
                .jsonEquals("code", "VALIDATION")
                .jsonEquals("errors.city", "is required");
        kit.http().get("/api/movies/4/shows?city=%20").expect(422);
        var res =
                kit.http()
                        .get(shows("4"))
                        .expect(200)
                        .header("Cache-Control", PUBLIC)
                        .jsonEquals("date", kit.seedDate().toString())
                        .jsonEquals("cinemas.0.id", 1)
                        .jsonEquals("cinemas.0.name", "Koramangala Cineplex")
                        .jsonEquals("cinemas.0.shows.0.id", 1)
                        .jsonEquals("cinemas.0.shows.0.format", "3D")
                        .jsonEquals("cinemas.0.shows.0.minPrice", 23000)
                        .jsonEquals("cinemas.0.shows.0.availability", "AVAILABLE")
                        .jsonEquals("cinemas.1.id", 2);
        assertNotNull(res.jsonField("cinemas.0.shows.0.startsAt"));
        assertTrue(
                String.valueOf(res.jsonField("cinemas.0.shows.0.localTime")).startsWith("10:00"));
        assertEmpty(kit.http().get(shows("4") + "&format=2D").expect(200).jsonField("cinemas"));
        kit.http().get(shows("4") + "&format=8K").expect(422).jsonHas("errors.format");
        kit.http()
                .get("/api/movies/4/shows?city=nowhere")
                .expect(404)
                .jsonEquals("code", "NOT_FOUND");
        kit.http().get("/api/movies/abc/shows?city=bengaluru").expect(404);
    }

    @Test
    void showTimesComeFromTheCacheUntilACatalogWriteOrAMinute() {
        kit.http().get(shows("4")).expect(200).jsonEquals("cinemas.0.shows.0.id", 1);
        kit.db().inTransaction(tx -> tx.update("UPDATE show SET status = 'CANCELLED'"));
        kit.http().get(shows("4")).expect(200).jsonEquals("cinemas.0.shows.0.id", 1);
        kit.mutableClock().advance(Duration.ofSeconds(61));
        assertEmpty(kit.http().get(shows("4")).expect(200).jsonField("cinemas"));
    }

    @Test
    void cinemasAndCinemaDetail() {
        kit.http()
                .get("/api/cinemas?city=bengaluru")
                .expect(200)
                .header("Cache-Control", PUBLIC)
                .jsonEquals("items.0.id", 1)
                .jsonEquals("items.0.cityId", "bengaluru")
                .jsonEquals("items.0.address", "80 Feet Road, Koramangala, Bengaluru")
                .jsonEquals("total", 2);
        kit.http()
                .get("/api/cinemas?page=2&size=4")
                .expect(200)
                .jsonEquals("total", 6)
                .jsonEquals("totalPages", 2);
        kit.http().get("/api/cinemas?size=0").expect(422).jsonHas("errors.size");
        kit.http()
                .get("/api/cinemas/1")
                .expect(200)
                .header("Cache-Control", PUBLIC)
                .jsonEquals("name", "Koramangala Cineplex")
                .jsonEquals("screens.0.name", "Audi 1")
                .jsonEquals("screens.0.capacity", 198)
                .jsonEquals("screens.2.name", "IMAX");
        kit.http().get("/api/cinemas/77").expect(404).jsonEquals("code", "NOT_FOUND");
    }

    @Test
    void showDetailHasPricesInPaise() {
        kit.http()
                .get("/api/shows/1")
                .expect(200)
                .header("Cache-Control", PUBLIC)
                .jsonEquals("id", 1)
                .jsonEquals("movie.id", 4)
                .jsonEquals("movie.title", "Paper Lanterns")
                .jsonEquals("movie.certificate", "U")
                .jsonEquals("movie.durationMinutes", 98)
                .jsonEquals("cinema.name", "Koramangala Cineplex")
                .jsonEquals("screen.name", "Audi 1")
                .jsonEquals("format", "3D")
                .jsonEquals("status", "SCHEDULED")
                .jsonEquals("prices.0.category", "CLASSIC")
                .jsonEquals("prices.0.name", "Classic")
                .jsonEquals("prices.0.amount", 23000)
                .jsonEquals("prices.1.amount", 33000);
        kit.http().get("/api/shows/99999").expect(404);
    }

    @Test
    void seatMapRevalidatesEveryTimeAndChangesWhenSeatsAreHeld() {
        var before =
                kit.http()
                        .get("/api/shows/1/seats")
                        .expect(200)
                        .header("Cache-Control", "no-cache")
                        .jsonEquals("showId", 1)
                        .jsonEquals("categories.1.category", "PRIME")
                        .jsonEquals("rows.0.row", "A")
                        .jsonEquals("rows.0.aisleAfter.1", 15)
                        .jsonEquals("rows.0.seats.0.id", "A1")
                        .jsonEquals("rows.0.seats.0.number", 1)
                        .jsonEquals("rows.0.seats.0.category", "CLASSIC")
                        .jsonEquals("rows.0.seats.0.status", "BLOCKED")
                        .jsonEquals("rows.0.seats.1.status", "AVAILABLE")
                        .jsonEquals("holdTtlSeconds", 420);
        String etag = before.header("ETag");
        assertNotNull(etag);
        kit.http().get("/api/shows/1/seats").header("If-None-Match", etag).expect(304);

        CatalogSqlTest.Seeds.hold(
                kit.db(), 1, kit.clock().instant().plusSeconds(60), List.of("A2"));
        var after =
                kit.http()
                        .get("/api/shows/1/seats")
                        .header("If-None-Match", etag)
                        .expect(200)
                        .jsonEquals("rows.0.seats.1.status", "HELD");
        assertNotEquals(etag, after.header("ETag"));
        assertNotEquals(before.jsonField("version"), after.jsonField("version"));
        kit.http().get("/api/shows/404404/seats").expect(404);
    }

    @Test
    void responsesKeepTheirFieldOrder() {
        String city = kit.http().get("/api/cities").expect(200).text();
        assertTrue(
                city.startsWith(
                        "[{\"id\":\"bengaluru\",\"name\":\"Bengaluru\",\"timeZone\":\"Asia/Kolkata\"}"),
                city);
        String seats = kit.http().get("/api/shows/1/seats").expect(200).text();
        assertTrue(seats.startsWith("{\"showId\":1,\"version\":"), seats);
        assertTrue(
                seats.contains(
                        "\"categories\":[{\"category\":\"CLASSIC\",\"name\":\"Classic\",\"amount\":23000}"),
                seats);
        assertTrue(seats.endsWith(",\"holdTtlSeconds\":420}"), seats);
        String times = kit.http().get(shows("4")).expect(200).text();
        assertTrue(times.startsWith("{\"date\":\"" + kit.seedDate() + "\",\"cinemas\":"), times);
        assertTrue(
                times.contains(
                        "{\"id\":1,\"name\":\"Koramangala Cineplex\",\"address\":\"80 Feet Road,"
                                + " Koramangala, Bengaluru\",\"shows\":[{\"id\":1,\"startsAt\":"),
                times);
        assertTrue(
                times.contains(
                        "\"format\":\"3D\",\"minPrice\":23000,\"availability\":\"AVAILABLE\"}"),
                times);
    }

    private int cityCount() {
        return kit.http().get("/api/cities").expect(200).json(List.class).size();
    }

    private static void assertEmpty(Object list) {
        assertTrue(list instanceof List<?> l && l.isEmpty(), String.valueOf(list));
    }
}
