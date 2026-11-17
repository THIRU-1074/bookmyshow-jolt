package dev.thiru.bms.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.domain.port.AdminRepositories;
import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.UnitOfWork;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingCode;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.kernel.booking.BookingSeat;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.booking.HoldStatus;
import dev.thiru.bms.kernel.booking.SeatHold;
import dev.thiru.bms.kernel.booking.SeatLock;
import dev.thiru.bms.kernel.catalog.Certificate;
import dev.thiru.bms.kernel.catalog.Cinema;
import dev.thiru.bms.kernel.catalog.City;
import dev.thiru.bms.kernel.catalog.Movie;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What every {@link UnitOfWork} and the {@link Repositories} it hands out must do, whichever store
 * backs them. Subclasses supply a fresh store; the suite builds its own catalog through the port so
 * it does not depend on seed data. Use cases are tested on the fake, so any behaviour they rely on
 * belongs here, where the real adapter is held to it too.
 */
public abstract class RepositoriesContract {

    protected static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final Map<String, Long> PRICES = Map.of("CLASSIC", 15000L, "PRIME", 25000L);

    private UnitOfWork uow;
    private int seq;
    private final Random random = new Random(7);

    /** A new store for one test; empty or seeded, the suite does not care. */
    protected abstract UnitOfWork newUnitOfWork();

    @BeforeEach
    protected void openStore() {
        uow = newUnitOfWork();
    }

    protected <T> T tx(Function<Repositories, T> work) {
        return uow.inTransaction(work);
    }

    /** {@link #tx} for a boolean result, which assertTrue cannot take as a generic T. */
    protected boolean flag(Function<Repositories, Boolean> work) {
        return uow.inTransaction(work);
    }

    // ---- fixtures, written through the port ----

    /** A city, cinema, 20-seat screen (A1..A10 CLASSIC with A1 blocked, B PRIME), movie, show. */
    protected record World(
            String cityId, Cinema cinema, Screen screen, Movie movie, Show show, long userId) {}

    private static SeatLayout layout() {
        return new SeatLayout(
                List.of(
                        new SeatLayout.Category("CLASSIC", "Classic"),
                        new SeatLayout.Category("PRIME", "Prime")),
                List.of(
                        new SeatLayout.Row("A", 10, "CLASSIC", List.of(5), List.of(1)),
                        new SeatLayout.Row("B", 10, "PRIME", List.of(), List.of())));
    }

    protected World world() {
        int n = ++seq;
        return tx(
                r -> {
                    String cityId = "contract-" + n;
                    r.catalogWrites()
                            .saveCity(City.of(cityId, "Contract City " + n, "Asia/Kolkata"));
                    Cinema cinema =
                            r.catalogWrites()
                                    .saveCinema(new Cinema(0, cityId, "Cinema " + n, "1 Road"));
                    Screen screen =
                            r.catalogWrites()
                                    .saveScreen(new Screen(0, cinema.id(), "Screen 1", layout()));
                    Movie movie = r.catalogWrites().saveMovie(movie("Contract Movie " + n));
                    Show show =
                            r.catalogWrites()
                                    .insertShow(
                                            Show.schedule(
                                                    0,
                                                    movie,
                                                    screen,
                                                    NOW.plus(Duration.ofDays(3)),
                                                    "en",
                                                    ShowFormat.TWO_D,
                                                    PRICES,
                                                    List.of()));
                    User user =
                            r.users()
                                    .insert(
                                            new User(
                                                    0,
                                                    "buyer" + n + "@contract.test",
                                                    "hash",
                                                    "Buyer " + n,
                                                    Set.of(Role.AUDIENCE),
                                                    NOW));
                    return new World(cityId, cinema, screen, movie, show, user.id());
                });
    }

    private static Movie movie(String title) {
        return new Movie(
                0,
                title,
                List.of("en"),
                List.of("Drama"),
                Certificate.UA,
                120,
                LocalDate.of(2026, 11, 1),
                "/poster.png",
                "");
    }

