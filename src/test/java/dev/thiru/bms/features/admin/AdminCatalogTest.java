package dev.thiru.bms.features.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.account.CinemaScope;
import dev.thiru.bms.kernel.catalog.Certificate;
import dev.thiru.bms.kernel.catalog.Cinema;
import dev.thiru.bms.kernel.catalog.City;
import dev.thiru.bms.kernel.catalog.Movie;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.MutableClock;
import dev.thiru.bms.testing.TestDb;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Catalog writes on the V2 seed. Cinema 2 (Whitefield Galaxy, bengaluru) has screens 4 (Screen 1,
 * SMALL) and 5 (Screen 2, LARGE); both layouts have CLASSIC and PRIME.
 */
class AdminCatalogTest {

    static final CinemaScope ALL = CinemaScope.everything();

    @TempDir Path dir;
    TestDb test;
    Db db;
    MutableClock clock;
    AdminFixtures f;
    AdminCatalog admin;

    @BeforeEach
    void open() {
        test = TestDb.open(dir);
        db = test.db();
        clock =
                new MutableClock(
                        AdminFixtures.seedDate(db)
                                .atTime(LocalTime.of(8, 0))
                                .atZone(AdminFixtures.IST)
                                .toInstant());
        f = new AdminFixtures(db, clock);
        admin = new AdminCatalog(db, clock);
    }

    @AfterEach
    void close() {
        test.close();
    }

    static Code codeOf(Runnable r) {
        return assertThrows(DomainException.class, r::run).code();
    }

    static SeatLayout layout(int seats) {
        return new SeatLayout(
                List.of(new SeatLayout.Category("CLASSIC", "Classic")),
                List.of(new SeatLayout.Row("A", seats, "CLASSIC", List.of(), List.of())));
    }

    static Movie movie(String title) {
        return new Movie(
                0,
                title,
                List.of("en"),
                List.of("Drama"),
                Certificate.U,
                100,
                LocalDate.parse("2026-11-01"),
                "/p.png",
                "");
    }

    /** A free slot well after the seeded fortnight. */
    Instant later(int days) {
        return clock.instant().plus(Duration.ofDays(20 + days));
    }

    @Test
    void cityCreateAndRenameKeepTheZone() {
        admin.createCity(City.of("pune", "Pune", "Asia/Kolkata"));
        assertEquals(
                Code.CONFLICT,
                codeOf(() -> admin.createCity(City.of("pune", "Pune", "Asia/Kolkata"))));
        assertEquals("Asia/Kolkata", admin.renameCity("pune", "Pune City").timeZone().getId());
        assertEquals(
                "Pune City",
                db.inTransaction(tx -> AdminSql.city(tx, "pune")).orElseThrow().name());
        assertEquals(Code.NOT_FOUND, codeOf(() -> admin.renameCity("zzz", "Zzz")));
    }

    @Test
    void cinemaNamesAreUniquePerCityAndDeleteIsBlockedByShows() {
        Cinema c = admin.createCinema(new Cinema(0, "bengaluru", "Ozone", "MG Road"));
        assertTrue(c.id() > 6);
        assertEquals(
                Code.CONFLICT,
                codeOf(() -> admin.createCinema(new Cinema(0, "bengaluru", "OZONE", "X"))));
        assertEquals(
                Code.NOT_FOUND,
                codeOf(() -> admin.createCinema(new Cinema(0, "nowhere", "Ozone", "X"))));
        admin.createCinema(new Cinema(0, "chennai", "Ozone", "Elsewhere"));
        assertEquals("New", admin.updateCinema(c.id(), "New", "Addr2").name());
        assertEquals(
                Code.CONFLICT, codeOf(() -> admin.updateCinema(c.id(), "Whitefield Galaxy", "A")));
        assertEquals(Code.NOT_FOUND, codeOf(() -> admin.updateCinema(999, "N", "A")));
        assertEquals(Code.CONFLICT, codeOf(() -> admin.deleteCinema(2)));

        Screen s = admin.createScreen(ALL, c.id(), "Audi", layout(10));
        admin.deleteCinema(c.id());
        assertEquals(0, f.count("SELECT count(*) FROM screen WHERE id = ?", s.id()));
        assertEquals(Code.NOT_FOUND, codeOf(() -> admin.deleteCinema(c.id())));
    }

