package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.domain.Certificate;
import dev.thiru.bms.domain.Cinema;
import dev.thiru.bms.domain.City;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.DomainException.Code;
import dev.thiru.bms.domain.Movie;
import dev.thiru.bms.domain.Screen;
import dev.thiru.bms.domain.SeatId;
import dev.thiru.bms.domain.SeatLayout;
import dev.thiru.bms.domain.Show;
import dev.thiru.bms.domain.ShowFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminCatalogTest {

    static final Map<String, Long> PRICES = Map.of("CLASSIC", 15000L, "PRIME", 25000L);
    static final long SCREEN = FakeWorld.SCREEN_ID;

    FakeWorld w;
    AdminCatalog admin;

    @BeforeEach
    void setUp() {
        w = FakeWorld.seeded();
        admin = new AdminCatalog(w, w.clock);
    }

    static Movie movie(String title) {
        return new Movie(
                0,
                title,
                List.of("en"),
                List.of("Drama"),
                Certificate.U,
                100,
                LocalDate.of(2026, 12, 1),
                "/p.png",
                "d");
    }

    static SeatLayout layout(int seats) {
        return new SeatLayout(
                List.of(new SeatLayout.Category("CLASSIC", "Classic")),
                List.of(new SeatLayout.Row("A", seats, "CLASSIC", List.of(), List.of())));
    }

    static Code codeOf(Runnable r) {
        return assertThrows(DomainException.class, r::run).code();
    }

    @Test
    void cityCreateAndRenameKeepTheZone() {
        admin.createCity(City.of("pune", "Pune", "Asia/Kolkata"));
        assertEquals(
                Code.CONFLICT,
                codeOf(() -> admin.createCity(City.of("pune", "Pune2", "Asia/Kolkata"))));
        assertEquals("Asia/Kolkata", admin.renameCity("pune", "Pune City").timeZone().getId());
        assertEquals(Code.NOT_FOUND, codeOf(() -> admin.renameCity("zzz", "Zzz")));
    }

    @Test
    void cinemaNamesAreUniquePerCityAndDeleteIsBlockedByShows() {
        Cinema c = admin.createCinema(new Cinema(0, "bengaluru", "Cine A", "Addr"));
        assertTrue(c.id() > 0);
        assertEquals(
                Code.CONFLICT,
                codeOf(() -> admin.createCinema(new Cinema(0, "bengaluru", "cine a", "x"))));
        assertEquals(
                Code.NOT_FOUND,
                codeOf(() -> admin.createCinema(new Cinema(0, "nowhere", "X", "x"))));
        assertEquals("New", admin.updateCinema(c.id(), "New", "Addr2").name());
        admin.deleteCinema(c.id());
        assertEquals(Code.CONFLICT, codeOf(() -> admin.deleteCinema(2)));
    }

    @Test
    void screenLayoutIsLockedWhileFutureShowsExist() {
        Screen s = admin.createScreen(2, "Screen 2", layout(10));
        assertEquals(Code.CONFLICT, codeOf(() -> admin.createScreen(2, "screen 2", layout(5))));
        assertEquals(20, admin.updateScreen(s.id(), "Screen 2", layout(20)).capacity());
        assertEquals(
                Code.CONFLICT, codeOf(() -> admin.updateScreen(SCREEN, "Screen 1", layout(3))));
        SeatLayout same = w.screens().findById(SCREEN).orElseThrow().layout();
        assertEquals("Renamed", admin.updateScreen(SCREEN, "Renamed", same).name());
    }

    @Test
    void screenAndMovieDeleteAreBlockedByShows() {
        assertEquals(Code.CONFLICT, codeOf(() -> admin.deleteScreen(SCREEN)));
        assertEquals(Code.CONFLICT, codeOf(() -> admin.deleteMovie(1)));
        Movie m = admin.createMovie(movie("New Film"));
        assertEquals("Newer", admin.updateMovie(m.id(), movie("Newer")).title());
        admin.deleteMovie(m.id());
        Screen s = admin.createScreen(2, "Temp", layout(4));
        admin.deleteScreen(s.id());
    }

    @Test
    void showCreateChecksOverlapPricesAndFuture() {
        Instant start = FakeWorld.T0.plus(Duration.ofDays(5));
        Show s = admin.createShow(1, SCREEN, start, "en", ShowFormat.TWO_D, PRICES);
        assertTrue(s.id() > 0);
        Instant clash = start.plus(Duration.ofMinutes(30));
        assertEquals(
                Code.VALIDATION,
                codeOf(() -> admin.createShow(1, SCREEN, clash, "en", ShowFormat.TWO_D, PRICES)));
        DomainException past =
                assertThrows(
                        DomainException.class,
                        () ->
                                admin.createShow(
                                        1,
                                        SCREEN,
                                        FakeWorld.T0.minusSeconds(5),
                                        "en",
                                        ShowFormat.TWO_D,
                                        PRICES));
        assertTrue(past.errors().containsKey("startsAt"));
        Instant later = start.plus(Duration.ofDays(1));
        assertEquals(
                Code.VALIDATION,
                codeOf(
                        () ->
                                admin.createShow(
                                        1,
                                        SCREEN,
                                        later,
                                        "en",
                                        ShowFormat.TWO_D,
                                        Map.of("CLASSIC", 1L))));
    }

    @Test
    void showUpdateIsRefusedOnceASeatIsLocked() {
        Instant start = FakeWorld.T0.plus(Duration.ofDays(5));
        Show s = admin.createShow(1, SCREEN, start, "en", ShowFormat.TWO_D, PRICES);
        Show moved =
                admin.updateShow(
                        s.id(), 1, SCREEN, start.plusSeconds(3600), "ta", ShowFormat.IMAX, PRICES);
        assertEquals("ta", moved.language());
        w.putLock(s.id(), SeatId.parse("A2"), "h", FakeWorld.T0.plusSeconds(60));
        assertEquals(
                Code.CONFLICT,
                codeOf(
                        () ->
                                admin.updateShow(
                                        s.id(), 1, SCREEN, start, "en", ShowFormat.TWO_D, PRICES)));
        assertEquals(
                Code.NOT_FOUND,
                codeOf(
                        () ->
                                admin.updateShow(
                                        999, 1, SCREEN, start, "en", ShowFormat.TWO_D, PRICES)));
    }
}
