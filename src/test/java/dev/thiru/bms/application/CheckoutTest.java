package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CheckoutTest {

    FakeWorld w;
    Checkout checkout;
    Booking held;

    @BeforeEach
    void setUp() {
        w = FakeWorld.seeded();
        checkout = new Checkout(w, w, w, w, w.clock, w.policy);
        held = new CreateBooking(w, w, w, w.clock).execute(7, w.putHold(7, "A2", "B1").id());
    }

    @Test
    void movesToPaymentPendingAndOpensAPayment() {
        Checkout.Result r = checkout.execute(7, held.id());
        Booking b = w.booking(held.id());
        assertEquals(BookingStatus.PAYMENT_PENDING, b.status());
        assertEquals(1, b.checkoutCount());
        assertEquals(1, b.version());
        assertEquals(40000, r.amount());
        assertEquals("INR", r.currency());
        assertEquals("http://pay.test/" + r.paymentId(), r.checkoutUrl());
        Payment p = w.payment(r.paymentId());
        assertEquals(PaymentStatus.INITIATED, p.status());
        assertEquals(40000, p.amount());
        assertEquals(b.expiresAt(), r.expiresAt());
    }

    @Test
    void extendsBookingAndLockExpiryToThePaymentWindow() {
        w.clock.advance(Duration.ofMinutes(6));
        Checkout.Result r = checkout.execute(7, held.id());
        assertEquals(FakeWorld.T0.plus(Duration.ofMinutes(11)), r.expiresAt());
        assertTrue(w.allLocks().stream().allMatch(l -> l.expiresAt().equals(r.expiresAt())));
    }

    @Test
    void onlyTheOwnerCanCheckOut() {
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> checkout.execute(8, held.id())).code());
    }

    @Test
    void secondCheckoutWhilePendingIsAStateError() {
        checkout.execute(7, held.id());
        assertEquals(
                Code.BOOKING_STATE,
                assertThrows(DomainException.class, () -> checkout.execute(7, held.id())).code());
        assertEquals(1, w.allPayments().size());
    }

    @Test
    void fourthCheckoutIsRefused() {
        ProcessPaymentWebhook webhook = new ProcessPaymentWebhook(w, w, w, w.clock);
        for (int i = 0; i < 3; i++) {
            Checkout.Result r = checkout.execute(7, held.id());
            webhook.execute(
                    new ProcessPaymentWebhook.Event(
                            "evt-" + i,
                            ProcessPaymentWebhook.Type.PAYMENT_FAILED,
                            r.paymentId(),
                            "gw",
                            40000,
                            "INR",
                            w.clock.instant()));
        }
        assertEquals(
                Code.LIMIT_EXCEEDED,
                assertThrows(DomainException.class, () -> checkout.execute(7, held.id())).code());
    }

    @Test
    void expiredBookingGivesGoneAndIsExpiredAndFreed() {
        w.clock.advance(Duration.ofMinutes(7));
        DomainException e =
                assertThrows(DomainException.class, () -> checkout.execute(7, held.id()));
        assertEquals(Code.HOLD_EXPIRED, e.code());
        assertEquals(BookingStatus.EXPIRED, w.booking(held.id()).status());
        assertTrue(w.allLocks().isEmpty());
        assertTrue(w.allPayments().isEmpty());
    }

    @Test
    void versionConflictIsRetriedOnceThenSurfaces() {
        w.injectedVersionConflicts = 1;
        checkout.execute(7, held.id());
        assertEquals(BookingStatus.PAYMENT_PENDING, w.booking(held.id()).status());

        FakeWorld w2 = FakeWorld.seeded();
        Booking b2 = new CreateBooking(w2, w2, w2, w2.clock).execute(7, w2.putHold(7, "A2").id());
        w2.injectedVersionConflicts = 2;
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () ->
                                new Checkout(w2, w2, w2, w2, w2.clock, w2.policy)
                                        .execute(7, b2.id()));
        assertEquals(Code.VERSION_CONFLICT, e.code());
        assertEquals(BookingStatus.HELD, w2.booking(b2.id()).status());
        assertTrue(w2.allPayments().isEmpty(), "failed checkout leaves no payment behind");
    }
}