    @Test
    void screenNamesAreUniqueAndTheLayoutIsLockedWhileFutureShowsExist() {
        Screen s = admin.createScreen(ALL, 2, "Screen 3", layout(10));
        assertEquals(10, s.capacity());
        assertEquals(
                Code.CONFLICT, codeOf(() -> admin.createScreen(ALL, 2, "screen 3", layout(5))));
        assertEquals(Code.NOT_FOUND, codeOf(() -> admin.createScreen(ALL, 999, "X", layout(5))));
        assertEquals(20, admin.updateScreen(ALL, s.id(), "Screen 3", layout(20)).capacity());
        assertEquals(
                layout(20),
                db.inTransaction(tx -> AdminSql.screen(tx, s.id())).orElseThrow().layout());

        Screen seeded = db.inTransaction(tx -> AdminSql.screen(tx, 5)).orElseThrow();
        assertEquals(
                Code.CONFLICT, codeOf(() -> admin.updateScreen(ALL, 5, "Screen 2", layout(20))));
        assertEquals("Renamed", admin.updateScreen(ALL, 5, "Renamed", seeded.layout()).name());
        assertEquals(
                Code.CONFLICT,
                codeOf(() -> admin.updateScreen(ALL, 5, "Screen 1", seeded.layout())));
        assertEquals(Code.NOT_FOUND, codeOf(() -> admin.updateScreen(ALL, 999, "X", layout(5))));

        // Once every show has started, the layout may change again.
        clock.advance(Duration.ofDays(15));
        assertEquals(20, admin.updateScreen(ALL, 5, "Renamed", layout(20)).capacity());
    }

    @Test
    void storedLayoutsReadBackAsTheSameDomainLayout() {
        SeatLayout l =
                new SeatLayout(
                        List.of(
                                new SeatLayout.Category("CLASSIC", "Classic \"quoted\""),
                                new SeatLayout.Category("PRIME", "Prime")),
                        List.of(
                                new SeatLayout.Row("A", 10, "CLASSIC", List.of(4), List.of(1, 10)),
                                new SeatLayout.Row("B", 8, "PRIME", List.of(), List.of())));
        Screen s = admin.createScreen(ALL, 2, "JSON", l);
        assertEquals(l, db.inTransaction(tx -> AdminSql.screen(tx, s.id())).orElseThrow().layout());
        assertEquals(
                "{\"categories\":[{\"code\":\"CLASSIC\",\"name\":\"Classic \\\"quoted\\\"\"},"
                        + "{\"code\":\"PRIME\",\"name\":\"Prime\"}],\"rows\":["
                        + "{\"row\":\"A\",\"seats\":10,\"category\":\"CLASSIC\",\"aisleAfter\":[4],"
                        + "\"blocked\":[1,10]},{\"row\":\"B\",\"seats\":8,\"category\":\"PRIME\","
                        + "\"aisleAfter\":[],\"blocked\":[]}]}",
                db.inTransaction(
                                tx ->
                                        tx.queryOne(
                                                "SELECT layout_json FROM screen WHERE id = ?",
                                                rs -> rs.getString(1),
                                                s.id()))
                        .orElseThrow());
        assertEquals(16, f.count("SELECT capacity FROM screen WHERE id = ?", s.id()));
    }

