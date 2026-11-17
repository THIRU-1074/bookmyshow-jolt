package dev.thiru.bms.features.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.booking.HoldStatus;
import dev.thiru.bms.kernel.booking.SeatHold;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.SeatEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ExpireSweepTest {

    @RegisterExtension final BookingWorld w = new BookingWorld();
    final long show = w.show(3);
    final long ada = w.user("ada@example.com");
    final long eve = w.user("eve@example.com");

    @Test
    void nothingToDoBeforeExpiry() {
        w.hold(ada, show, "A2");
        w.clock.advance(Duration.ofMinutes(7).minusMillis(1));
        assertEquals(new ExpireSweep.Result(0, 0), w.sweep.run());
        assertEquals(1, w.count("seat_lock"));
    }

    @Test
    void expiresHoldsAtTheBoundaryAndFreesSeats() {
        SeatHold hold = w.hold(ada, show, "A3", "A2").hold();
        w.clock.advance(Duration.ofMinutes(7));
        w.events.published.clear();
        assertEquals(new ExpireSweep.Result(1, 0), w.sweep.run());
        assertEquals(HoldStatus.EXPIRED, w.holdStatus(hold.id()));
        assertEquals(0, w.count("seat_lock"));
        assertEquals(
                List.of(
                        new SeatEvent.Released(
                                show,
                                List.of(SeatId.parse("A2"), SeatId.parse("A3")),
                                SeatEvent.Reason.EXPIRED)),
                w.events.published);
    }

    @Test
    void expiresOpenBookingsButNotConfirmedOnes() {
        Booking open = w.held(ada, show, "A2");
        Booking pending = w.held(w.user("pat@example.com"), show, "A4");
        w.checkout.execute(pending.userId(), pending.id());
        Booking paid = w.confirmed(eve, show, "A3");
        w.clock.advance(Duration.ofDays(1));
        assertEquals(new ExpireSweep.Result(0, 2), w.sweep.run());
        assertEquals(BookingStatus.EXPIRED, w.bookingStatus(open.id()));
        assertEquals(BookingStatus.EXPIRED, w.bookingStatus(pending.id()));
        assertEquals(BookingStatus.CONFIRMED, w.bookingStatus(paid.id()));
        assertEquals(1, w.count("seat_lock"));
        assertEquals(new ExpireSweep.Result(0, 0), w.sweep.run(), "second run is a no-op");
    }

    @Test
    void drainsMoreThanOneBatch() {
        List<Object[]> rows = new ArrayList<>();
        long now = Db.millis(w.clock.instant());
        for (int i = 0; i < ExpireSweep.BATCH + 5; i++) {
            rows.add(new Object[] {"h" + i, show, ada, "[]", now, now + 1000, "ACTIVE"});
        }
        w.db.inTransaction(
                tx ->
                        tx.batch(
                                "INSERT INTO seat_hold (id, show_id, user_id, seats_json,"
                                        + " created_at, expires_at, status) VALUES"
                                        + " (?, ?, ?, ?, ?, ?, ?)",
                                rows));
        w.clock.advance(Duration.ofMinutes(1));
        assertEquals(new ExpireSweep.Result(ExpireSweep.BATCH + 5, 0), w.sweep.run());
        assertEquals(0, w.count("seat_hold WHERE status = 'ACTIVE'"));
    }
}
