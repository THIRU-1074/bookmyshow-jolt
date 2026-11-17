package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.booking.Booking;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The legacy mock gateway on FakeWorld (wave 4 moves it). Its report and booking query cases moved
 * to {@code features.admin.AdminReportsTest} and {@code features.booking.BookingQueriesTest}.
 */
class AdminReportsAndQueriesTest {

    FakeWorld w;
    Booking confirmed;

    @BeforeEach
    void setUp() {
        w = FakeWorld.seeded();
        Booking held =
                new CreateBooking(w, w, w, w.clock).execute(7, w.putHold(7, "A2", "B1").id());
        Checkout.Result pay = new Checkout(w, w, w, w, w.clock, w.policy).execute(7, held.id());
        new ProcessPaymentWebhook(w, w, w, w.clock)
                .execute(
                        new ProcessPaymentWebhook.Event(
                                "e1",
                                ProcessPaymentWebhook.Type.PAYMENT_SUCCEEDED,
                                pay.paymentId(),
                                "gw_" + pay.paymentId(),
                                pay.amount(),
                                "INR",
                                w.clock.instant()));
        confirmed = w.booking(held.id());
    }

    @Test
    void mockGatewayDeliversAnEventAndReturnsTheBooking() {
        Booking b2 = new CreateBooking(w, w, w, w.clock).execute(8, w.putHold(8, "A6").id());
        Checkout.Result pay2 = new Checkout(w, w, w, w, w.clock, w.policy).execute(8, b2.id());
        List<ProcessPaymentWebhook.Event> sent = new ArrayList<>();
        MockGatewayPayments gw =
                new MockGatewayPayments(
                        w,
                        e -> {
                            sent.add(e);
                            return 200;
                        },
                        w,
                        w.clock);
        assertEquals(pay2.amount(), gw.find(pay2.paymentId()).amount());
        assertEquals(b2.id(), gw.complete(pay2.paymentId(), false));
        assertEquals(ProcessPaymentWebhook.Type.PAYMENT_FAILED, sent.get(0).type());
        assertEquals(
                Code.NOT_FOUND, assertThrows(DomainException.class, () -> gw.find("nope")).code());
        MockGatewayPayments rejecting = new MockGatewayPayments(w, e -> 401, w, w.clock);
        assertThrows(IllegalStateException.class, () -> rejecting.complete(pay2.paymentId(), true));
    }
}