    @Test
    void screenAndMovieDeleteAreBlockedByShows() {
        assertEquals(Code.CONFLICT, codeOf(() -> admin.deleteScreen(5)));
        assertEquals(Code.CONFLICT, codeOf(() -> admin.deleteMovie(1)));
        Movie m = admin.createMovie(movie("New"));
        assertTrue(m.id() > 12);
        Movie updated =
                admin.updateMovie(
                        m.id(),
                        new Movie(
                                99,
                                "Newer",
                                List.of("hi", "en"),
                                List.of("Comedy"),
                                Certificate.A,
                                90,
                                LocalDate.parse("2026-12-01"),
                                "https://x/p.png",
                                "d"));
        assertEquals(m.id(), updated.id());
        Movie read = db.inTransaction(tx -> AdminSql.movie(tx, m.id())).orElseThrow();
        assertEquals("Newer", read.title());
        assertEquals(List.of("en", "hi"), read.languages());
        assertEquals(List.of("Comedy"), read.genres());
        assertEquals(Code.NOT_FOUND, codeOf(() -> admin.updateMovie(999, movie("X"))));
        admin.deleteMovie(m.id());
        assertEquals(0, f.count("SELECT count(*) FROM movie_language WHERE movie_id = ?", m.id()));
        assertEquals(Code.NOT_FOUND, codeOf(() -> admin.deleteMovie(m.id())));
        Screen s = admin.createScreen(ALL, 2, "Spare", layout(10));
        admin.deleteScreen(s.id());
        assertEquals(Code.NOT_FOUND, codeOf(() -> admin.deleteScreen(s.id())));
    }

    @Test
    void showCreateChecksOverlapPricesLanguageAndFuture() {
        Map<String, Long> prices = Map.of("CLASSIC", 15000L, "PRIME", 25000L);
        Instant start = later(0);
        Show s = admin.createShow(ALL, 1, 5, start, "en", ShowFormat.TWO_D, prices);
        assertTrue(s.id() > 0);
        assertEquals(start.plus(Duration.ofMinutes(138 + 15)), s.endsAt());
        assertEquals(
                "{\"CLASSIC\":15000,\"PRIME\":25000}",
                db.inTransaction(
                                tx ->
                                        tx.queryOne(
                                                "SELECT prices_json FROM show WHERE id = ?",
                                                rs -> rs.getString(1),
                                                s.id()))
                        .orElseThrow());
        DomainException overlap =
                assertThrows(
                        DomainException.class,
                        () ->
                                admin.createShow(
                                        ALL,
                                        3,
                                        5,
                                        start.plus(Duration.ofMinutes(60)),
                                        "en",
                                        ShowFormat.TWO_D,
                                        prices));
        assertEquals(Map.of("startsAt", "overlaps another show on this screen"), overlap.errors());
        // The slot frees at endsAt (half-open).
        admin.createShow(ALL, 3, 5, s.endsAt(), "en", ShowFormat.TWO_D, prices);
        DomainException past =
                assertThrows(
                        DomainException.class,
                        () ->
                                admin.createShow(
                                        ALL,
                                        1,
                                        5,
                                        clock.instant(),
                                        "en",
                                        ShowFormat.TWO_D,
                                        prices));
        assertEquals(Map.of("startsAt", "must be in the future"), past.errors());
        DomainException bad =
                assertThrows(
                        DomainException.class,
                        () ->
                                admin.createShow(
                                        ALL,
                                        1,
                                        5,
                                        later(3),
                                        "xx",
                                        ShowFormat.TWO_D,
                                        Map.of("CLASSIC", 15000L)));
        assertTrue(bad.errors().containsKey("language"));
        assertTrue(bad.errors().containsKey("prices"));
        assertEquals(
                Code.NOT_FOUND,
                codeOf(
                        () ->
                                admin.createShow(
                                        ALL, 999, 5, later(4), "en", ShowFormat.TWO_D, prices)));
        assertEquals(
                Code.NOT_FOUND,
                codeOf(
                        () ->
                                admin.createShow(
                                        ALL, 1, 999, later(4), "en", ShowFormat.TWO_D, prices)));
    }

