package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.testing.SliceKit;
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

    @RegisterExtension final SliceKit kit = WebApp.boot();

    /** A show in cinema 2 one day after the seed date, and the listing that holds it. */
    private final long show = WebApp.show(kit);

    private final String shows =
            "/api/movies/"
                    + WebApp.movieOf(kit, show)
                    + "/shows?city=bengaluru&date="
                    + kit.seedDate().plusDays(1);

    /** The ids of every show in the listing for the show's movie, city and date. */
    private List<Long> listedShows() {
        List<?> cinemas = (List<?>) kit.http().get(shows).expect(200).jsonField("cinemas");
        return cinemas.stream()
                .flatMap(c -> ((List<?>) ((Map<?, ?>) c).get("shows")).stream())
                .map(s -> ((Number) ((Map<?, ?>) s).get("id")).longValue())
                .toList();
    }

    private List<?> cityIds() {
        List<?> cities = kit.http().get("/api/cities").expect(200).json(List.class);
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
        assertEquals(List.of("bengaluru", "chennai", "mumbai"), cityIds());
        addCityBehindTheApi("goa");
        assertEquals(List.of("bengaluru", "chennai", "mumbai"), cityIds());
        kit.mutableClock().advance(Duration.ofSeconds(61));
        assertEquals(List.of("bengaluru", "chennai", "goa", "mumbai"), cityIds());
    }

    @Test
    void cachedReadsKeepTheirEtagAndCacheControl() {
        var first =
                kit.http()
                        .get("/api/cities")
                        .expect(200)
                        .header("Cache-Control", "public, max-age=60");
        kit.http()
                .get("/api/cities")
                .expect(200)
                .header("Cache-Control", "public, max-age=60")
                .header("ETag", first.header("ETag"));
        kit.http().get("/api/cities").header("If-None-Match", first.header("ETag")).expect(304);
    }

    @Test
    void anAdminCatalogWriteClearsTheCache() {
        assertEquals(List.of("bengaluru", "chennai", "mumbai"), cityIds());
        long movies =
                ((Number) kit.http().get("/api/movies").expect(200).jsonField("total")).longValue();
        addCityBehindTheApi("goa");
        kit.http()
                .post("/api/admin/cities")
                .header("Authorization", "Bearer " + kit.admin())
                .header("Idempotency-Key", kit.key())
                .json(Map.of("id", "pune", "name", "Pune", "timeZone", "Asia/Kolkata"))
                .expect(201);
        assertEquals(List.of("bengaluru", "chennai", "goa", "mumbai", "pune"), cityIds());
        kit.http()
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
        kit.http().get("/api/movies").expect(200).jsonEquals("total", (int) movies + 1);
    }

    @Test
    void anOwnerShowCancelClearsTheShowListing() {
        List<Long> before = listedShows();
        assertTrue(before.contains(show), before.toString());
        String owner = kit.owner("owner@example.com", WebApp.CINEMA_ID);
        kit.http()
                .post("/api/owner/shows/" + show + "/cancel")
                .header("Authorization", "Bearer " + owner)
                .header("Idempotency-Key", kit.key())
                .expect(200);
        List<Long> after = listedShows();
        assertFalse(after.contains(show), after.toString());
        assertEquals(before.size() - 1, after.size());
    }

    @Test
    void failedReadsAreNotCached() {
        kit.http().get("/api/movies/1/shows").expect(422);
        kit.http().get("/api/movies?size=0").expect(422);
        assertFalse(kit.http().get("/api/movies").expect(200).text().isEmpty());
    }
}
