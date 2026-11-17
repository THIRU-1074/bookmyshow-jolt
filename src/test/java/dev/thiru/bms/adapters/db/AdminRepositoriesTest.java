package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.domain.Certificate;
import dev.thiru.bms.domain.Cinema;
import dev.thiru.bms.domain.City;
import dev.thiru.bms.domain.Movie;
import dev.thiru.bms.domain.Screen;
import dev.thiru.bms.domain.SeatLayout;
import dev.thiru.bms.domain.Show;
import dev.thiru.bms.domain.port.AdminRepositories;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminRepositoriesTest extends DbTest {

    private static final SeatLayout LAYOUT =
            new SeatLayout(
                    List.of(
                            new SeatLayout.Category("CLASSIC", "Classic"),
                            new SeatLayout.Category("PRIME", "Prime")),
                    List.of(
                            new SeatLayout.Row("A", 10, "CLASSIC", List.of(5), List.of(1)),
                            new SeatLayout.Row("B", 8, "PRIME", List.of(), List.of())));

    @Test
    void citiesAreCreatedThenRenamed() {
        tx(r -> r.catalogWrites().saveCity(City.of("pune", "Pune", "Asia/Kolkata")));
        tx(r -> r.catalogWrites().saveCity(City.of("pune", "Poona", "Asia/Kolkata")));
        assertEquals("Poona", tx(r -> r.cities().findById("pune")).orElseThrow().name());
    }

    @Test
    void cinemasAndScreensRoundTripAndNamesAreUniqueIgnoringCase() {
        Cinema cinema =
                tx(r -> r.catalogWrites().saveCinema(new Cinema(0, "chennai", "Nova", "1 Road")));
        assertTrue(cinema.id() > 0);
        assertTrue(flag(r -> r.catalogWrites().cinemaNameTaken("chennai", "NOVA", 0)));
        assertFalse(flag(r -> r.catalogWrites().cinemaNameTaken("chennai", "nova", cinema.id())));
        tx(
                r ->
                        r.catalogWrites()
                                .saveCinema(
                                        new Cinema(cinema.id(), "chennai", "Nova 2", "2 Road")));
        assertEquals("Nova 2", tx(r -> r.cinemas().findById(cinema.id())).orElseThrow().name());

        Screen screen =
                tx(r -> r.catalogWrites().saveScreen(new Screen(0, cinema.id(), "Audi 1", LAYOUT)));
        assertEquals(screen, tx(r -> r.screens().findById(screen.id())).orElseThrow());
        assertEquals(
                17,
                db.queryOne(
                                "SELECT capacity FROM screen WHERE id = ?",
                                rs -> rs.getInt(1),
                                screen.id())
                        .orElseThrow());
        assertTrue(flag(r -> r.catalogWrites().screenNameTaken(cinema.id(), "audi 1", 0)));
        assertFalse(flag(r -> r.catalogWrites().screenHasShows(screen.id())));
        assertFalse(flag(r -> r.catalogWrites().cinemaHasShows(cinema.id())));

        tx(
                r -> {
                    r.catalogWrites().deleteScreen(screen.id());
                    r.catalogWrites().deleteCinema(cinema.id());
                    return null;
                });
        assertTrue(tx(r -> r.cinemas().findById(cinema.id())).isEmpty());
    }

    @Test
    void moviesRoundTripWithLanguagesAndGenres() {
        Movie movie =
                tx(
                        r ->
                                r.catalogWrites()
                                        .saveMovie(
                                                new Movie(
                                                        0,
                                                        "Test Pattern",
                                                        List.of("en", "hi"),
                                                        List.of("Drama"),
                                                        Certificate.UA,
                                                        110,
                                                        LocalDate.parse("2026-11-01"),
                                                        "/posters/test.svg",
                                                        "A film.")));
        assertEquals(movie, tx(r -> r.movies().findById(movie.id())).orElseThrow());
        Movie changed =
                new Movie(
                        movie.id(),
                        "Test Pattern 2",
                        List.of("ta"),
                        List.of("Comedy", "Family"),
                        Certificate.U,
                        95,
                        movie.releaseDate(),
                        movie.posterUrl(),
                        movie.description());
        tx(r -> r.catalogWrites().saveMovie(changed));
        assertEquals(changed, tx(r -> r.movies().findById(movie.id())).orElseThrow());
        assertFalse(flag(r -> r.catalogWrites().movieHasShows(movie.id())));
        tx(
                r -> {
                    r.catalogWrites().deleteMovie(movie.id());
                    return null;
                });
        assertTrue(tx(r -> r.movies().findById(movie.id())).isEmpty());
    }

    @Test
    void insertedShowsGetAnIdAndSeededOnesBlockDeletes() {
        Show seeded = tx(r -> r.shows().findById(anyShowId())).orElseThrow();
        Duration later = Duration.ofDays(400);
        Show inserted =
                tx(
                        r ->
                                r.catalogWrites()
                                        .insertShow(
                                                new Show(
                                                        0,
                                                        seeded.movieId(),
                                                        seeded.screenId(),
                                                        seeded.startsAt().plus(later),
                                                        seeded.endsAt().plus(later),
                                                        seeded.language(),
                                                        seeded.format(),
                                                        seeded.prices(),
                                                        seeded.status())));
        assertTrue(inserted.id() > seeded.id());
        assertEquals(inserted, tx(r -> r.shows().findById(inserted.id())).orElseThrow());
        assertTrue(flag(r -> r.catalogWrites().movieHasShows(seeded.movieId())));
        assertTrue(flag(r -> r.catalogWrites().screenHasShows(seeded.screenId())));
        assertTrue(
                flag(
                        r ->
                                r.catalogWrites()
                                        .screenHasFutureShows(
                                                seeded.screenId(),
                                                seeded.startsAt().minusSeconds(1))));
        assertFalse(
                flag(
                        r ->
                                r.catalogWrites()
                                        .screenHasFutureShows(
                                                seeded.screenId(), inserted.startsAt())));
    }

    @Test
    void reportsCountConfirmedSeatsAndPaidAndRefundedMoney() {
        long showId = anyShowId();
        Show show = tx(r -> r.shows().findById(showId)).orElseThrow();
        Screen screen = tx(r -> r.screens().findById(show.screenId())).orElseThrow();
        String category = screen.layout().categories().get(0).code();
        long user = newUser("buyer@example.com");
        long at = NOW.toEpochMilli();
        db.update(
                "INSERT INTO seat_hold VALUES ('h1', ?, ?, '[\"A2\",\"A3\"]', ?, ?, 'CONSUMED')",
                showId,
                user,
                at,
                at);
        db.update(
                "INSERT INTO booking (id, code, user_id, show_id, hold_id, amount, status,"
                        + " expires_at, created_at, updated_at)"
                        + " VALUES ('b1', 'BK00000001', ?, ?, 'h1', 50000, 'CONFIRMED', ?, ?, ?)",
                user,
                showId,
                at,
                at,
                at);
        db.update(
                "INSERT INTO booking_seat VALUES ('b1', 'A2', ?, 25000), ('b1', 'A3', ?, 25000)",
                category,
                category);
        db.update(
                "INSERT INTO payment (id, booking_id, amount, status, gateway_ref,"
                        + " refunded_amount, created_at, updated_at)"
                        + " VALUES ('p1', 'b1', 50000, 'SUCCEEDED', 'gw1', 0, ?, ?)",
                at,
                at);
        db.update(
                "INSERT INTO payment (id, booking_id, amount, status, gateway_ref,"
                        + " refunded_amount, created_at, updated_at)"
                        + " VALUES ('p2', 'b1', 50000, 'FAILED', 'gw2', 0, ?, ?)",
                at,
                at);

        AdminRepositories.ShowStat stat = tx(r -> r.reports().showStat(showId)).orElseThrow();
        assertEquals(2, stat.booked());
        assertEquals(50000, stat.paid());
        assertEquals(0, stat.refunded());
        assertEquals(screen.capacity(), stat.capacity());

        var categories = tx(r -> r.reports().categoryStats(showId));
        assertEquals(screen.layout().categories().size(), categories.size());
        var first = categories.get(0);
        assertEquals(category, first.category());
        assertEquals(2, first.booked());
        assertEquals(50000, first.revenue());
        assertEquals(
                screen.capacity(),
                categories.stream().mapToInt(AdminRepositories.CategoryStat::capacity).sum());

        assertEquals(1L, (long) tx(r -> r.reports().countConfirmedSince(NOW)));
        assertEquals(0L, (long) tx(r -> r.reports().countConfirmedSince(NOW.plusSeconds(1))));

        Instant from = show.startsAt();
        var page = tx(r -> r.reports().showStats(null, from, from.plusSeconds(1), 1, 20));
        assertTrue(page.items().stream().anyMatch(s -> s.showId() == showId));
        var none = tx(r -> r.reports().showStats("no-such-city", from, from.plusSeconds(1), 1, 20));
        assertEquals(0, none.total());
        assertTrue(tx(r -> r.reports().categoryStats(999_999)).isEmpty());
    }
}