    @Test
    void showUpdateIsRefusedOnceASeatIsLockedOrTheShowIsCancelled() {
        Map<String, Long> prices = Map.of("CLASSIC", 15000L, "PRIME", 25000L);
        Show s = admin.createShow(ALL, 1, 5, later(0), "en", ShowFormat.TWO_D, prices);
        Show moved = admin.updateShow(s.id(), 1, 5, later(1), "hi", ShowFormat.THREE_D, prices);
        assertEquals("hi", moved.language());
        assertEquals(
                "3D",
                db.inTransaction(
                                tx ->
                                        tx.queryOne(
                                                "SELECT format FROM show WHERE id = ?",
                                                rs -> rs.getString(1),
                                                s.id()))
                        .orElseThrow());
        // Moving onto its own old slot is not an overlap with itself.
        admin.updateShow(s.id(), 1, 5, later(1).plusSeconds(60), "en", ShowFormat.TWO_D, prices);
        f.hold(f.user("a@x.in"), s.id(), "A2");
        assertEquals(
                Code.CONFLICT,
                codeOf(
                        () ->
                                admin.updateShow(
                                        s.id(), 1, 5, later(2), "en", ShowFormat.TWO_D, prices)));
        Show other = admin.createShow(ALL, 1, 5, later(5), "en", ShowFormat.TWO_D, prices);
        db.inTransaction(
                tx -> tx.update("UPDATE show SET status = 'CANCELLED' WHERE id = ?", other.id()));
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () ->
                                admin.updateShow(
                                        other.id(),
                                        1,
                                        5,
                                        later(6),
                                        "en",
                                        ShowFormat.TWO_D,
                                        prices));
        assertEquals("A cancelled show cannot be edited", e.getMessage());
        assertEquals(
                Code.NOT_FOUND,
                codeOf(
                        () ->
                                admin.updateShow(
                                        999_999, 1, 5, later(6), "en", ShowFormat.TWO_D, prices)));
    }

    // ---- cinema scope ----

    @Test
    void myCinemasListsOnlyLinkedCinemasWithTheirScreensByName() {
        long owner = f.user("o@x.in");
        f.link(owner, 2);
        List<AdminCatalog.CinemaScreens> mine = admin.cinemas(CinemaScope.ownedBy(owner));
        assertEquals(List.of(2L), mine.stream().map(c -> c.cinema().id()).toList());
        assertEquals(
                List.of("Screen 1", "Screen 2"),
                mine.get(0).screens().stream().map(Screen::name).toList());
        assertEquals(6, admin.cinemas(ALL).size());
        assertEquals("Andheri Starplex", admin.cinemas(ALL).get(0).cinema().name());
        assertEquals(List.of(), admin.cinemas(CinemaScope.ownedBy(999)));
    }

    @Test
    void screensAndShowsAreManagedOnlyInOwnedCinemas() {
        long owner = f.user("o@x.in");
        f.link(owner, 2);
        CinemaScope mine = CinemaScope.ownedBy(owner);
        assertEquals(2, admin.createScreen(mine, 2, "Mine", layout(10)).cinemaId());
        assertEquals(
                "Cinema not found",
                assertThrows(
                                DomainException.class,
                                () -> admin.createScreen(mine, 1, "X", layout(10)))
                        .getMessage());
        assertEquals(
                "Screen not found",
                assertThrows(
                                DomainException.class,
                                () -> admin.updateScreen(mine, 1, "X", layout(10)))
                        .getMessage());
        Map<String, Long> prices = Map.of("CLASSIC", 15000L, "PRIME", 25000L);
        assertEquals(
                5,
                admin.createShow(mine, 1, 5, later(0), "en", ShowFormat.TWO_D, prices).screenId());
        assertEquals(
                "Screen not found",
                assertThrows(
                                DomainException.class,
                                () ->
                                        admin.createShow(
                                                mine,
                                                999,
                                                1,
                                                later(0),
                                                "en",
                                                ShowFormat.TWO_D,
                                                prices))
                        .getMessage());
        // An owned screen still runs the overlap rule.
        assertEquals(
                Code.VALIDATION,
                codeOf(
                        () ->
                                admin.createShow(
                                        mine, 1, 5, later(0), "en", ShowFormat.TWO_D, prices)));
    }

    @Test
    void unlinkingTakesEffectOnTheNextCall() {
        long owner = f.user("o@x.in");
        f.link(owner, 2);
        CinemaScope mine = CinemaScope.ownedBy(owner);
        assertEquals(1, admin.cinemas(mine).size());
        db.inTransaction(tx -> tx.update("DELETE FROM cinema_owner WHERE user_id = ?", owner));
        assertEquals(0, admin.cinemas(mine).size());
        assertEquals(Code.NOT_FOUND, codeOf(() -> admin.createScreen(mine, 2, "X", layout(10))));
    }
}
