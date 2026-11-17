package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.HoldStatus;
import dev.thiru.bms.ports.SeatEvent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateHoldTest {

    FakeWorld w;
    CreateHold createHold;

    @BeforeEach
    void setUp() {
        w = FakeWorld.seeded();
        createHold = new CreateHold(w, w, w, w.clock, w.policy);
    }

    @Test
    void holdsSeatsLocksThemAndPricesOnTheServer() {
        CreateHold.Result r = createHold.execute(7, FakeWorld.SHOW_ID, List.of("A2", "B1"));
        assertEquals(40000, r.amount());
        assertEquals("INR", r.currency());
        assertEquals(HoldStatus.ACTIVE, r.hold().status());
        assertEquals(FakeWorld.T0.plus(Duration.ofMinutes(7)), r.hold().expiresAt());
        assertEquals(2, w.allLocks().size());
        assertEquals(1, w.published.size());
        SeatEvent.Held held = (SeatEvent.Held) w.published.get(0);
        assertEquals(List.of(SeatId.parse("A2"), SeatId.parse("B1")), held.seats());
        assertEquals(r.hold().expiresAt(), held.until());
    }

    @Test
    void conflictRollsBackEverythingAndNamesTheSeats() {
        createHold.execute(1, FakeWorld.SHOW_ID, List.of("A3"));
        w.published.clear();
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () -> createHold.execute(2, FakeWorld.SHOW_ID, List.of("A2", "A3", "B1")));
        assertEquals(Code.SEAT_UNAVAILABLE, e.code());
        assertEquals(List.of("A3"), e.conflicts());
        assertEquals(1, w.allLocks().size(), "no partial lock from the failed hold");
        assertEquals(1, w.allHolds().size());
        assertTrue(w.published.isEmpty(), "nothing published for a rolled back hold");
    }

    @Test
    void newHoldReleasesTheCallersPreviousActiveHold() {
        CreateHold.Result first = createHold.execute(7, FakeWorld.SHOW_ID, List.of("A2", "A3"));
        CreateHold.Result second = createHold.execute(7, FakeWorld.SHOW_ID, List.of("A3", "A5"));
        assertEquals(HoldStatus.RELEASED, w.hold(first.hold().id()).status());
        assertEquals(HoldStatus.ACTIVE, w.hold(second.hold().id()).status());
        assertEquals(2, w.allLocks().size());
        assertTrue(
                w.published.stream()
                        .anyMatch(
                                e ->
                                        e instanceof SeatEvent.Released rel
                                                && rel.reason() == SeatEvent.Reason.RELEASED));
    }

    @Test
    void failedReplacementKeepsTheOldHold() {
        CreateHold.Result mine = createHold.execute(7, FakeWorld.SHOW_ID, List.of("A2"));
        createHold.execute(8, FakeWorld.SHOW_ID, List.of("B1"));
        assertThrows(
                DomainException.class,
                () -> createHold.execute(7, FakeWorld.SHOW_ID, List.of("B1")));
        assertEquals(HoldStatus.ACTIVE, w.hold(mine.hold().id()).status());
    }

    @Test
    void expiredLocksOnRequestedSeatsAreTakenOver() {
        createHold.execute(1, FakeWorld.SHOW_ID, List.of("A2"));
        w.clock.advance(Duration.ofMinutes(7));
        w.published.clear();
        createHold.execute(2, FakeWorld.SHOW_ID, List.of("A2"));
        assertEquals(1, w.allLocks().size());
        assertTrue(
                w.published.stream()
                        .anyMatch(
                                e ->
                                        e instanceof SeatEvent.Released rel
                                                && rel.reason() == SeatEvent.Reason.EXPIRED));
    }

    @Test
    void validationAndShowRules() {
        DomainException bad =
                assertThrows(
                        DomainException.class,
                        () -> createHold.execute(7, FakeWorld.SHOW_ID, List.of("A1", "Q9")));
        assertEquals(Code.VALIDATION, bad.code());
        assertEquals("seat is blocked", bad.errors().get("seats[0]"));
        assertEquals("unknown seat", bad.errors().get("seats[1]"));
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> createHold.execute(7, 404, List.of("A2")))
                        .code());
        w.clock.advance(Duration.ofDays(3));
        assertEquals(
                Code.SHOW_STARTED,
                assertThrows(
                                DomainException.class,
                                () -> createHold.execute(7, FakeWorld.SHOW_ID, List.of("A2")))
                        .code());
    }

    @Test
    void cancelledShowCannotBeHeld() {
        w.inTransaction(r -> r.shows().save(w.show(FakeWorld.SHOW_ID).cancel()));
        assertEquals(
                Code.BOOKING_STATE,
                assertThrows(
                                DomainException.class,
                                () -> createHold.execute(7, FakeWorld.SHOW_ID, List.of("A2")))
                        .code());
    }

    @Test
    void releaseIsOwnerOnlyAndFreesSeats() {
        CreateHold.Result r = createHold.execute(7, FakeWorld.SHOW_ID, List.of("A2"));
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> createHold.release(8, r.hold().id()))
                        .code());
        createHold.release(7, r.hold().id());
        assertTrue(w.allLocks().isEmpty());
        assertEquals(HoldStatus.RELEASED, w.hold(r.hold().id()).status());
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> createHold.release(7, r.hold().id()))
                        .code());
    }

    @Test
    void aFailingSubscriberDoesNotFailTheHold() {
        w.failPublishing = true;
        CreateHold.Result r = createHold.execute(7, FakeWorld.SHOW_ID, List.of("A2"));
        assertEquals(HoldStatus.ACTIVE, w.hold(r.hold().id()).status());
    }
}
