package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.domain.Booking;
import dev.thiru.bms.domain.BookingStatus;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.HoldStatus;
import dev.thiru.bms.domain.PaymentStatus;
import dev.thiru.bms.domain.SeatHold;
import dev.thiru.bms.domain.ShowStatus;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CancelShowTest {

    @Test
    void refundsConfirmedCancelsOpenReleasesHoldsAndIsIdempotent() {
        FakeWorld w = FakeWorld.seeded();
        CreateBooking createBooking = new CreateBooking(w, w, w, w.clock);
        Checkout checkout = new Checkout(w, w, w, w, w.clock, w.policy);

        Booking paid = createBooking.execute(1, w.putHold(1, "A2", "A3").id());
        Checkout.Result c = checkout.execute(1, paid.id());
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
        Booking held = createBooking.execute(2, w.putHold(2, "A5").id());
        Booking pending = createBooking.execute(3, w.putHold(3, "A6").id());
        checkout.execute(3, pending.id());
        SeatHold activeHold = w.putHold(4, "B1");

        CancelShow cancelShow = new CancelShow(w, w, w, w.clock);
        CancelShow.Result r = cancelShow.execute(FakeWorld.SHOW_ID);

        assertEquals(new CancelShow.Result(1, 2, 1), r);
        assertEquals(ShowStatus.CANCELLED, w.show(FakeWorld.SHOW_ID).status());
        Booking refunded = w.booking(paid.id());
        assertEquals(BookingStatus.REFUNDED, refunded.status());
        assertEquals(refunded.amount(), refunded.refundAmount());
        assertEquals(BookingStatus.CANCELLED, w.booking(held.id()).status());
        assertEquals(BookingStatus.CANCELLED, w.booking(pending.id()).status());
        assertEquals(HoldStatus.RELEASED, w.hold(activeHold.id()).status());
        assertEquals(PaymentStatus.REFUNDED, w.payment(c.paymentId()).status());
        assertEquals(1, w.refunds.size());
        assertTrue(w.allLocks().isEmpty());

        assertEquals(new CancelShow.Result(0, 0, 0), cancelShow.execute(FakeWorld.SHOW_ID));
        assertEquals(1, w.refunds.size(), "no second refund");
    }

    @Test
    void worksEvenAfterTheShowStartedAndRejectsUnknownShows() {
        FakeWorld w = FakeWorld.seeded();
        w.clock.advance(Duration.ofDays(4));
        new CancelShow(w, w, w, w.clock).execute(FakeWorld.SHOW_ID);
        assertEquals(ShowStatus.CANCELLED, w.show(FakeWorld.SHOW_ID).status());
        assertEquals(
                DomainException.Code.NOT_FOUND,
                assertThrows(
                                DomainException.class,
                                () -> new CancelShow(w, w, w, w.clock).execute(404))
                        .code());
    }

    @Test
    void aFailingRefundLeavesEverythingUntouched() {
        FakeWorld w = FakeWorld.seeded();
        Booking paid = new CreateBooking(w, w, w, w.clock).execute(1, w.putHold(1, "A2").id());
        Checkout.Result c = new Checkout(w, w, w, w, w.clock, w.policy).execute(1, paid.id());
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
        w.failRefunds = true;
        assertThrows(
                IllegalStateException.class,
                () -> new CancelShow(w, w, w, w.clock).execute(FakeWorld.SHOW_ID));
        assertEquals(ShowStatus.SCHEDULED, w.show(FakeWorld.SHOW_ID).status());
        assertEquals(BookingStatus.CONFIRMED, w.booking(paid.id()).status());
    }
}
