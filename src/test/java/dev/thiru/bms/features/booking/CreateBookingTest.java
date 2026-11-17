package dev.thiru.bms.features.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingCode;
import dev.thiru.bms.kernel.booking.BookingSeat;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.booking.HoldStatus;
import dev.thiru.bms.kernel.booking.SeatHold;
import dev.thiru.bms.ports.SeatEvent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CreateBookingTest {

    @RegisterExtension final BookingWorld w = new BookingWorld();
    final long show = w.show(0);
    final long ada = w.user("ada@example.com");
    final long eve = w.user("eve@example.com");

    @Test
    void turnsAnActiveHoldIntoAHeldBooking() {
        SeatHold hold = w.hold(ada, show, "G1", "A2").hold();
        Booking b = w.create.execute(ada, hold.id());
        assertEquals(BookingStatus.HELD, b.status());
        assertEquals(46000, b.amount());
        assertEquals(hold.expiresAt(), b.expiresAt());
        assertEquals(0, b.version());
        assertTrue(BookingCode.isValid(b.code()));
        assertEquals(HoldStatus.CONSUMED, w.holdStatus(hold.id()));
        long until = hold.expiresAt().toEpochMilli();
        assertEquals(
                List.of("A2 HELD " + b.id() + " " + until, "G1 HELD " + b.id() + " " + until),
                w.locks());
        // Seats come back in the order the buyer chose them, priced from the show.
        assertEquals(
                List.of(
                        new BookingSeat(SeatId.parse("G1"), "PRIME", 28000),
                        new BookingSeat(SeatId.parse("A2"), "CLASSIC", 18000)),
                w.booking(b.id()).seats());
        assertEquals(b, w.booking(b.id()));
    }

    @Test
    void holdMustBelongToTheCaller() {
        SeatHold hold = w.hold(ada, show, "A2").hold();
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> w.create.execute(eve, hold.id())).code());
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> w.create.execute(ada, "nope")).code());
    }

    @Test
    void holdMustBeActive() {
        SeatHold hold = w.hold(ada, show, "A2").hold();
        w.create.execute(ada, hold.id());
        DomainException e =
                assertThrows(DomainException.class, () -> w.create.execute(ada, hold.id()));
        assertEquals(Code.BOOKING_STATE, e.code());
        assertEquals("Hold is CONSUMED", e.getMessage());
    }

    @Test
    void expiredHoldGivesGoneAndIsExpiredWithItsLocksFreed() {
        SeatHold hold = w.hold(ada, show, "A2").hold();
        w.clock.advance(Duration.ofMinutes(7));
        w.events.published.clear();
        DomainException e =
                assertThrows(DomainException.class, () -> w.create.execute(ada, hold.id()));
        assertEquals(Code.HOLD_EXPIRED, e.code());
        assertEquals(HoldStatus.EXPIRED, w.holdStatus(hold.id()), "lazy expiry must commit");
        assertEquals(0, w.count("seat_lock"));
        assertEquals(
                List.of(
                        new SeatEvent.Released(
                                show, List.of(SeatId.parse("A2")), SeatEvent.Reason.EXPIRED)),
                w.events.published);
        assertEquals(0, w.count("booking"));
    }

    @Test
    void aStartedShowCannotBeBooked() {
        SeatHold hold = w.hold(ada, show, "A2").hold();
        // Shorter than the hold: the show started while the hold is still live.
        w.db.inTransaction(
                tx ->
                        tx.update(
                                "UPDATE show SET starts_at = ? WHERE id = ?",
                                w.clock.instant().plusSeconds(60).toEpochMilli(),
                                show));
        w.clock.advance(Duration.ofMinutes(1));
        assertEquals(
                Code.SHOW_STARTED,
                assertThrows(DomainException.class, () -> w.create.execute(ada, hold.id())).code());
        assertEquals(HoldStatus.ACTIVE, w.holdStatus(hold.id()));
    }

    @Test
    void bookingCodesAreUnique() {
        Booking a = w.held(ada, show, "A2");
        Booking b = w.held(eve, show, "A3");
        assertNotEquals(a.code(), b.code());
    }
}