    private static List<SeatId> seats(String... ids) {
        return java.util.Arrays.stream(ids).map(SeatId::parse).toList();
    }

    private SeatHold hold(World w, Instant expiresAt, String... seats) {
        SeatHold h =
                new SeatHold(
                        UUID.randomUUID().toString(),
                        w.show().id(),
                        w.userId(),
                        seats(seats),
                        NOW,
                        expiresAt,
                        HoldStatus.ACTIVE);
        tx(
                r -> {
                    r.holds().insert(h);
                    r.locks().lock(w.show().id(), h.id(), h.seats(), expiresAt);
                    return null;
                });
        return h;
    }

    private Booking booking(World w, SeatHold h, Instant createdAt) {
        Booking b =
                Booking.create(
                        UUID.randomUUID().toString(),
                        BookingCode.generate(random),
                        w.userId(),
                        w.show().id(),
                        h.id(),
                        BookingSeat.price(w.show(), w.screen().layout(), h.seats()),
                        createdAt,
                        h.expiresAt());
        tx(
                r -> {
                    r.bookings().insert(b);
                    return null;
                });
        return b;
    }

    // ---- the guard for ports with throwing defaults ----

    @Test
    protected void everyRepositoryIsAvailableInsideATransaction() {
        tx(
                r -> {
                    assertNotNull(r.cities().findAll());
                    assertEquals(Optional.empty(), r.cinemas().findById(-1));
                    assertEquals(Optional.empty(), r.screens().findById(-1));
                    assertEquals(Optional.empty(), r.movies().findById(-1));
                    assertEquals(Optional.empty(), r.shows().findById(-1));
                    assertEquals(Optional.empty(), r.users().findById(-1));
                    assertEquals(Optional.empty(), r.holds().findById("none"));
                    assertEquals(List.of(), r.locks().findByHold("none"));
                    assertEquals(Optional.empty(), r.bookings().findById("none"));
                    assertEquals(Optional.empty(), r.payments().findById("none"));
                    assertNotNull(r.paymentEvents());
                    assertFalse(r.catalogWrites().cinemaHasShows(-1));
                    assertEquals(Optional.empty(), r.reports().showStat(-1));
                    assertEquals(List.of(), r.cinemaOwners().cinemaIdsOf(-1));
                    assertEquals(0, r.userRoles().page("no-such-user-zzz", 1, 5).total());
                    return null;
                });
    }

    // ---- unit of work ----

    @Test
    protected void workIsCommittedWhenItReturns() {
        long id = tx(r -> r.users().insert(user("commit@contract.test"))).id();
        assertTrue(tx(r -> r.users().findById(id)).isPresent());
    }

