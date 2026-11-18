package dev.thiru.bms.features.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.HoldStatus;
import dev.thiru.bms.ports.SeatEvent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CreateHoldTest {

    @RegisterExtension final BookingWorld w = new BookingWorld();
    final long show = w.show(0);
    final long ada = w.user("ada@example.com");
    final long eve = w.user("eve@example.com");

    @Test
    void holdsSeatsLocksThemAndPricesOnTheServer() {
        CreateHold.Result r = w.hold(ada, show, "A2", "G1");
        assertEquals(18000 + 28000, r.amount());
        assertEquals("INR", r.currency());
        assertEquals(HoldStatus.ACTIVE, w.holdStatus(r.hold().id()));
        assertEquals(w.clock.instant().plus(Duration.ofMinutes(7)), r.hold().expiresAt());
        assertEquals(
                List.of(
                        "A2 HELD null " + r.hold().expiresAt().toEpochMilli(),
                        "G1 HELD null " + r.hold().expiresAt().toEpochMilli()),
                w.locks());
        assertEquals(1, w.events.published.size());
        SeatEvent.Held held = (SeatEvent.Held) w.events.published.get(0);
        assertEquals(show, held.showId());
        assertEquals(List.of(SeatId.parse("A2"), SeatId.parse("G1")), held.seats());
        assertEquals(r.hold().expiresAt(), held.until());
    }

    @Test
    void seatsAreStoredAsTheLegacyWriterStoresThem() {
        CreateHold.Result r = w.hold(ada, show, "B10", "A2");
        String json =
                w.db.inTransaction(
                        tx ->
                                tx.queryOne(
                                                "SELECT seats_json FROM seat_hold WHERE id = ?",
                                                rs -> rs.getString(1),
                                                r.hold().id())
                                        .orElseThrow());
        assertEquals("[\"B10\",\"A2\"]", json);
        assertEquals(
                List.of(SeatId.parse("B10"), SeatId.parse("A2")),
                w.db.inTransaction(tx -> BookingSql.hold(tx, r.hold().id())).orElseThrow().seats());
    }

    @Test
    void conflictRollsBackEverythingAndNamesTheSeats() {
        w.hold(ada, show, "A3");
        w.events.published.clear();
        DomainException e =
                assertThrows(DomainException.class, () -> w.hold(eve, show, "A2", "A3", "B1"));
        assertEquals(Code.SEAT_UNAVAILABLE, e.code());
        assertEquals(List.of("A3"), e.conflicts());
        assertEquals(1, w.count("seat_lock"), "no partial lock from the failed hold");
        assertEquals(1, w.count("seat_hold"));
        assertTrue(w.events.published.isEmpty(), "nothing published for a rolled back hold");
    }

    @Test
    void newHoldReleasesTheCallersPreviousActiveHold() {
        CreateHold.Result first = w.hold(ada, show, "A2", "A3");
        CreateHold.Result second = w.hold(ada, show, "A3", "A5");
        assertEquals(HoldStatus.RELEASED, w.holdStatus(first.hold().id()));
        assertEquals(HoldStatus.ACTIVE, w.holdStatus(second.hold().id()));
        assertEquals(2, w.count("seat_lock"));
        List<SeatEvent.Released> released = w.events.of(SeatEvent.Released.class);
        assertEquals(1, released.size());
        assertEquals(SeatEvent.Reason.RELEASED, released.get(0).reason());
        assertEquals(List.of(SeatId.parse("A2"), SeatId.parse("A3")), released.get(0).seats());
    }

    @Test
    void aHoldOnAnotherShowLeavesTheFirstAlone() {
        CreateHold.Result first = w.hold(ada, show, "A2");
        w.hold(ada, w.show(1), "A2");
        assertEquals(HoldStatus.ACTIVE, w.holdStatus(first.hold().id()));
    }

    @Test
    void failedReplacementKeepsTheOldHold() {
        CreateHold.Result mine = w.hold(ada, show, "A2");
        w.hold(eve, show, "B1");
        assertThrows(DomainException.class, () -> w.hold(ada, show, "B1"));
        assertEquals(HoldStatus.ACTIVE, w.holdStatus(mine.hold().id()));
        assertEquals(2, w.count("seat_lock"));
    }

    @Test
    void expiredLocksOnRequestedSeatsAreTakenOver() {
        w.hold(ada, show, "A2");
        w.clock.advance(Duration.ofMinutes(7));
        w.events.published.clear();
        w.hold(eve, show, "A2");
        assertEquals(1, w.count("seat_lock"));
        List<SeatEvent.Released> released = w.events.of(SeatEvent.Released.class);
        assertEquals(1, released.size());
        assertEquals(SeatEvent.Reason.EXPIRED, released.get(0).reason());
    }

    @Test
    void validationAndShowRules() {
        DomainException bad =
                assertThrows(
                        DomainException.class, () -> w.hold(ada, show, "A1", "Q9", "A2", "A2"));
        assertEquals(Code.VALIDATION, bad.code());
        assertEquals("seat is blocked", bad.errors().get("seats[0]"));
        assertEquals("unknown seat", bad.errors().get("seats[1]"));
        assertEquals("duplicate seat", bad.errors().get("seats[3]"));
        assertEquals(
                "choose 1 to 10 seats",
                assertThrows(
                                DomainException.class,
                                () ->
                                        w.hold(
                                                ada, show, "B1", "B2", "B3", "B4", "B5", "B6", "B7",
                                                "B8", "B9", "B10", "B11"))
                        .errors()
                        .get("seats"));
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> w.hold(ada, 999_999, "A2")).code());
        w.clock.set(w.startsAt(show));
        assertEquals(
                Code.SHOW_STARTED,
                assertThrows(DomainException.class, () -> w.hold(ada, show, "A2")).code());
        assertEquals(0, w.count("seat_hold"));
    }

    @Test
    void cancelledShowCannotBeHeld() {
        w.db.inTransaction(
                tx -> tx.update("UPDATE show SET status = 'CANCELLED' WHERE id = ?", show));
        assertEquals(
                Code.BOOKING_STATE,
                assertThrows(DomainException.class, () -> w.hold(ada, show, "A2")).code());
    }

    @Test
    void releaseIsOwnerOnlyAndFreesSeats() {
        CreateHold.Result r = w.hold(ada, show, "A2");
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> w.holds.release(eve, r.hold().id()))
                        .code());
        w.events.published.clear();
        w.holds.release(ada, r.hold().id());
        assertEquals(0, w.count("seat_lock"));
        assertEquals(HoldStatus.RELEASED, w.holdStatus(r.hold().id()));
        assertEquals(
                List.of(
                        new SeatEvent.Released(
                                show, List.of(SeatId.parse("A2")), SeatEvent.Reason.RELEASED)),
                w.events.published);
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> w.holds.release(ada, r.hold().id()))
                        .code());
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> w.holds.release(ada, "nope")).code());
    }

    @Test
    void aFailingSubscriberDoesNotFailTheHold() {
        w.events.fail = true;
        CreateHold.Result r = w.hold(ada, show, "A2");
        assertEquals(HoldStatus.ACTIVE, w.holdStatus(r.hold().id()));
    }

    @Test
    void eventsArePublishedOnlyAfterTheCommit() {
        // A subscriber that reads the database sees the hold: the event left after the commit.
        List<Integer> seen = new java.util.ArrayList<>();
        CreateHold peeking =
                new CreateHold(w.db, e -> seen.add(w.count("seat_lock")), w.ids, w.clock, w.policy);
        peeking.execute(ada, show, List.of("A2", "A3"));
        assertEquals(List.of(2), seen);
    }
}
