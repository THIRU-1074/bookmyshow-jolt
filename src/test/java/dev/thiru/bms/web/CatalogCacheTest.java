package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.fakes.FakeWorld;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Public catalog reads come from a 60 s TtlCache (the same minute as their Cache-Control); admin
 * and owner catalog writes clear it. Data changed behind the API's back shows whether a read was
 * cached.
 */
class CatalogCacheTest {

    private static final String SHOWS = "/api/movies/1/shows?city=bengaluru&date=2026-11-22";

    @RegisterExtension final WebKit kit = new WebKit();

    private List<?> cityIds() {
        List<?> cities = kit.http.get("/api/cities").expect(200).json(List.class);
        return cities.stream().map(c -> ((Map<?, ?>) c).get("id")).toList();
    }

    private void addCityBehindTheApi(String id) {
        kit.db()
                .inTransaction(
                        tx ->
                                tx.update(
                                        "INSERT INTO city (id, name, time_zone)"
                                                + " VALUES (?, ?, 'Asia/Kolkata')",
                                        id,
                                        // Capitalised as real names are: cities list by name.
                                        Character.toUpperCase(id.charAt(0)) + id.substring(1)));
    }

    @Test
    void citiesAreCachedForAMinute() {
        assertEquals(List.of("bengaluru"), cityIds());
        addCityBehindTheApi("goa");
        assertEquals(List.of("bengaluru"), cityIds());
        kit.world.clock.advance(Duration.ofSeconds(61));
        assertEquals(List.of("bengaluru", "goa"), cityIds());
    }

    @Test
    void cachedReadsKeepTheirEtagAndCacheControl() {
        var first =
                kit.http
                        .get("/api/cities")
                        .expect(200)
                        .header("Cache-Control", "public, max-age=60");
        kit.http
                .get("/api/cities")
                .expect(200)
                .header("Cache-Control", "public, max-age=60")
                .header("ETag", first.header("ETag"));
        kit.http.get("/api/cities").header("If-None-Match", first.header("ETag")).expect(304);
    }

    @Test
    void anAdminCatalogWriteClearsTheCache() {
        assertEquals(List.of("bengaluru"), cityIds());
        long movies =
                ((Number) kit.http.get("/api/movies").expect(200).jsonField("total")).longValue();
        addCityBehindTheApi("goa");
        kit.http
                .post("/api/admin/cities")
                .header("Authorization", "Bearer " + kit.admin())
                .header("Idempotency-Key", kit.key())
                .json(Map.of("id", "pune", "name", "Pune", "timeZone", "Asia/Kolkata"))
                .expect(201);
        assertEquals(List.of("bengaluru", "goa", "pune"), cityIds());
        kit.http
                .post("/api/admin/movies")
                .header("Authorization", "Bearer " + kit.admin())
                .header("Idempotency-Key", kit.key())
                .json(
                        Map.of(
                                "title",
                                "Second Movie",
                                "languages",
                                List.of("en"),
                                "genres",
                                List.of("Drama"),
                                "certificate",
                                "U",
                                "durationMinutes",
                                100,
                                "releaseDate",
                                "2026-11-01",
                                "posterUrl",
                                "/p/second.png",
                                "description",
                                "Another."))
                .expect(201);
        kit.http.get("/api/movies").expect(200).jsonEquals("total", (int) movies + 1);
    }

    @Test
    void anOwnerShowCancelClearsTheShowListing() {
        kit.http.get(SHOWS).expect(200).jsonEquals("cinemas.0.shows.0.id", 9);
        String owner = kit.owner("owner@example.com", 2);
        kit.http
                .post("/api/owner/shows/" + FakeWorld.SHOW_ID + "/cancel")
                .header("Authorization", "Bearer " + owner)
                .header("Idempotency-Key", kit.key())
                .expect(200);
        Object cinemas = kit.http.get(SHOWS).expect(200).jsonField("cinemas");
        assertTrue(cinemas instanceof List<?> l && l.isEmpty(), String.valueOf(cinemas));
    }

    @Test
    void failedReadsAreNotCached() {
        kit.http.get("/api/movies/1/shows").expect(422);
        kit.http.get("/api/movies?size=0").expect(422);
        assertFalse(kit.http.get("/api/movies").expect(200).text().isEmpty());
    }
}
