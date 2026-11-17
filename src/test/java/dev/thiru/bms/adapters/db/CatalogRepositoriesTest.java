package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.domain.Certificate;
import dev.thiru.bms.domain.Cinema;
import dev.thiru.bms.domain.Movie;
import dev.thiru.bms.domain.Page;
import dev.thiru.bms.domain.Screen;
import dev.thiru.bms.domain.Show;
import dev.thiru.bms.domain.ShowFormat;
import dev.thiru.bms.domain.ShowStatus;
import dev.thiru.bms.domain.port.CatalogRepositories.MovieQuery;
import dev.thiru.bms.domain.port.CatalogRepositories.ShowListing;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogRepositoriesTest extends DbTest {

    private static final Instant FAR_PAST = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("2100-01-01T00:00:00Z");

    private static MovieQuery all(MovieQuery.Sort sort) {
        return new MovieQuery(null, null, null, null, Set.of(), Set.of(), Set.of(), Set.of(), sort);
    }

    private static MovieQuery inCity(String city, Set<ShowFormat> formats) {
        return new MovieQuery(
                city,
                FAR_PAST,
                FAR_FUTURE,
                null,
                Set.of(),
                Set.of(),
                Set.of(),
                formats,
                MovieQuery.Sort.TITLE);
    }

    @Test
    void citiesAreListedByNameWithTheirZone() {
        var cities = tx(r -> r.cities().findAll());
        assertEquals(
                List.of("bengaluru", "chennai", "mumbai"),
                cities.stream().map(c -> c.id()).toList());
        assertEquals("Asia/Kolkata", cities.get(0).timeZone().getId());
        assertTrue(tx(r -> r.cities().findById("chennai")).isPresent());
        assertTrue(tx(r -> r.cities().findById("nowhere")).isEmpty());
    }

    @Test
    void cinemasPageIsOneBasedAndFilteredByCity() {
        Page<Cinema> first = tx(r -> r.cinemas().page("bengaluru", 1, 1));
        Page<Cinema> second = tx(r -> r.cinemas().page("bengaluru", 2, 1));
        assertEquals(2, first.total());
        assertEquals(1, first.items().size());
        assertEquals(1, second.items().size());
        assertTrue(!first.items().get(0).equals(second.items().get(0)));
        assertTrue(first.items().stream().allMatch(c -> c.cityId().equals("bengaluru")));
        assertTrue(tx(r -> r.cinemas().page("bengaluru", 3, 1)).items().isEmpty());
        long everywhere = tx(r -> r.cinemas().page(null, 1, 100)).total();
        assertEquals(
                db.queryOne("SELECT count(*) FROM cinema", r -> r.getLong(1)).orElseThrow(),
                everywhere);
        assertTrue(tx(r -> r.cinemas().findById(1)).isPresent());
        assertTrue(tx(r -> r.cinemas().findById(9999)).isEmpty());
    }

    @Test
    void screensCarryAParsedLayout() {
        Screen s = tx(r -> r.screens().findByCinema(1)).get(0);
        long capacity =
                db.queryOne("SELECT capacity FROM screen WHERE id = ?", r -> r.getLong(1), s.id())
                        .orElseThrow();
        assertEquals(capacity, s.capacity());
        assertEquals(s, tx(r -> r.screens().findById(s.id())).orElseThrow());
        assertTrue(tx(r -> r.screens().findById(9999)).isEmpty());
    }

    @Test
    void movieByIdLoadsLanguagesAndGenres() {
        Movie m = tx(r -> r.movies().findById(3)).orElseThrow();
        assertEquals("Last Train to Hampi", m.title());
        assertEquals(List.of("en", "hi", "kn"), m.languages());
        assertEquals(List.of("Drama", "Thriller"), m.genres());
        assertEquals(Certificate.UA, m.certificate());
        assertTrue(tx(r -> r.movies().findById(999)).isEmpty());
    }

    @Test
    void searchSortsAndPages() {
        Page<Movie> byTitle = tx(r -> r.movies().search(all(MovieQuery.Sort.TITLE), 1, 5));
        assertEquals(12, byTitle.total());
        assertEquals(5, byTitle.items().size());
        List<String> titles = byTitle.items().stream().map(Movie::title).toList();
        assertEquals(titles.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(), titles);

        Page<Movie> byRelease = tx(r -> r.movies().search(all(MovieQuery.Sort.RELEASE), 1, 12));
        assertEquals(
                byRelease.items().stream()
                        .sorted(Comparator.comparing(Movie::releaseDate).reversed())
                        .map(Movie::releaseDate)
                        .toList(),
                byRelease.items().stream().map(Movie::releaseDate).toList());

        assertEquals(
                2, tx(r -> r.movies().search(all(MovieQuery.Sort.TITLE), 3, 5)).items().size());
    }

    @Test
    void searchFiltersByTextGenreLanguageAndCertificate() {
        MovieQuery text =
                new MovieQuery(
                        null,
                        null,
                        null,
                        "TIGERS",
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        MovieQuery.Sort.TITLE);
        assertEquals(
                List.of("Little Tigers of Lalbagh"),
                tx(r -> r.movies().search(text, 1, 10)).items().stream()
                        .map(Movie::title)
                        .toList());

        // LIKE wildcards in user input are literal.
        MovieQuery wildcard =
                new MovieQuery(
                        null,
                        null,
                        null,
                        "%",
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        MovieQuery.Sort.TITLE);
        assertEquals(0, tx(r -> r.movies().search(wildcard, 1, 10)).total());

        MovieQuery facets =
                new MovieQuery(
                        null,
                        null,
                        null,
                        null,
                        Set.of("Animation"),
                        Set.of("ta"),
                        Set.of(Certificate.U),
                        Set.of(),
                        MovieQuery.Sort.TITLE);
        assertEquals(
                List.of("Paper Lanterns"),
                tx(r -> r.movies().search(facets, 1, 10)).items().stream()
                        .map(Movie::title)
                        .toList());
    }

    @Test
    void searchWithACityOnlyReturnsMoviesWithScheduledShowsInTheWindow() {
        long scheduled = tx(r -> r.movies().search(inCity("bengaluru", Set.of()), 1, 50)).total();
        assertTrue(scheduled > 0);

        assertEquals(
                0,
                tx(r ->
                                r.movies()
                                        .search(
                                                new MovieQuery(
                                                        "bengaluru",
                                                        FAR_PAST,
                                                        FAR_PAST.plus(Duration.ofDays(1)),
                                                        null,
                                                        Set.of(),
                                                        Set.of(),
                                                        Set.of(),
                                                        Set.of(),
                                                        MovieQuery.Sort.TITLE),
                                                1,
                                                50))
                        .total());

        // Cancelled shows do not count: cancel everything in the city and nothing is left.
        db.update(
                "UPDATE show SET status = 'CANCELLED' WHERE screen_id IN (SELECT s.id FROM screen s"
                        + " JOIN cinema c ON c.id = s.cinema_id WHERE c.city_id = 'bengaluru')");
        assertEquals(0, tx(r -> r.movies().search(inCity("bengaluru", Set.of()), 1, 50)).total());
        assertTrue(tx(r -> r.movies().search(inCity("chennai", Set.of()), 1, 50)).total() > 0);
    }

    @Test
    void formatFilterNarrowsMoviesToThoseShowingInThatFormat() {
        long imax =
                tx(r -> r.movies().search(inCity("bengaluru", Set.of(ShowFormat.IMAX)), 1, 50))
                        .total();
        long every = tx(r -> r.movies().search(inCity("bengaluru", Set.of()), 1, 50)).total();
        long expected =
                db.queryOne(
                                "SELECT count(DISTINCT s.movie_id) FROM show s JOIN screen sc ON"
                                        + " sc.id = s.screen_id JOIN cinema c ON c.id ="
                                        + " sc.cinema_id WHERE c.city_id = 'bengaluru' AND"
                                        + " s.format = 'IMAX'",
                                r -> r.getLong(1))
                        .orElseThrow();
        assertEquals(expected, imax);
        assertTrue(imax <= every);
    }

    @Test
    void listingsAreOrderedByCinemaThenTimeAndFiltered() {
        long movieId =
                db.queryOne(
                                "SELECT s.movie_id FROM show s JOIN screen sc ON sc.id ="
                                        + " s.screen_id JOIN cinema c ON c.id = sc.cinema_id"
                                        + " WHERE c.city_id = 'bengaluru' GROUP BY s.movie_id"
                                        + " ORDER BY count(*) DESC LIMIT 1",
                                r -> r.getLong(1))
                        .orElseThrow();
        List<ShowListing> all =
                tx(
                        r ->
                                r.shows()
                                        .findListings(
                                                movieId,
                                                "bengaluru",
                                                FAR_PAST,
                                                FAR_FUTURE,
                                                Set.of(),
                                                Set.of()));
        assertFalse(all.isEmpty());
        for (int i = 1; i < all.size(); i++) {
            ShowListing a = all.get(i - 1);
            ShowListing b = all.get(i);
            assertTrue(
                    a.cinema().id() < b.cinema().id()
                            || (a.cinema().id() == b.cinema().id()
                                    && !a.show().startsAt().isAfter(b.show().startsAt())));
        }
        assertTrue(all.stream().allMatch(l -> l.cinema().cityId().equals("bengaluru")));
        assertTrue(all.stream().allMatch(l -> l.show().screenId() == l.screen().id()));
        assertTrue(all.stream().allMatch(l -> l.screen().cinemaId() == l.cinema().id()));

        String language = all.get(0).show().language();
        List<ShowListing> oneLanguage =
                tx(
                        r ->
                                r.shows()
                                        .findListings(
                                                movieId,
                                                "bengaluru",
                                                FAR_PAST,
                                                FAR_FUTURE,
                                                Set.of(language),
                                                Set.of()));
        assertTrue(oneLanguage.stream().allMatch(l -> l.show().language().equals(language)));

        List<ShowListing> wrongFormat =
                tx(
                        r ->
                                r.shows()
                                        .findListings(
                                                movieId,
                                                "bengaluru",
                                                FAR_PAST,
                                                FAR_FUTURE,
                                                Set.of(),
                                                Set.of(ShowFormat.IMAX, ShowFormat.THREE_D)
                                                                .contains(
                                                                        all.get(0).show().format())
                                                        ? Set.of(ShowFormat.TWO_D)
                                                        : Set.of(ShowFormat.IMAX)));
        assertTrue(
                wrongFormat.stream()
                        .noneMatch(l -> l.show().format() == all.get(0).show().format()));

        // Window is half open: [from, to).
        Instant t = all.get(0).show().startsAt();
        assertTrue(
                tx(
                                r ->
                                        r.shows()
                                                .findListings(
                                                        movieId,
                                                        "bengaluru",
                                                        FAR_PAST,
                                                        t,
                                                        Set.of(),
                                                        Set.of()))
                        .stream()
                        .noneMatch(l -> !l.show().startsAt().isBefore(t)));
        assertTrue(
                tx(r ->
                                r.shows()
                                        .findListings(
                                                movieId,
                                                "nowhere",
                                                FAR_PAST,
                                                FAR_FUTURE,
                                                Set.of(),
                                                Set.of()))
                        .isEmpty());
    }

    @Test
    void cancelledShowsAreNotListed() {
        long id = anyShowId();
        Show show = tx(r -> r.shows().findById(id)).orElseThrow();
        long movieId = show.movieId();
        String city =
                db.queryOne(
                                "SELECT c.city_id FROM screen sc JOIN cinema c ON c.id ="
                                        + " sc.cinema_id WHERE sc.id = ?",
                                r -> r.getString(1),
                                show.screenId())
                        .orElseThrow();
        tx(r -> r.shows().save(show.cancel()));
        assertTrue(
                tx(
                                r ->
                                        r.shows()
                                                .findListings(
                                                        movieId,
                                                        city,
                                                        FAR_PAST,
                                                        FAR_FUTURE,
                                                        Set.of(),
                                                        Set.of()))
                        .stream()
                        .noneMatch(l -> l.show().id() == id));
    }

    @Test
    void showRoundTripsPricesAndSaveUpdatesOrInserts() {
        long id = anyShowId();
        Show show = tx(r -> r.shows().findById(id)).orElseThrow();
        assertFalse(show.prices().isEmpty());
        assertEquals(ShowStatus.SCHEDULED, show.status());
        assertEquals(show, tx(r -> r.shows().save(show)));
        assertEquals(ShowStatus.CANCELLED, tx(r -> r.shows().save(show.cancel())).status());
        assertEquals(ShowStatus.CANCELLED, tx(r -> r.shows().findById(id)).orElseThrow().status());

        Show fresh =
                new Show(
                        0,
                        show.movieId(),
                        show.screenId(),
                        NOW.plus(Duration.ofDays(400)),
                        NOW.plus(Duration.ofDays(400)).plus(Duration.ofHours(3)),
                        show.language(),
                        ShowFormat.TWO_D,
                        Map.copyOf(show.prices()),
                        ShowStatus.SCHEDULED);
        Show saved = tx(r -> r.shows().save(fresh));
        assertTrue(saved.id() > 0);
        assertEquals(saved, tx(r -> r.shows().findById(saved.id())).orElseThrow());
        assertTrue(tx(r -> r.shows().findByScreen(show.screenId())).contains(saved));
    }

    @Test
    void findByScreenIsOrderedByStart() {
        long screen = tx(r -> r.screens().findByCinema(1)).get(0).id();
        List<Show> shows = tx(r -> r.shows().findByScreen(screen));
        assertFalse(shows.isEmpty());
        for (int i = 1; i < shows.size(); i++) {
            assertFalse(shows.get(i).startsAt().isBefore(shows.get(i - 1).startsAt()));
        }
    }
}
