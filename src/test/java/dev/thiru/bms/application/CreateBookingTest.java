package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingCode;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.booking.HoldStatus;
import dev.thiru.bms.kernel.booking.SeatHold;
import dev.thiru.bms.kernel.booking.SeatLock;
import dev.thiru.bms.ports.SeatEvent;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateBookingTest {

    FakeWorld w;
    CreateBooking createBooking;

    @BeforeEach
    void setUp() {
        w = FakeWorld.seeded();
        createBooking = new CreateBooking(w, w, w, w.clock);
    }

    @Test
    void turnsAnActiveHoldIntoAHeldBooking() {
        SeatHold hold = w.putHold(7, "A2", "B1");
        Booking b = createBooking.execute(7, hold.id());
        assertEquals(BookingStatus.HELD, b.status());
        assertEquals(40000, b.amount());
        assertEquals(hold.expiresAt(), b.expiresAt());
        assertEquals(0, b.version());
        assertTrue(BookingCode.isValid(b.code()));
        assertEquals(HoldStatus.CONSUMED, w.hold(hold.id()).status());
        assertTrue(w.allLocks().stream().allMatch(l -> b.id().equals(l.bookingId())));
        assertTrue(w.allLocks().stream().allMatch(l -> l.state() == SeatLock.State.HELD));
        assertEquals(b, w.booking(b.id()));
    }

    @Test
    void holdMustBelongToTheCaller() {
        SeatHold hold = w.putHold(7, "A2");
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> createBooking.execute(8, hold.id()))
                        .code());
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> createBooking.execute(7, "nope")).code());
    }

    @Test
    void holdMustBeActive() {
        SeatHold hold = w.putHold(7, "A2");
        createBooking.execute(7, hold.id());
        DomainException e =
                assertThrows(DomainException.class, () -> createBooking.execute(7, hold.id()));
        assertEquals(Code.BOOKING_STATE, e.code());
    }

    @Test
    void expiredHoldGivesGoneAndIsExpiredWithItsLocksFreed() {
        SeatHold hold = w.putHold(7, "A2");
        w.clock.advance(Duration.ofMinutes(7));
        DomainException e =
                assertThrows(DomainException.class, () -> createBooking.execute(7, hold.id()));
        assertEquals(Code.HOLD_EXPIRED, e.code());
        assertEquals(HoldStatus.EXPIRED, w.hold(hold.id()).status(), "lazy expiry must commit");
        assertTrue(w.allLocks().isEmpty());
        assertTrue(
                w.published.stream()
                        .anyMatch(
                                ev ->
                                        ev instanceof SeatEvent.Released r
                                                && r.reason() == SeatEvent.Reason.EXPIRED));
        assertTrue(w.allBookings().isEmpty());
    }

    @Test
    void bookingCodesAreUnique() {
        Booking a = createBooking.execute(7, w.putHold(7, "A2").id());
        Booking b = createBooking.execute(8, w.putHold(8, "A3").id());
        assertNotEquals(a.code(), b.code());
    }
}
