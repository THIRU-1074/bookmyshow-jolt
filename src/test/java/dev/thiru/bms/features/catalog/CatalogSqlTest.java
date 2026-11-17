package dev.thiru.bms.features.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.features.catalog.CatalogDtos.CinemaShowsDto;
import dev.thiru.bms.features.catalog.CatalogDtos.MovieSummary;
import dev.thiru.bms.features.catalog.CatalogDtos.ShowTimesDto;
import dev.thiru.bms.features.catalog.CatalogSql.MovieFilter;
import dev.thiru.bms.features.catalog.CatalogSql.Sort;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.kernel.catalog.Certificate;
import dev.thiru.bms.kernel.catalog.SeatMap;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.MutableClock;
import dev.thiru.bms.testing.TestDb;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Catalog SQL on the migrated V2 seed. Show 1 is Paper Lanterns (3D) in Audi 1 at 10:00. */
class CatalogSqlTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @TempDir Path dir;
    TestDb test;
    Db db;
    MutableClock clock;
    LocalDate seedDate;
    CatalogSql catalog;

    @BeforeEach
    void open() {
        test = TestDb.open(dir);
        db = test.db();
        seedDate = Seeds.firstShowDate(db);
        clock = new MutableClock(seedDate.atTime(LocalTime.of(8, 0)).atZone(IST).toInstant());
        catalog = new CatalogSql(db, clock, BookingPolicy.defaults());
    }

    @AfterEach
    void close() {
        test.close();
    }

    private static MovieFilter filter(String city, LocalDate date, String q) {
        return new MovieFilter(city, date, q, Set.of(), Set.of(), Set.of(), Set.of(), Sort.RELEASE);
    }

    private static DomainException.Code code(Runnable r) {
        return assertThrows(DomainException.class, r::run).code();
    }

    @Test
    void citiesAndLookupsAndNotFound() {
        assertEquals(
                List.of("bengaluru", "chennai", "mumbai"),
                catalog.cities().stream().map(CatalogDtos.CityDto::id).toList());
        assertEquals("Asia/Kolkata", catalog.cities().get(0).timeZone());
        var movie = catalog.movie(2);
        assertEquals("Kaveri Nights", movie.title());
        assertEquals(List.of("kn", "ta"), movie.languages());
        assertEquals(List.of("Drama", "Romance"), movie.genres());
        assertEquals("U", movie.certificate());
        assertEquals(LocalDate.of(2026, 10, 9), movie.releaseDate());
        assertTrue(movie.description().startsWith("Two ferry operators"));
        assertEquals(DomainException.Code.NOT_FOUND, code(() -> catalog.movie(99)));
        assertEquals(DomainException.Code.NOT_FOUND, code(() -> catalog.cinema(99)));
        assertEquals(DomainException.Code.NOT_FOUND, code(() -> catalog.show(99_999)));
        assertEquals(DomainException.Code.NOT_FOUND, code(() -> catalog.seatMap(99_999)));
        assertEquals(
                DomainException.Code.NOT_FOUND,
                code(() -> catalog.movies(filter("nowhere", null, null), 1, 10)));
        assertEquals(
                DomainException.Code.NOT_FOUND,
                code(() -> catalog.showTimes(1, "nowhere", null, Set.of(), Set.of())));
    }

    @Test
    void moviesAreSortedByReleaseOrTitleAndPaged() {
        var page = catalog.movies(filter(null, null, null), 1, 5);
        assertEquals(12, page.total());
        assertEquals(
                List.of(
                        "The Wedding Ledger",
                        "Little Tigers of Lalbagh",
                        "Orbit of Seven Lakes",
                        "Midnight at Mylapore",
                        "Sundarbans Run"),
                page.items().stream().map(MovieSummary::title).toList());
        assertEquals(2, catalog.movies(filter(null, null, null), 3, 5).items().size());
        var byTitle =
                new MovieFilter(
                        null, null, null, Set.of(), Set.of(), Set.of(), Set.of(), Sort.TITLE);
        assertEquals("Chai and Chaos", catalog.movies(byTitle, 1, 5).items().get(0).title());
    }

    @Test
    void movieSearchByTextIsAtLeastTwoCharactersAndCaseInsensitive() {
        assertEquals(1, catalog.movies(filter(null, null, "KAVERI"), 1, 10).total());
        assertEquals(1, catalog.movies(filter(null, null, "  kaveri "), 1, 10).total());
        assertEquals(0, catalog.movies(filter(null, null, "zzz"), 1, 10).total());
        assertEquals(0, catalog.movies(filter(null, null, "%%"), 1, 10).total());
        assertEquals(12, catalog.movies(filter(null, null, "  "), 1, 10).total());
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () -> catalog.movies(filter(null, null, "k"), 1, 10));
        assertEquals("must be at least 2 characters", e.errors().get("q"));
    }

    @Test
    void movieFiltersCombineWithAnd() {
        var drama =
                new MovieFilter(
                        null,
                        null,
                        null,
                        Set.of("Drama"),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Sort.TITLE);
        assertEquals(5, catalog.movies(drama, 1, 20).total());
        var both =
                new MovieFilter(
                        null,
                        null,
                        null,
                        Set.of("Drama", "Thriller"),
                        Set.of("ta"),
                        Set.of(Certificate.A),
                        Set.of(),
                        Sort.TITLE);
        assertEquals(
                List.of("Midnight at Mylapore"),
                catalog.movies(both, 1, 10).items().stream().map(MovieSummary::title).toList());
        var imax =
                new MovieFilter(
                        null,
                        null,
                        null,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(ShowFormat.THREE_D),
                        Sort.TITLE);
        assertEquals(
                List.of("Neon Delta", "Paper Lanterns"),
                catalog.movies(imax, 1, 10).items().stream().map(MovieSummary::title).toList());
    }

    @Test
    void withACityOnlyMoviesWithFutureScheduledShowsOnTheDateAppear() {
        long today = catalog.movies(filter("bengaluru", null, null), 1, 20).total();
        assertTrue(today > 0);
        assertEquals(today, catalog.movies(filter("bengaluru", seedDate, null), 1, 20).total());
        assertEquals(
                0, catalog.movies(filter("bengaluru", seedDate.plusDays(14), null), 1, 20).total());
        clock.set(seedDate.atTime(LocalTime.of(23, 15)).atZone(IST).toInstant());
        assertEquals(
                0,
                catalog.movies(filter("bengaluru", seedDate, null), 1, 20).total(),
                "a show starting now is no longer listed");
        clock.set(seedDate.atTime(LocalTime.of(23, 14)).atZone(IST).toInstant());
        assertTrue(catalog.movies(filter("bengaluru", seedDate, null), 1, 20).total() > 0);
        db.inTransaction(tx -> tx.update("UPDATE show SET status = 'CANCELLED'"));
        assertEquals(0, catalog.movies(filter("bengaluru", seedDate, null), 1, 20).total());
    }

    @Test
    void showTimesGroupByCinemaWithLocalTimeAndMinPrice() {
        ShowTimesDto times = catalog.showTimes(4, "bengaluru", seedDate, Set.of(), Set.of());
        assertEquals(seedDate, times.date());
        assertEquals(List.of(1L, 2L), times.cinemas().stream().map(CinemaShowsDto::id).toList());
        CinemaShowsDto first = times.cinemas().get(0);
        assertEquals("Koramangala Cineplex", first.name());
        assertEquals("80 Feet Road, Koramangala, Bengaluru", first.address());
        assertEquals(1, first.shows().size());
        var slot = first.shows().get(0);
        assertEquals(1, slot.id());
        assertEquals(LocalTime.of(10, 0), slot.localTime());
        assertEquals(seedDate.atTime(10, 0).atZone(IST).toInstant(), slot.startsAt());
        assertEquals("3D", slot.format());
        assertEquals(23000, slot.minPrice());
        assertEquals("AVAILABLE", slot.availability());
        assertEquals(
                List.of(LocalTime.of(10, 0), LocalTime.of(20, 30)),
                times.cinemas().get(1).shows().stream()
                        .map(CatalogDtos.SlotDto::localTime)
                        .toList());

        assertEquals(times, catalog.showTimes(4, "bengaluru", null, Set.of(), Set.of()));
        assertTrue(
                catalog.showTimes(4, "bengaluru", seedDate, Set.of("kn"), Set.of())
                        .cinemas()
                        .isEmpty());
        String language = slot.language();
        assertTrue(
                catalog
                        .showTimes(4, "bengaluru", seedDate, Set.of(language), Set.of())
                        .cinemas()
                        .stream()
                        .flatMap(c -> c.shows().stream())
                        .allMatch(s -> s.language().equals(language)));
        assertTrue(
                catalog.showTimes(4, "bengaluru", seedDate, Set.of(), Set.of(ShowFormat.TWO_D))
                        .cinemas()
                        .isEmpty());
        assertEquals(
                times,
                catalog.showTimes(4, "bengaluru", seedDate, Set.of(), Set.of(ShowFormat.THREE_D)));
        assertTrue(
                catalog.showTimes(999, "bengaluru", seedDate, Set.of(), Set.of())
                        .cinemas()
                        .isEmpty(),
                "an unknown movie has no shows, not a 404");
        clock.set(seedDate.atTime(LocalTime.of(10, 0)).atZone(IST).toInstant());
        assertEquals(
                List.of(2L),
                catalog.showTimes(4, "bengaluru", seedDate, Set.of(), Set.of()).cinemas().stream()
                        .map(CinemaShowsDto::id)
                        .toList(),
                "started shows are not sold");
    }

    @Test
    void availabilityBadgesFollowTheTwentyPercentRule() {
        // Show 1 is in Audi 1 (capacity 198): under 20 percent free is filling fast.
        Supplier<String> badge =
                () ->
                        catalog.showTimes(4, "bengaluru", seedDate, Set.of(), Set.of())
                                .cinemas()
                                .get(0)
                                .shows()
                                .get(0)
                                .availability();
        Instant later = clock.instant().plus(Duration.ofHours(1));
        List<String> seats = Seeds.seats(198);
        Seeds.hold(db, 1, later, seats.subList(0, 158));
        assertEquals("AVAILABLE", badge.get(), "40 of 198 free is 20.2 percent");
        Seeds.hold(db, 1, later, seats.subList(158, 159));
        assertEquals("FILLING_FAST", badge.get(), "39 of 198 free is 19.7 percent");
        Seeds.hold(db, 1, later, seats.subList(159, 198));
        assertEquals("SOLD_OUT", badge.get());
        clock.advance(Duration.ofHours(1));
        assertEquals("AVAILABLE", badge.get(), "expired holds free their seats");
    }

    @Test
    void cinemasAreFilteredByCityAndPaged() {
        var bengaluru = catalog.cinemas("bengaluru", 1, 20);
        assertEquals(2, bengaluru.total());
        assertEquals("Koramangala Cineplex", bengaluru.items().get(0).name());
        assertEquals("bengaluru", bengaluru.items().get(0).cityId());
        var all = catalog.cinemas(null, 2, 4);
        assertEquals(6, all.total());
        assertEquals(2, all.items().size());
        assertEquals(0, catalog.cinemas("nowhere", 1, 20).total());
    }

    @Test
    void cinemaDetailListsScreensByName() {
        var cinema = catalog.cinema(1);
        assertEquals("Koramangala Cineplex", cinema.name());
        assertEquals("bengaluru", cinema.cityId());
        assertEquals(
                List.of("Audi 1", "Audi 2", "IMAX"),
                cinema.screens().stream().map(CatalogDtos.ScreenSummary::name).toList());
        assertEquals(
                List.of(198, 94, 205),
                cinema.screens().stream().map(CatalogDtos.ScreenSummary::capacity).toList());
    }

    @Test
    void showDetailHasRefsLocalTimeAndPricesInLayoutOrder() {
        var d = catalog.show(1);
        assertEquals(1, d.id());
        assertEquals("Paper Lanterns", d.movie().title());
        assertEquals(4, d.movie().id());
        assertEquals("U", d.movie().certificate());
        assertEquals(98, d.movie().durationMinutes());
        assertEquals("Koramangala Cineplex", d.cinema().name());
        assertEquals("Audi 1", d.screen().name());
        assertEquals(LocalTime.of(10, 0), d.localTime());
        assertEquals("3D", d.format());
        assertEquals("SCHEDULED", d.status());
        assertEquals(
                List.of(
                        new CatalogDtos.PriceDto("CLASSIC", "Classic", 23000),
                        new CatalogDtos.PriceDto("PRIME", "Prime", 33000)),
                d.prices());
    }

    @Test
    void seatMapShowsStatusesAndVersionChanges() {
        SeatMap before = catalog.seatMap(1);
        assertEquals(1, before.showId());
        assertEquals(420, before.holdTtlSeconds());
        assertEquals(10, before.rows().size());
        assertEquals(List.of(5, 15), before.rows().get(0).aisleAfter());
        assertEquals(SeatMap.Status.BLOCKED, status(before, "A1"));
        assertEquals(SeatMap.Status.BLOCKED, status(before, "J20"));
        assertEquals("PRIME", before.rows().get(9).seats().get(0).category());
        assertEquals(33000, before.categories().get(1).amount());

        Seeds.hold(db, 1, clock.instant().plusSeconds(60), List.of("A2"));
        Seeds.book(db, 1, "B3");
        SeatMap after = catalog.seatMap(1);
        assertEquals(SeatMap.Status.HELD, status(after, "A2"));
        assertEquals(SeatMap.Status.BOOKED, status(after, "B3"));
        assertNotEquals(before.version(), after.version());
        assertEquals(after.version(), catalog.seatMap(1).version(), "stable when unchanged");

        clock.advance(Duration.ofMinutes(2));
        assertEquals(SeatMap.Status.AVAILABLE, status(catalog.seatMap(1), "A2"));
    }

    private static SeatMap.Status status(SeatMap map, String seat) {
        SeatId id = SeatId.parse(seat);
        return map.rows().stream()
                .flatMap(r -> r.seats().stream())
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow()
                .status();
    }

    /** Seed lookups and lock fixtures shared by the catalog tests. */
    static final class Seeds {
        private static final AtomicInteger USERS = new AtomicInteger();

        private Seeds() {}

        static LocalDate firstShowDate(Db db) {
            return db.inTransaction(
                            tx ->
                                    tx.queryOne(
                                                    "SELECT min(starts_at) AS first FROM show",
                                                    rs -> Db.instant(rs, "first"))
                                            .orElseThrow())
                    .atZone(IST)
                    .toLocalDate();
        }

        /** {@code n} distinct seat ids, row by row. */
        static List<String> seats(int n) {
            List<String> out = new ArrayList<>();
            for (char row = 'A'; out.size() < n; row++) {
                for (int s = 1; s <= 20 && out.size() < n; s++) {
                    out.add("" + row + s);
                }
            }
            return out;
        }

        /** A HELD lock on each seat, under one hold of a fresh user. */
        static void hold(Db db, long showId, Instant expires, List<String> seats) {
            db.inTransaction(
                    tx -> {
                        String hold = holdRow(tx, showId, expires);
                        List<Object[]> rows = new ArrayList<>();
                        for (String seat : seats) {
                            rows.add(new Object[] {showId, seat, hold, Db.millis(expires)});
                        }
                        tx.batch(
                                "INSERT INTO seat_lock (show_id, seat_id, hold_id, state,"
                                        + " expires_at) VALUES (?, ?, ?, 'HELD', ?)",
                                rows);
                        return null;
                    });
        }

        static void book(Db db, long showId, String seat) {
            db.inTransaction(
                    tx -> {
                        String hold = holdRow(tx, showId, Instant.EPOCH);
                        tx.update(
                                "INSERT INTO seat_lock (show_id, seat_id, hold_id, state,"
                                        + " expires_at) VALUES (?, ?, ?, 'BOOKED', NULL)",
                                showId,
                                seat,
                                hold);
                        return null;
                    });
        }

        private static String holdRow(Db.Tx tx, long showId, Instant expires) {
            long user =
                    tx.insert(
                            "INSERT INTO app_user (email, password_hash, name, roles, created_at)"
                                    + " VALUES (?, 'x', 'T', 'AUDIENCE', 0)",
                            "u" + USERS.incrementAndGet() + "@t.test");
            String hold = "h" + user;
            tx.update(
                    "INSERT INTO seat_hold (id, show_id, user_id, seats_json, created_at,"
                            + " expires_at, status) VALUES (?, ?, ?, '[]', 0, ?, 'ACTIVE')",
                    hold,
                    showId,
                    user,
                    Db.millis(expires));
            return hold;
        }
    }
}
