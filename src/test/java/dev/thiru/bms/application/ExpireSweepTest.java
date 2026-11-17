package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.domain.Booking;
import dev.thiru.bms.domain.BookingStatus;
import dev.thiru.bms.domain.HoldStatus;
import dev.thiru.bms.domain.SeatHold;
import dev.thiru.bms.domain.port.SeatEvent;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExpireSweepTest {

    FakeWorld w;
    ExpireSweep sweep;

    @BeforeEach
    void setUp() {
        w = FakeWorld.seeded();
        sweep = new ExpireSweep(w, w, w.clock);
    }

    @Test
    void nothingToDoBeforeExpiry() {
        w.putHold(7, "A2");
        w.clock.advance(Duration.ofMinutes(7).minusMillis(1));
        assertEquals(new ExpireSweep.Result(0, 0), sweep.run());
        assertEquals(1, w.allLocks().size());
    }

    @Test
    void expiresHoldsAtTheBoundaryAndFreesSeats() {
        SeatHold hold = w.putHold(7, "A2", "A3");
        w.clock.advance(Duration.ofMinutes(7));
        assertEquals(new ExpireSweep.Result(1, 0), sweep.run());
        assertEquals(HoldStatus.EXPIRED, w.hold(hold.id()).status());
        assertTrue(w.allLocks().isEmpty());
        assertTrue(
                w.published.stream()
                        .anyMatch(
                                e ->
                                        e instanceof SeatEvent.Released r
                                                && r.reason() == SeatEvent.Reason.EXPIRED
                                                && r.seats().size() == 2));
    }

    @Test
    void expiresOpenBookingsButNotConfirmedOnes() {
        CreateBooking createBooking = new CreateBooking(w, w, w, w.clock);
        Booking open = createBooking.execute(7, w.putHold(7, "A2").id());
        Booking paid = createBooking.execute(8, w.putHold(8, "A3").id());
        Checkout.Result c = new Checkout(w, w, w, w, w.clock, w.policy).execute(8, paid.id());
        new ProcessPaymentWebhook(w, w, w, w.clock)
                .execute(
                        new ProcessPaymentWebhook.Event(
                                "e1",
                                ProcessPaymentWebhook.Type.PAYMENT_SUCCEEDED,
                                c.paymentId(),
                                "gw",
                                c.amount(),
                                "INR",
                                w.clock.instant()));
        w.clock.advance(Duration.ofDays(1));
        assertEquals(new ExpireSweep.Result(0, 1), sweep.run());
        assertEquals(BookingStatus.EXPIRED, w.booking(open.id()).status());
        assertEquals(BookingStatus.CONFIRMED, w.booking(paid.id()).status());
        assertEquals(1, w.allLocks().size());
        assertEquals(new ExpireSweep.Result(0, 0), sweep.run(), "second run is a no-op");
    }

    @Test
    void drainsMoreThanOneBatch() {
        for (int i = 0; i < ExpireSweep.BATCH + 5; i++) {
            w.inTransaction(
                    r -> {
                        SeatHold h =
                                new SeatHold(
                                        "h" + System.nanoTime(),
                                        FakeWorld.SHOW_ID,
                                        1,
                                        java.util.List.of(),
                                        FakeWorld.T0,
                                        FakeWorld.T0.plusSeconds(1),
                                        HoldStatus.ACTIVE);
                        r.holds().insert(h);
                        return null;
                    });
        }
        w.clock.advance(Duration.ofMinutes(1));
        assertEquals(new ExpireSweep.Result(ExpireSweep.BATCH + 5, 0), sweep.run());
    }
}