    @Test
    protected void everyWriteIsRolledBackWhenWorkThrowsAndTheExceptionPropagates() {
        IllegalStateException boom = new IllegalStateException("boom");
        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                tx(
                                        r -> {
                                            r.users().insert(user("rollback@contract.test"));
                                            r.catalogWrites()
                                                    .saveCity(
                                                            City.of(
                                                                    "rollback-city",
                                                                    "Rollback City",
                                                                    "Asia/Kolkata"));
                                            throw boom;
                                        }));
        assertSame(boom, thrown);
        assertEquals(Optional.empty(), tx(r -> r.users().findByEmail("rollback@contract.test")));
        assertEquals(Optional.empty(), tx(r -> r.cities().findById("rollback-city")));
    }

    @Test
    protected void theTransactionReturnsTheWorksValue() {
        assertEquals("value", tx(r -> "value"));
    }

    // ---- users ----

    private static User user(String email) {
        return new User(0, email, "hash", "Someone", Set.of(Role.AUDIENCE), NOW);
    }

    @Test
    protected void anInsertedUserGetsAnIdAndIsFoundByIdAndByEmailInAnyCase() {
        User saved = tx(r -> r.users().insert(user("Mixed.Case@Contract.test")));
        assertTrue(saved.id() > 0);
        assertEquals("mixed.case@contract.test", saved.email());
        assertEquals(saved, tx(r -> r.users().findById(saved.id())).orElseThrow());
        assertEquals(
                saved.id(),
                tx(r -> r.users().findByEmail("MIXED.case@contract.TEST")).orElseThrow().id());
    }

    @Test
    protected void aSecondAccountWithTheSameEmailIsAValidationError() {
        tx(r -> r.users().insert(user("twice@contract.test")));
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () -> tx(r -> r.users().insert(user("TWICE@contract.test"))));
        assertEquals(DomainException.Code.VALIDATION, e.code());
        assertTrue(e.errors().containsKey("email"));
    }

    // ---- catalog writes and reads ----

    @Test
    protected void catalogWritesAssignIdsAndTheReadPortsSeeThem() {
        World w = world();
        assertTrue(w.cinema().id() > 0 && w.screen().id() > 0 && w.movie().id() > 0);
        assertTrue(w.show().id() > 0);
        tx(
                r -> {
                    assertEquals(w.cityId(), r.cities().findById(w.cityId()).orElseThrow().id());
                    assertEquals(w.cinema(), r.cinemas().findById(w.cinema().id()).orElseThrow());
                    assertEquals(List.of(w.screen()), r.screens().findByCinema(w.cinema().id()));
                    assertEquals(
                            19, r.screens().findById(w.screen().id()).orElseThrow().capacity());
                    assertEquals(w.movie(), r.movies().findById(w.movie().id()).orElseThrow());
                    assertEquals(w.show(), r.shows().findById(w.show().id()).orElseThrow());
                    assertEquals(List.of(w.show()), r.shows().findByScreen(w.screen().id()));
                    return null;
                });
    }

    @Test
    protected void savingWithAnIdUpdatesInPlace() {
        World w = world();
        tx(
                r ->
                        r.catalogWrites()
                                .saveCinema(
                                        new Cinema(
                                                w.cinema().id(), w.cityId(), "Renamed", "2 Road")));
        assertEquals(
                "Renamed", tx(r -> r.cinemas().findById(w.cinema().id())).orElseThrow().name());
    }

    @Test
    protected void citiesAndTheScreensOfACinemaComeBackOrderedByName() {
        World w = world();
        Screen audi =
                tx(
                        r ->
                                r.catalogWrites()
                                        .saveScreen(
                                                new Screen(
                                                        0,
                                                        w.cinema().id(),
                                                        "Audi 2",
                                                        w.screen().layout())));
        tx(
                r ->
                        r.catalogWrites()
                                .saveCity(
                                        City.of(
                                                w.cityId() + "-a",
                                                "AAA " + w.cityId(),
                                                "Asia/Kolkata")));
        tx(
                r -> {
                    assertEquals(
                            List.of(audi, w.screen()), r.screens().findByCinema(w.cinema().id()));
                    List<String> names = r.cities().findAll().stream().map(City::name).toList();
                    assertEquals(names.stream().sorted().toList(), names);
                    return null;
                });
    }

    @Test
    protected void nameChecksIgnoreCaseAndExcludeTheEntityItself() {
        World w = world();
        String name = w.cinema().name().toUpperCase(java.util.Locale.ROOT);
        tx(
                r -> {
                    assertTrue(r.catalogWrites().cinemaNameTaken(w.cityId(), name, 0));
                    assertFalse(
                            r.catalogWrites().cinemaNameTaken(w.cityId(), name, w.cinema().id()));
                    assertTrue(r.catalogWrites().screenNameTaken(w.cinema().id(), "SCREEN 1", 0));
                    assertFalse(
                            r.catalogWrites()
                                    .screenNameTaken(w.cinema().id(), "screen 1", w.screen().id()));
                    return null;
                });
    }

    @Test
    protected void hasShowsChecksSeeTheScheduledShow() {
        World w = world();
        tx(
                r -> {
                    assertTrue(r.catalogWrites().cinemaHasShows(w.cinema().id()));
                    assertTrue(r.catalogWrites().screenHasShows(w.screen().id()));
                    assertTrue(r.catalogWrites().movieHasShows(w.movie().id()));
                    assertTrue(r.catalogWrites().screenHasFutureShows(w.screen().id(), NOW));
                    assertFalse(
                            r.catalogWrites()
                                    .screenHasFutureShows(w.screen().id(), w.show().startsAt()));
                    return null;
                });
    }

    @Test
    protected void aMovieWithoutShowsCanBeDeleted() {
        Movie m = tx(r -> r.catalogWrites().saveMovie(movie("Short Lived")));
        tx(
                r -> {
                    assertFalse(r.catalogWrites().movieHasShows(m.id()));
                    r.catalogWrites().deleteMovie(m.id());
                    return null;
                });
        assertEquals(Optional.empty(), tx(r -> r.movies().findById(m.id())));
    }

    // ---- seat locks ----

    @Test
    protected void aLockedSeatCannotBeLockedByAnotherHoldAndTheErrorNamesIt() {
        World w = world();
        hold(w, NOW.plus(Duration.ofMinutes(7)), "A3", "A4");
        SeatHold other =
                new SeatHold(
                        "other-hold",
                        w.show().id(),
                        w.userId(),
                        seats("A4", "A5"),
                        NOW,
                        NOW.plus(Duration.ofMinutes(7)),
                        HoldStatus.ACTIVE);
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () ->
                                tx(
                                        r -> {
                                            r.holds().insert(other);
                                            r.locks()
                                                    .lock(
                                                            w.show().id(),
                                                            other.id(),
                                                            other.seats(),
                                                            other.expiresAt());
                                            return null;
                                        }));
        assertEquals(DomainException.Code.SEAT_UNAVAILABLE, e.code());
        assertEquals(List.of("A4"), e.conflicts());
        assertEquals(List.of(), tx(r -> r.locks().findByHold(other.id())));
    }

    @Test
    protected void locksOfAHoldComeBackOrderedBySeat() {
        World w = world();
        Instant until = NOW.plus(Duration.ofMinutes(7));
        SeatHold h = hold(w, until, "B2", "A10", "A3");
        List<SeatLock> locks = tx(r -> r.locks().findByHold(h.id()));
        assertEquals(seats("A3", "A10", "B2"), locks.stream().map(SeatLock::seat).toList());
        assertTrue(locks.stream().allMatch(l -> l.state() == SeatLock.State.HELD));
        assertTrue(locks.stream().allMatch(l -> until.equals(l.expiresAt())));
        assertEquals(
                seats("A3", "A10", "B2"),
                tx(r -> r.locks().findByShow(w.show().id())).stream().map(SeatLock::seat).toList());
    }

    @Test
    protected void aLockMovesFromHeldToBookedWithItsBookingAndNoExpiry() {
        World w = world();
        SeatHold h = hold(w, NOW.plus(Duration.ofMinutes(7)), "A2", "A3");
        Booking b = booking(w, h, NOW);
        Instant later = NOW.plus(Duration.ofMinutes(12));
        tx(
                r -> {
                    r.locks().attachBooking(h.id(), b.id());
                    r.locks().updateExpiry(h.id(), later);
                    return null;
                });
        List<SeatLock> extended = tx(r -> r.locks().findByHold(h.id()));
        assertTrue(extended.stream().allMatch(l -> b.id().equals(l.bookingId())));
        assertTrue(extended.stream().allMatch(l -> later.equals(l.expiresAt())));
        tx(
                r -> {
                    r.locks().markBooked(h.id());
                    return null;
                });
        List<SeatLock> booked = tx(r -> r.locks().findByHold(h.id()));
        assertTrue(booked.stream().allMatch(l -> l.state() == SeatLock.State.BOOKED));
        assertTrue(booked.stream().allMatch(l -> l.expiresAt() == null));
        assertTrue(booked.stream().allMatch(l -> l.isLiveAt(NOW.plus(Duration.ofDays(30)))));
    }

    @Test
    protected void onlyExpiredHeldLocksAreDeletedLazilyAndTheFreedSeatsComeBackSorted() {
        World w = world();
        SeatHold stale = hold(w, NOW, "B3", "B1");
        SeatHold live = hold(w, NOW.plus(Duration.ofMinutes(7)), "A2");
        List<SeatId> freed =
                tx(r -> r.locks().deleteExpiredHeld(w.show().id(), seats("B3", "B1", "A2"), NOW));
        assertEquals(seats("B1", "B3"), freed);
        assertEquals(List.of(), tx(r -> r.locks().findByHold(stale.id())));
        assertEquals(1, tx(r -> r.locks().findByHold(live.id())).size());
    }

    @Test
    protected void deletingByHoldFreesEverySeatSorted() {
        World w = world();
        SeatHold h = hold(w, NOW.plus(Duration.ofMinutes(7)), "B5", "A6");
        assertEquals(seats("A6", "B5"), tx(r -> r.locks().deleteByHold(h.id())));
        assertEquals(List.of(), tx(r -> r.locks().findByShow(w.show().id())));
    }

    @Test
    protected void liveLockCountsSkipExpiredHeldLocks() {
        World w = world();
        hold(w, NOW, "A2");
        hold(w, NOW.plus(Duration.ofMinutes(7)), "A3", "A4");
        Map<Long, Integer> counts = tx(r -> r.locks().countLive(List.of(w.show().id(), -1L), NOW));
        assertEquals(Map.of(w.show().id(), 2), counts);
    }

    // ---- holds ----

    @Test
    protected void theActiveHoldOfAUserOnAShowIsFoundAndAStatusChangeHidesIt() {
        World w = world();
        SeatHold h = hold(w, NOW.plus(Duration.ofMinutes(7)), "A2");
        assertEquals(
                h, tx(r -> r.holds().findActiveByUserAndShow(w.userId(), w.show().id())).get());
        assertEquals(List.of(h), tx(r -> r.holds().findActiveByShow(w.show().id())));
        tx(
                r -> {
                    r.holds().update(h.release());
                    return null;
                });
        assertEquals(HoldStatus.RELEASED, tx(r -> r.holds().findById(h.id())).get().status());
        assertEquals(
                Optional.empty(),
                tx(r -> r.holds().findActiveByUserAndShow(w.userId(), w.show().id())));
    }

    @Test
    protected void expiredActiveHoldsComeOldestExpiryFirstUpToTheLimit() {
        World w = world();
        SeatHold later = hold(w, NOW.minus(Duration.ofMinutes(1)), "A2");
        SeatHold earliest = hold(w, NOW.minus(Duration.ofMinutes(3)), "A3");
        SeatHold middle = hold(w, NOW.minus(Duration.ofMinutes(2)), "A4");
        hold(w, NOW.plus(Duration.ofMinutes(5)), "A5");
        assertEquals(
                List.of(earliest.id(), middle.id()),
                tx(r -> r.holds().findExpiredActive(NOW, 2)).stream().map(SeatHold::id).toList());
        assertEquals(
                List.of(earliest.id(), middle.id(), later.id()),
                tx(r -> r.holds().findExpiredActive(NOW, 10)).stream().map(SeatHold::id).toList());
        assertTrue(tx(r -> r.holds().countActive(NOW)) >= 1);
    }

    // ---- bookings ----

    @Test
    protected void anUpdateBumpsTheVersionAndAStaleCopyIsAVersionConflict() {
        World w = world();
        Booking b = booking(w, hold(w, NOW.plus(Duration.ofMinutes(7)), "A2"), NOW);
        Booking pending = b.startCheckout(NOW, BookingPolicy.defaults());
        Booking saved = tx(r -> r.bookings().update(pending));
        assertEquals(b.version() + 1, saved.version());
        assertEquals(saved, tx(r -> r.bookings().findById(b.id())).orElseThrow());
        DomainException e =
                assertThrows(DomainException.class, () -> tx(r -> r.bookings().update(pending)));
        assertEquals(DomainException.Code.VERSION_CONFLICT, e.code());
    }

    @Test
    protected void aBookingKeepsItsSeatsAndIsFoundByCode() {
        World w = world();
        Booking b = booking(w, hold(w, NOW.plus(Duration.ofMinutes(7)), "B1", "A2"), NOW);
        Booking read = tx(r -> r.bookings().findById(b.id())).orElseThrow();
        assertEquals(b, read);
        assertEquals(40000, read.amount());
        assertTrue(flag(r -> r.bookings().existsByCode(b.code())));
        assertFalse(flag(r -> r.bookings().existsByCode("NOPE-0000")));
    }

    @Test
    protected void aUsersBookingsPageNewestFirstAndFiltersByStatus() {
        World w = world();
        Booking older = booking(w, hold(w, NOW.plus(Duration.ofMinutes(7)), "A2"), NOW);
        Booking newer =
                booking(
                        w,
                        hold(w, NOW.plus(Duration.ofMinutes(7)), "A3"),
                        NOW.plus(Duration.ofMinutes(1)));
        var page = tx(r -> r.bookings().page(w.userId(), Set.of(), 1, 10));
        assertEquals(List.of(newer.id(), older.id()), ids(page.items()));
        assertEquals(2, page.total());
        assertEquals(
                0,
                tx(r -> r.bookings().page(w.userId(), Set.of(BookingStatus.CONFIRMED), 1, 10))
                        .total());
        assertEquals(
                List.of(older.id()),
                ids(tx(r -> r.bookings().page(w.userId(), Set.of(), 2, 1)).items()));
    }

    private static List<String> ids(List<Booking> bookings) {
        return bookings.stream().map(Booking::id).toList();
    }

    @Test
    protected void openExpiredBookingsComeOldestExpiryFirst() {
        World w = world();
        Booking late = booking(w, hold(w, NOW.minus(Duration.ofMinutes(1)), "A2"), NOW);
        Booking early = booking(w, hold(w, NOW.minus(Duration.ofMinutes(4)), "A3"), NOW);
        booking(w, hold(w, NOW.plus(Duration.ofMinutes(4)), "A4"), NOW);
        assertEquals(
                List.of(early.id(), late.id()),
                ids(tx(r -> r.bookings().findOpenExpired(NOW, 10))));
        assertEquals(List.of(early.id()), ids(tx(r -> r.bookings().findOpenExpired(NOW, 1))));
    }

    @Test
    protected void liveBookingsOfAShowLeaveOutFinishedOnes() {
        World w = world();
        Booking open = booking(w, hold(w, NOW.plus(Duration.ofMinutes(7)), "A2"), NOW);
        Booking gone = booking(w, hold(w, NOW.plus(Duration.ofMinutes(7)), "A3"), NOW);
        tx(r -> r.bookings().update(gone.expire(NOW.plus(Duration.ofMinutes(8)))));
        assertEquals(List.of(open.id()), ids(tx(r -> r.bookings().findLiveByShow(w.show().id()))));
    }

    // ---- payments ----

    @Test
    protected void theLatestPaymentOfABookingIsTheNewest() {
        World w = world();
        Booking b = booking(w, hold(w, NOW.plus(Duration.ofMinutes(7)), "A2"), NOW);
        Payment first = Payment.initiate("pay-1-" + b.id(), b, "gw-1-" + b.id(), NOW);
        Payment second =
                Payment.initiate(
                        "pay-2-" + b.id(), b, "gw-2-" + b.id(), NOW.plus(Duration.ofMinutes(1)));
        tx(
                r -> {
                    r.payments().insert(first);
                    r.payments().insert(second);
                    r.payments().update(first.fail(NOW.plus(Duration.ofMinutes(2))));
                    return null;
                });
        assertEquals(second, tx(r -> r.payments().findLatestByBooking(b.id())).orElseThrow());
        assertEquals(
                PaymentStatus.FAILED, tx(r -> r.payments().findById(first.id())).get().status());
        assertEquals(Optional.empty(), tx(r -> r.payments().findLatestByBooking("none")));
    }

    @Test
    protected void aPaymentEventIsRecordedOnce() {
        assertTrue(
                flag(
                        r ->
                                r.paymentEvents()
                                        .recordIfNew("evt-1", "pay-x", "payment.succeeded", NOW)));
        assertFalse(
                flag(
                        r ->
                                r.paymentEvents()
                                        .recordIfNew("evt-1", "pay-x", "payment.succeeded", NOW)));
    }

    // ---- owners and roles ----

    @Test
    protected void cinemaOwnerLinksAreIdempotentScopedAndRemovable() {
        World a = world();
        World b = world();
        long owner = a.userId();
        tx(
                r -> {
                    r.cinemaOwners().link(b.cinema().id(), owner);
                    r.cinemaOwners().link(a.cinema().id(), owner);
                    r.cinemaOwners().link(a.cinema().id(), owner);
                    return null;
                });
        tx(
                r -> {
                    assertTrue(r.cinemaOwners().owns(owner, a.cinema().id()));
                    assertFalse(r.cinemaOwners().owns(b.userId(), a.cinema().id()));
                    assertEquals(
                            List.of(a.cinema().id(), b.cinema().id()),
                            r.cinemaOwners().cinemaIdsOf(owner));
                    assertEquals(List.of(a.cinema(), b.cinema()), r.cinemaOwners().cinemas(owner));
                    assertTrue(
                            r.cinemaOwners()
                                    .cinemas(null)
                                    .containsAll(List.of(a.cinema(), b.cinema())));
                    r.cinemaOwners().unlink(a.cinema().id(), owner);
                    r.cinemaOwners().unlink(a.cinema().id(), owner);
                    assertEquals(List.of(b.cinema().id()), r.cinemaOwners().cinemaIdsOf(owner));
                    r.cinemaOwners().unlinkAll(owner);
                    assertEquals(List.of(), r.cinemaOwners().cinemaIdsOf(owner));
                    return null;
                });
    }

    @Test
    protected void deletingACinemaDropsItsOwnerLinks() {
        World w = world();
        Cinema empty =
                tx(r -> r.catalogWrites().saveCinema(new Cinema(0, w.cityId(), "Empty", "3 Rd")));
        tx(
                r -> {
                    r.cinemaOwners().link(empty.id(), w.userId());
                    assertFalse(r.catalogWrites().cinemaHasShows(empty.id()));
                    r.catalogWrites().deleteCinema(empty.id());
                    return null;
                });
        assertEquals(Optional.empty(), tx(r -> r.cinemas().findById(empty.id())));
        assertEquals(List.of(), tx(r -> r.cinemaOwners().cinemaIdsOf(w.userId())));
    }

    @Test
    protected void usersPageByIdMatchesEmailOrNameIgnoringCaseAndRolesAreSaved() {
        User x = tx(r -> r.users().insert(user("zed.alpha@contract.test")));
        User y =
                tx(
                        r ->
                                r.users()
                                        .insert(
                                                new User(
                                                        0,
                                                        "other@contract.test",
                                                        "hash",
                                                        "Zed Beta",
                                                        Set.of(Role.AUDIENCE),
                                                        NOW)));
        var page = tx(r -> r.userRoles().page("ZED", 1, 10));
        assertEquals(List.of(x.id(), y.id()), page.items().stream().map(User::id).toList());
        assertEquals(2, page.total());
        tx(
                r -> {
                    r.userRoles().saveRoles(x.id(), Set.of(Role.AUDIENCE, Role.THEATRE_OWNER));
                    return null;
                });
        assertEquals(
                Set.of(Role.AUDIENCE, Role.THEATRE_OWNER),
                tx(r -> r.users().findById(x.id())).orElseThrow().roles());
    }

    // ---- reports ----

    @Test
    protected void aNewShowReportsItsCapacityAndNoSales() {
        World w = world();
        AdminRepositories.ShowStat stat =
                tx(r -> r.reports().showStat(w.show().id())).orElseThrow();
        assertEquals(
                new AdminRepositories.ShowStat(
                        w.show().id(),
                        w.movie().title(),
                        w.cinema().name(),
                        w.show().startsAt(),
                        w.show().status(),
                        19,
                        0,
                        0,
                        0),
                stat);
        assertEquals(
                List.of(
                        new AdminRepositories.CategoryStat("CLASSIC", 9, 0, 0),
                        new AdminRepositories.CategoryStat("PRIME", 10, 0, 0)),
                tx(r -> r.reports().categoryStats(w.show().id())));
    }

    @Test
    protected void aConfirmedPaidBookingShowsUpInTheReports() {
        World w = world();
        SeatHold h = hold(w, NOW.plus(Duration.ofMinutes(7)), "A2", "B2");
        Booking b = booking(w, h, NOW);
        Instant paidAt = NOW.plus(Duration.ofMinutes(1));
        Booking pending = b.startCheckout(NOW, BookingPolicy.defaults());
        Payment p = Payment.initiate("pay-" + b.id(), pending, "gw-" + b.id(), NOW);
        tx(
                r -> {
                    Booking saved = r.bookings().update(pending);
                    r.payments().insert(p);
                    r.bookings().update(saved.paymentSucceeded(paidAt));
                    r.payments().update(p.succeed(paidAt));
                    return null;
                });
        AdminRepositories.ShowStat stat =
                tx(r -> r.reports().showStat(w.show().id())).orElseThrow();
        assertEquals(2, stat.booked());
        assertEquals(40000, stat.paid());
        assertEquals(0, stat.refunded());
        assertEquals(
                List.of(
                        new AdminRepositories.CategoryStat("CLASSIC", 9, 1, 15000),
                        new AdminRepositories.CategoryStat("PRIME", 10, 1, 25000)),
                tx(r -> r.reports().categoryStats(w.show().id())));
        assertTrue(tx(r -> r.reports().countConfirmedSince(paidAt)) >= 1);
    }

    @Test
    protected void showStatsFilterByCityOwnerAndWindow() {
        World a = world();
        World b = world();
        tx(
                r -> {
                    r.cinemaOwners().link(a.cinema().id(), b.userId());
                    return null;
                });
        Instant from = NOW;
        Instant to = NOW.plus(Duration.ofDays(30));
        tx(
                r -> {
                    assertEquals(
                            List.of(a.show().id()),
                            showIds(r.reports().showStats(a.cityId(), null, from, to, 1, 10)));
                    assertEquals(
                            List.of(a.show().id()),
                            showIds(r.reports().showStats(null, b.userId(), from, to, 1, 10)));
                    assertEquals(
                            List.of(),
                            showIds(
                                    r.reports()
                                            .showStats(
                                                    a.cityId(),
                                                    null,
                                                    a.show().startsAt().plusMillis(1),
                                                    to,
                                                    1,
                                                    10)));
                    return null;
                });
        assertNotEquals(a.show().id(), b.show().id());
    }

    private static List<Long> showIds(dev.thiru.bms.kernel.Page<AdminRepositories.ShowStat> page) {
        return page.items().stream().map(AdminRepositories.ShowStat::showId).toList();
    }

    @Test
    protected void anUnknownShowHasNoStat() {
        assertEquals(Optional.empty(), tx(r -> r.reports().showStat(-42)));
        assertNull(tx(r -> r.reports().showStat(-42).orElse(null)));
    }
}
