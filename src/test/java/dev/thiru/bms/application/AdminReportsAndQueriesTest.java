package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.domain.Booking;
import dev.thiru.bms.domain.BookingStatus;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.DomainException.Code;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void reportCountsBookedSeatsRevenueAndOccupancy() {
        AdminReports reports = new AdminReports(w, w.clock);
        AdminReports.ShowReportDetail d = reports.show(FakeWorld.SHOW_ID);
        assertEquals(2, d.show().booked());
        assertEquals(23, d.show().capacity());
        assertEquals(8.7, d.show().occupancyPercent());
        assertEquals(40000, d.show().revenue());
        assertEquals(2, d.byCategory().size());
        assertEquals(15000, d.byCategory().get(0).revenue());
        assertEquals(
                1,
                reports.shows(null, FakeWorld.T0, FakeWorld.T0.plus(Duration.ofDays(10)), 1, 20)
                        .total());
        assertEquals(1, reports.stats().bookingsConfirmedToday());
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> reports.show(404)).code());
    }

    @Test
    void refundsReduceRevenue() {
        w.clock.advance(Duration.ofHours(1));
        new CancelBooking(w, w, w, w.clock).execute(7, confirmed.id());
        AdminReports.ShowReport r = new AdminReports(w, w.clock).show(FakeWorld.SHOW_ID).show();
        assertEquals(0, r.booked());
        assertEquals(40000, r.refunded());
        assertEquals(0, r.revenue());
    }

    @Test
    void strangersGetNotFoundButAdminsAndOwnersGetTheBooking() {
        BookingQueries q = new BookingQueries(w);
        assertEquals("Kaveri Nights", q.get(7, false, confirmed.id()).show().movieTitle());
        assertEquals(confirmed.id(), q.get(99, true, confirmed.id()).booking().id());
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> q.get(8, false, confirmed.id())).code());
        assertEquals(1, q.list(7, Set.of(BookingStatus.CONFIRMED), 1, 20).total());
        assertEquals(0, q.list(8, Set.of(), 1, 20).total());
    }

    @Test
    void ticketIsOwnerOnlyAndConfirmedOnly() {
        BookingQueries q = new BookingQueries(w);
        assertEquals(confirmed.code(), q.ticket(7, confirmed.id()).booking().code());
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> q.ticket(99, confirmed.id())).code());
        Booking other = new CreateBooking(w, w, w, w.clock).execute(8, w.putHold(8, "A5").id());
        assertEquals(
                Code.BOOKING_STATE,
                assertThrows(DomainException.class, () -> q.ticket(8, other.id())).code());
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
