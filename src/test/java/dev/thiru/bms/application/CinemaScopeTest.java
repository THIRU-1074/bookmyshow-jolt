package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.domain.Cinema;
import dev.thiru.bms.domain.DomainException.Code;
import dev.thiru.bms.domain.Role;
import dev.thiru.bms.domain.Screen;
import dev.thiru.bms.domain.Show;
import dev.thiru.bms.domain.ShowFormat;
import dev.thiru.bms.domain.ShowStatus;
import dev.thiru.bms.domain.User;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Theatre owners reach only the cinemas linked to them; anything else is NOT_FOUND. */
class CinemaScopeTest {

    FakeWorld w;
    AdminCatalog catalog;
    AdminReports reports;
    CancelShow cancelShow;
    long seededCinema = 2;
    Cinema otherCinema;
    Screen otherScreen;
    CinemaScope owner;
    CinemaScope stranger;

    @BeforeEach
    void setUp() {
        w = FakeWorld.seeded();
        catalog = new AdminCatalog(w, w.clock);
        reports = new AdminReports(w, w.clock);
        cancelShow = new CancelShow(w, w, w, w.clock);
        otherCinema = catalog.createCinema(new Cinema(0, "bengaluru", "Other", "Road"));
        otherScreen = catalog.createScreen(otherCinema.id(), "Audi", AdminCatalogTest.layout(10));
        long ownerId = user("owner@example.com");
        long strangerId = user("stranger@example.com");
        w.inTransaction(
                r -> {
                    r.cinemaOwners().link(seededCinema, ownerId);
                    r.cinemaOwners().link(otherCinema.id(), strangerId);
                    return null;
                });
        owner = CinemaScope.ownedBy(ownerId);
        stranger = CinemaScope.ownedBy(strangerId);
    }

    private long user(String email) {
        return w.inTransaction(
                        r ->
                                r.users()
                                        .insert(
                                                new User(
                                                        0,
                                                        email,
                                                        "h",
                                                        "N",
                                                        Set.of(Role.AUDIENCE, Role.THEATRE_OWNER),
                                                        FakeWorld.T0)))
                .id();
    }

    @Test
    void myCinemasListsOnlyLinkedCinemasWithTheirScreens() {
        List<AdminCatalog.CinemaScreens> mine = catalog.cinemas(owner);
        assertEquals(List.of(seededCinema), mine.stream().map(c -> c.cinema().id()).toList());
        assertEquals(
                List.of(FakeWorld.SCREEN_ID),
                mine.get(0).screens().stream().map(Screen::id).toList());
        assertEquals(2, catalog.cinemas(CinemaScope.everything()).size());
        assertEquals(List.of(), catalog.cinemas(CinemaScope.ownedBy(999)).stream().toList());
    }

    @Test
    void screensAreManagedOnlyInOwnedCinemas() {
        Screen created =
                catalog.createScreen(owner, seededCinema, "Audi 2", AdminCatalogTest.layout(8));
        assertEquals(seededCinema, created.cinemaId());
        assertEquals(
                12,
                catalog.updateScreen(owner, created.id(), "Audi 2", AdminCatalogTest.layout(12))
                        .capacity());

        assertEquals(
                Code.NOT_FOUND,
                AdminCatalogTest.codeOf(
                        () ->
                                catalog.createScreen(
                                        stranger, seededCinema, "X", AdminCatalogTest.layout(8))));
        assertEquals(
                Code.NOT_FOUND,
                AdminCatalogTest.codeOf(
                        () ->
                                catalog.updateScreen(
                                        stranger,
                                        FakeWorld.SCREEN_ID,
                                        "Screen 1",
                                        AdminCatalogTest.layout(8))));
        assertEquals(
                "Screen 1", w.inTransaction(r -> r.screens().findById(5)).orElseThrow().name());
    }

    @Test
    void showsAreCreatedOnlyOnOwnedScreensAndStillCheckOverlaps() {
        Instant start = FakeWorld.T0.plus(Duration.ofDays(5));
        Show show =
                catalog.createShow(
                        owner,
                        1,
                        FakeWorld.SCREEN_ID,
                        start,
                        "en",
                        ShowFormat.TWO_D,
                        AdminCatalogTest.PRICES);
        assertEquals(FakeWorld.SCREEN_ID, show.screenId());
        assertEquals(
                Code.VALIDATION,
                AdminCatalogTest.codeOf(
                        () ->
                                catalog.createShow(
                                        owner,
                                        1,
                                        FakeWorld.SCREEN_ID,
                                        start.plus(Duration.ofMinutes(30)),
                                        "en",
                                        ShowFormat.TWO_D,
                                        AdminCatalogTest.PRICES)));
        assertEquals(
                Code.NOT_FOUND,
                AdminCatalogTest.codeOf(
                        () ->
                                catalog.createShow(
                                        stranger,
                                        1,
                                        FakeWorld.SCREEN_ID,
                                        start.plus(Duration.ofDays(1)),
                                        "en",
                                        ShowFormat.TWO_D,
                                        AdminCatalogTest.PRICES)));
    }

    @Test
    void onlyAnOwnerOfTheShowsCinemaMayCancelIt() {
        assertEquals(
                Code.NOT_FOUND,
                AdminCatalogTest.codeOf(() -> cancelShow.execute(stranger, FakeWorld.SHOW_ID)));
        assertEquals(ShowStatus.SCHEDULED, w.show(FakeWorld.SHOW_ID).status());
        cancelShow.execute(owner, FakeWorld.SHOW_ID);
        assertEquals(ShowStatus.CANCELLED, w.show(FakeWorld.SHOW_ID).status());
    }

    @Test
    void reportsAreFilteredToOwnedCinemas() {
        Instant from = Instant.EPOCH;
        Instant to = FakeWorld.T0.plus(Duration.ofDays(30));
        assertEquals(
                List.of(FakeWorld.SHOW_ID),
                reports.shows(owner, null, from, to, 1, 20).items().stream()
                        .map(AdminReports.ShowReport::showId)
                        .toList());
        assertEquals(0, reports.shows(stranger, null, from, to, 1, 20).total());
        assertEquals(1, reports.shows(CinemaScope.everything(), null, from, to, 1, 20).total());
        assertEquals(FakeWorld.SHOW_ID, reports.show(owner, FakeWorld.SHOW_ID).show().showId());
        assertEquals(ShowStatus.SCHEDULED, reports.show(owner, FakeWorld.SHOW_ID).show().status());
        assertEquals(
                Code.NOT_FOUND,
                AdminCatalogTest.codeOf(() -> reports.show(stranger, FakeWorld.SHOW_ID)));
        assertEquals(Code.NOT_FOUND, AdminCatalogTest.codeOf(() -> reports.show(owner, 12345)));
    }

    @Test
    void unlinkingTakesEffectOnTheNextCall() {
        assertTrue(catalog.cinemas(owner).size() == 1);
        w.inTransaction(
                r -> {
                    r.cinemaOwners().unlink(seededCinema, owner.ownerId());
                    return null;
                });
        assertEquals(
                Code.NOT_FOUND,
                AdminCatalogTest.codeOf(() -> cancelShow.execute(owner, FakeWorld.SHOW_ID)));
    }
}
