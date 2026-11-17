package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.CatalogQueries.Availability;
import dev.thiru.bms.application.CatalogQueries.MovieFilter;
import dev.thiru.bms.application.CatalogQueries.SeatMap;
import dev.thiru.bms.application.CatalogQueries.SeatStatus;
import dev.thiru.bms.application.CatalogQueries.ShowTimes;
import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.domain.Certificate;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.Movie;
import dev.thiru.bms.domain.SeatId;
import dev.thiru.bms.domain.ShowFormat;
import dev.thiru.bms.domain.port.CatalogRepositories.MovieQuery.Sort;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogQueriesTest {

    FakeWorld w;
    CatalogQueries catalog;

    @BeforeEach
    void setUp() {
        w = FakeWorld.seeded();
        catalog = new CatalogQueries(w, w.clock, w.policy);
    }

    private static MovieFilter filter(String city, LocalDate date, String q) {
        return new MovieFilter(city, date, q, Set.of(), Set.of(), Set.of(), Set.of(), Sort.RELEASE);
    }

    private LocalDate showDate() {
        return w.show(FakeWorld.SHOW_ID)
                .startsAt()
                .atZone(java.time.ZoneId.of("Asia/Kolkata"))
                .toLocalDate();
    }

    @Test
    void citiesAndLookupsAndNotFound() {
        assertEquals("bengaluru", catalog.cities().get(0).id());
        assertEquals("Kaveri Nights", catalog.movie(1).title());
        assertEquals(
                DomainException.Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> catalog.movie(99)).code());
        assertEquals(
                DomainException.Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> catalog.cinema(99)).code());
        assertEquals(
                DomainException.Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> catalog.show(99)).code());
        assertEquals(
                DomainException.Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> catalog.seatMap(99)).code());
        assertEquals(
                DomainException.Code.NOT_FOUND,
                assertThrows(
                                DomainException.class,
                                () -> catalog.movies(filter("nowhere", null, null), 1, 10))
                        .code());
    }

    @Test
    void movieSearchByTextIsAtLeastTwoCharactersAndCaseInsensitive() {
        assertEquals(1, catalog.movies(filter(null, null, "KAVERI"), 1, 10).total());
        assertEquals(0, catalog.movies(filter(null, null, "zzz"), 1, 10).total());
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () -> catalog.movies(filter(null, null, "k"), 1, 10));
        assertTrue(e.errors().containsKey("q"));
    }

    @Test
    void withACityOnlyMoviesWithFutureScheduledShowsOnTheDateAppear() {
        assertEquals(
                0,
                catalog.movies(filter("bengaluru", null, null), 1, 10).total(),
                "default date is today, show is in 3 days");
        assertEquals(1, catalog.movies(filter("bengaluru", showDate(), null), 1, 10).total());
        assertEquals(
                0,
                catalog.movies(filter("bengaluru", showDate().plusDays(1), null), 1, 10).total());
        w.clock.advance(Duration.ofDays(3));
        assertEquals(
                0,
                catalog.movies(filter("bengaluru", showDate(), null), 1, 10).total(),
                "started shows are not listed");
    }

    @Test
    void showTimesGroupByCinemaWithLocalTimeAndMinPrice() {
        w.addShow(
                10, w.show(FakeWorld.SHOW_ID).endsAt().plusSeconds(3600), 1, "ta", ShowFormat.IMAX);
        ShowTimes times = catalog.showTimes(1, "bengaluru", showDate(), Set.of(), Set.of());
        assertEquals(showDate(), times.date());
        assertEquals(1, times.cinemas().size());
        var slots = times.cinemas().get(0).shows();
        assertEquals(2, slots.size());
        assertEquals(15000, slots.get(0).minPrice());
        assertEquals(
                w.show(FakeWorld.SHOW_ID)
                        .startsAt()
                        .atZone(java.time.ZoneId.of("Asia/Kolkata"))
                        .toLocalTime(),
                slots.get(0).localTime());
        assertEquals(Availability.AVAILABLE, slots.get(0).availability());

        var tamil = catalog.showTimes(1, "bengaluru", showDate(), Set.of("ta"), Set.of());
        assertEquals(1, tamil.cinemas().get(0).shows().size());
        var imax =
                catalog.showTimes(1, "bengaluru", showDate(), Set.of(), Set.of(ShowFormat.TWO_D));
        assertEquals(10L < 0 ? 0 : 1, imax.cinemas().get(0).shows().size());
        assertTrue(
                catalog.showTimes(1, "bengaluru", showDate().plusDays(2), Set.of(), Set.of())
                        .cinemas()
                        .isEmpty());
    }

    @Test
    void availabilityBadgesFollowTheTwentyPercentRule() {
        List<SeatId> all =
                w.inTransaction(
                        r ->
                                r.screens()
                                        .findById(FakeWorld.SCREEN_ID)
                                        .orElseThrow()
                                        .layout()
                                        .bookableSeats());
        assertEquals(23, all.size());
        var expiry = FakeWorld.T0.plus(Duration.ofHours(1));
        java.util.function.Supplier<Availability> badge =
                () ->
                        catalog.showTimes(1, "bengaluru", showDate(), Set.of(), Set.of())
                                .cinemas()
                                .get(0)
                                .shows()
                                .get(0)
                                .availability();
        for (int i = 0; i < 18; i++) {
            w.putLock(FakeWorld.SHOW_ID, all.get(i), "h", expiry);
        }
        assertEquals(Availability.AVAILABLE, badge.get(), "5 of 23 free is 21.7 percent");
        w.putLock(FakeWorld.SHOW_ID, all.get(18), "h", expiry);
        assertEquals(Availability.FILLING_FAST, badge.get(), "4 of 23 free is 17.4 percent");
        for (int i = 19; i < 23; i++) {
            w.putLock(FakeWorld.SHOW_ID, all.get(i), "h", expiry);
        }
        assertEquals(Availability.SOLD_OUT, badge.get());
    }

    @Test
    void expiredHeldLocksDoNotCountAsTaken() {
        w.putHold(7, "A2");
        w.clock.advance(Duration.ofMinutes(8));
        SeatMap map = catalog.seatMap(FakeWorld.SHOW_ID);
        assertEquals(SeatStatus.AVAILABLE, status(map, "A2"));
    }

    private static SeatStatus status(SeatMap map, String seat) {
        SeatId id = SeatId.parse(seat);
        return map.rows().stream()
                .flatMap(r -> r.seats().stream())
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow()
                .status();
    }

    @Test
    void seatMapShowsStatusesCategoriesAislesAndVersionChanges() {
        SeatMap before = catalog.seatMap(FakeWorld.SHOW_ID);
        assertEquals(FakeWorld.SHOW_ID, before.showId());
        assertEquals(420, before.holdTtlSeconds());
        assertEquals(2, before.categories().size());
        assertEquals(15000, before.categories().get(0).amount());
        assertEquals("Classic", before.categories().get(0).name());
        assertEquals(2, before.rows().size());
        assertEquals(14, before.rows().get(0).seats().size());
        assertEquals(List.of(4, 10), before.rows().get(0).aisleAfter());
        assertEquals(SeatStatus.BLOCKED, status(before, "A1"));
        assertEquals(SeatStatus.AVAILABLE, status(before, "A2"));
        assertEquals("PRIME", before.rows().get(1).seats().get(0).category());

        w.putHold(7, "A2");
        SeatMap after = catalog.seatMap(FakeWorld.SHOW_ID);
        assertEquals(SeatStatus.HELD, status(after, "A2"));
        assertNotEquals(before.version(), after.version());
        assertEquals(
                after.version(),
                catalog.seatMap(FakeWorld.SHOW_ID).version(),
                "stable when nothing changed");
    }

    @Test
    void showAndCinemaDetails() {
        var detail = catalog.show(FakeWorld.SHOW_ID);
        assertEquals("Kaveri Nights", detail.movie().title());
        assertEquals("PVR Orion", detail.cinema().name());
        assertEquals("Screen 1", detail.screen().name());
        assertEquals(
                detail.show().startsAt().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalTime(),
                detail.localTime());
        assertEquals(
                List.of("CLASSIC", "PRIME"),
                detail.prices().stream().map(p -> p.category()).toList());
        assertEquals("Prime", detail.prices().get(1).name());
        assertEquals(25000, detail.prices().get(1).amount());

        var cinema = catalog.cinema(2);
        assertEquals(1, cinema.screens().size());
        assertEquals(23, cinema.screens().get(0).capacity());
        assertEquals(1, catalog.cinemas("bengaluru", 1, 20).total());
        assertEquals(0, catalog.cinemas("chennai", 1, 20).total());
    }

    @Test
    void movieFiltersCombineWithAnd() {
        w.addMovie(
                new Movie(
                        2,
                        "Last Train",
                        List.of("hi"),
                        List.of("Thriller"),
                        Certificate.A,
                        100,
                        LocalDate.of(2026, 5, 1),
                        "/p.png",
                        ""));
        var f =
                new MovieFilter(
                        null,
                        null,
                        null,
                        Set.of("Drama"),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Sort.TITLE);
        assertEquals(1, catalog.movies(f, 1, 10).total());
        var both =
                new MovieFilter(
                        null,
                        null,
                        null,
                        Set.of("Drama", "Thriller"),
                        Set.of(),
                        Set.of(Certificate.A),
                        Set.of(),
                        Sort.TITLE);
        assertEquals(
                List.of("Last Train"),
                catalog.movies(both, 1, 10).items().stream().map(Movie::title).toList());
        assertEquals(LocalTime.class, catalog.show(FakeWorld.SHOW_ID).localTime().getClass());
    }
}
