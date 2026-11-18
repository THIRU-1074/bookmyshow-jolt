package dev.thiru.bms.features.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.testing.SliceKit;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The slice's HTTP surface on {@link SliceKit} with the production pipeline, moved from {@code
 * BookingApiTest}: the signed webhook and the mock gateway pages. The booking halves of those cases
 * are in {@code BookingRoutesTest}.
 */
class PaymentRoutesTest {

    final PaymentFlow flow = PaymentFlow.boot();
    final SliceKit kit = flow.kit;
    final String ada = kit.audience("ada@example.com");

    @AfterEach
    void close() {
        kit.close();
    }

    private static String auth(String token) {
        return SliceKit.bearer(token);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** The webhook steps of {@code BookingApiTest.fullFlowFromHoldToTicketAndCancel}. */
    @Test
    void successConfirmsADuplicateReplaysAndTheTicketIsServed() {
        String[] p = flow.pending(ada, "A2", "A3");
        String booking = p[0];
        String payment = p[1];
        String code =
                (String)
                        kit.http()
                                .get("/api/bookings/" + booking)
                                .header("Authorization", auth(ada))
                                .expect(200)
                                .jsonField("code");

        var ok =
                flow.webhook("evt-1", "payment.succeeded", payment, 36000)
                        .expect(200)
                        .header("Cache-Control", "no-store")
                        .jsonEquals("status", "CONFIRMED");
        assertEquals("{\"status\":\"CONFIRMED\"}", ok.text());
        assertEquals(
                List.of(
                        new SeatEvent.Booked(
                                flow.show, List.of(SeatId.parse("A2"), SeatId.parse("A3")))),
                kit.events().of(SeatEvent.Booked.class));
        var dup =
                flow.webhook("evt-1", "payment.succeeded", payment, 36000)
                        .expect(200)
                        .jsonEquals("duplicate", true);
        assertEquals("{\"duplicate\":true}", dup.text());
        assertEquals(1, kit.events().of(SeatEvent.Booked.class).size());

        var ticket =
                kit.http()
                        .get("/api/bookings/" + booking + "/ticket")
                        .header("Authorization", auth(ada))
                        .expect(200)
                        .bodyContains(code)
                        .bodyContains("<li>A2</li>")
                        .bodyContains("INR 360.00");
        assertTrue(ticket.header("Content-Type").startsWith("text/html"));

        kit.http()
                .post("/api/bookings/" + booking + "/cancel")
                .header("Authorization", auth(ada))
                .header("Idempotency-Key", kit.key())
                .expect(200)
                .jsonEquals("status", "REFUNDED")
                .jsonEquals("refundAmount", 36000);
    }

    /** The payment-failed loop of {@code expiredHoldGives410AndTooManyCheckoutsGive429}. */
    @Test
    void threeFailedPaymentsReachTheCheckoutLimit() {
        String booking = flow.book(ada, flow.hold(ada, "A5"));
        for (int i = 0; i < 3; i++) {
            String payment = flow.checkout(ada, booking);
            flow.webhook("fail-" + i, "payment.failed", payment, 18000)
                    .expect(200)
                    .jsonEquals("status", "HELD");
        }
        kit.http()
                .post("/api/bookings/" + booking + "/checkout")
                .header("Authorization", auth(ada))
                .header("Idempotency-Key", kit.key())
                .expect(429)
                .jsonEquals("code", "LIMIT_EXCEEDED");
    }

    @Test
    void webhookRejectsBadSignaturesAndMismatchedAmounts() {
        String[] p = flow.pending(ada, "A2");
        String booking = p[0];
        String payment = p[1];
        kit.http()
                .post("/api/payments/webhook")
                .header("Bms-Timestamp", "1")
                .header("Bms-Signature", "v1=forged")
                .json(Map.of("eventId", "e"))
                .expect(401)
                .jsonEquals("code", "UNAUTHENTICATED");
        kit.http().post("/api/payments/webhook").json(Map.of()).expect(401);
        byte[] body = utf8("{\"eventId\":\"e\"}");
        long now = kit.clock().instant().getEpochSecond();
        // Tampered body, stale and future timestamps are all 401.
        flow.signedWebhook(utf8("{\"eventId\":\"f\"}"), now, PaymentFlow.sign(now, body))
                .expect(401);
        flow.signedWebhook(body, now - 301, PaymentFlow.sign(now - 301, body))
                .expect(401)
                .jsonEquals("code", "UNAUTHENTICATED");
        flow.signedWebhook(body, now + 301, PaymentFlow.sign(now + 301, body)).expect(401);
        // Exactly 300 s off still reaches validation.
        flow.signedWebhook(body, now - 300, PaymentFlow.sign(now - 300, body)).expect(422);
        assertTrue(
                flow.webhook("evt-x", "payment.refunded", payment, 18000)
                        .expect(422)
                        .jsonHas("errors.type"));
        flow.webhook("evt-y", "payment.succeeded", payment, 1)
                .expect(422)
                .jsonEquals("code", "VALIDATION")
                .jsonEquals("errors.amount", "does not match the payment (payment marked FAILED)");
        kit.http()
                .get("/api/bookings/" + booking)
                .header("Authorization", auth(ada))
                .expect(200)
                .jsonEquals("status", "HELD");
        assertEquals("FAILED", flow.paymentStatus(payment));
    }

    @Test
    void aBodyThatIsNotAnEventOrMissesFieldsIs422() {
        byte[] junk = utf8("not json");
        long now = kit.clock().instant().getEpochSecond();
        flow.signedWebhook(junk, now, PaymentFlow.sign(now, junk))
                .expect(422)
                .jsonEquals("errors.body", "is not a valid webhook event");
        byte[] empty = utf8("{}");
        var res =
                flow.signedWebhook(empty, now, PaymentFlow.sign(now, empty))
                        .expect(422)
                        .jsonEquals("errors.eventId", "is required")
                        .jsonEquals("errors.paymentId", "is required")
                        .jsonEquals("errors.currency", "is required")
                        .jsonEquals("errors.amount", "is required")
                        .jsonEquals("errors.occurredAt", "is required");
        assertTrue(res.jsonHas("errors.type"));
    }

    @Test
    void anUnknownPaymentIs404() {
        flow.webhook("evt-404", "payment.succeeded", "nope", 1)
                .expect(404)
                .jsonEquals("code", "NOT_FOUND");
    }

    @Test
    void aLateSuccessExpiresRefundsAndAnnouncesTheFreedSeats() {
        String[] p = flow.pending(ada, "A2", "A3");
        kit.mutableClock().advance(Duration.ofMinutes(8));
        flow.webhook("late", "payment.succeeded", p[1], 36000)
                .expect(200)
                .jsonEquals("status", "EXPIRED");
        assertEquals("REFUNDED", flow.paymentStatus(p[1]));
        assertEquals(List.of("gw_" + p[1] + ":36000"), kit.gateway().refunds);
        assertEquals(
                List.of(
                        new SeatEvent.Released(
                                flow.show,
                                List.of(SeatId.parse("A2"), SeatId.parse("A3")),
                                SeatEvent.Reason.EXPIRED)),
                kit.events().of(SeatEvent.Released.class));
    }

    /**
     * SYSTEM_DESIGN 4 end to end over HTTP: the first delivery fails inside the transaction (the
     * gateway refund throws), so nothing is recorded; the redelivery of the same event id then
     * succeeds, and a third delivery is a duplicate. One refund, one release, one event row.
     */
    @Test
    void aFailedFirstDeliveryRollsBackAndTheRedeliveryAppliesOnceWithNoDuplicateEffects() {
        String[] p = flow.pending(ada, "A2", "A3");
        kit.events().published.clear();
        kit.mutableClock().advance(Duration.ofMinutes(8));
        kit.gateway().failRefunds = true;

        flow.webhook("evt-redeliver", "payment.succeeded", p[1], 36000).expect(500);
        assertEquals("PAYMENT_PENDING", flow.bookingStatus(p[0]), "rolled back");
        assertEquals("INITIATED", flow.paymentStatus(p[1]));
        assertEquals(0, eventRows("evt-redeliver"), "the event id was not recorded");
        assertTrue(kit.events().published.isEmpty(), "nothing announced on rollback");
        assertTrue(kit.gateway().refunds.isEmpty());

        kit.gateway().failRefunds = false;
        flow.webhook("evt-redeliver", "payment.succeeded", p[1], 36000)
                .expect(200)
                .jsonEquals("status", "EXPIRED");
        assertEquals("REFUNDED", flow.paymentStatus(p[1]));
        assertEquals(1, eventRows("evt-redeliver"));
        assertEquals(List.of("gw_" + p[1] + ":36000"), kit.gateway().refunds);
        assertEquals(1, kit.events().of(SeatEvent.Released.class).size());

        flow.webhook("evt-redeliver", "payment.succeeded", p[1], 36000)
                .expect(200)
                .jsonEquals("duplicate", true);
        assertEquals(1, kit.gateway().refunds.size(), "still one refund");
        assertEquals(1, kit.events().of(SeatEvent.Released.class).size(), "still one release");
        assertEquals(1, eventRows("evt-redeliver"));
    }

    private long eventRows(String eventId) {
        return kit.db()
                .inTransaction(
                        tx ->
                                tx.queryOne(
                                                "SELECT COUNT(*) FROM payment_event"
                                                        + " WHERE event_id = ?",
                                                rs -> rs.getLong(1),
                                                eventId)
                                        .orElseThrow());
    }

    /** The review's reproduction: payment.failed, then payment.succeeded for one payment. */
    @Test
    void paymentFailedThenPaymentSucceededRefundsTheCustomerOverHttp() {
        String[] p = flow.pending(ada, "A2", "A3");
        flow.webhook("f1", "payment.failed", p[1], 36000).expect(200).jsonEquals("status", "HELD");
        flow.webhook("s1", "payment.succeeded", p[1], 36000)
                .expect(200)
                .jsonEquals("status", "HELD");
        assertEquals("REFUNDED", flow.paymentStatus(p[1]));
        assertEquals(36000, flow.refunded(p[1]));
        assertEquals("HELD", flow.bookingStatus(p[0]), "still retryable");
        flow.webhook("s2", "payment.succeeded", p[1], 36000).expect(200);
        assertEquals(List.of("gw_" + p[1] + ":36000"), kit.gateway().refunds, "one refund only");
    }

    @Test
    void mockGatewayPageAndCompletion() {
        String[] p = flow.pending(ada, "A2");
        String booking = p[0];
        String payment = p[1];
        var page =
                kit.http()
                        .get("/mock-gateway/" + payment)
                        .expect(200)
                        .header("Cache-Control", "no-store")
                        .bodyContains("INR 180.00")
                        .bodyContains("INITIATED")
                        .bodyContains("action=\"/mock-gateway/" + payment + "/complete\"");
        assertTrue(page.header("Content-Type").startsWith("text/html"));
        assertNotNull(page.header("Content-Security-Policy"));

        kit.http()
                .post("/mock-gateway/" + payment + "/complete")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(utf8("outcome=SUCCESS"))
                .expect(303)
                .header("Location", "/bookings/" + booking);
        assertEquals(1, kit.callbacks().sent.size());
        var sent = kit.callbacks().sent.get(0);
        assertEquals("payment.succeeded", sent.type());
        assertEquals(18000, sent.amount());
        assertEquals(payment, sent.paymentId());
        assertEquals("gw_" + payment, sent.gatewayRef());
        assertEquals("INR", sent.currency());
        assertEquals(kit.clock().instant(), sent.occurredAt());

        kit.http()
                .post("/mock-gateway/" + payment + "/complete")
                .json(Map.of("outcome", "FAILURE"))
                .expect(303);
        assertEquals("payment.failed", kit.callbacks().sent.get(1).type());
        kit.http()
                .post("/mock-gateway/" + payment + "/complete")
                .json(Map.of("outcome", "MAYBE"))
                .expect(422)
                .jsonEquals("errors.outcome", "must be SUCCESS or FAILURE");
        kit.http()
                .post("/mock-gateway/" + payment + "/complete")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(utf8(""))
                .expect(422);
        kit.http()
                .post("/mock-gateway/" + payment + "/complete")
                .header("Cookie", "a=b")
                .header("Sec-Fetch-Site", "cross-site")
                .json(Map.of("outcome", "SUCCESS"))
                .expect(403);
        kit.http().get("/mock-gateway/nope").expect(404);
        kit.http()
                .post("/mock-gateway/nope/complete")
                .json(Map.of("outcome", "SUCCESS"))
                .expect(404);
        assertEquals(2, kit.callbacks().sent.size());
    }

    @Test
    void mockGatewayIsAbsentWhenDisabled() {
        PaymentFlow off = PaymentFlow.boot(false, k -> k.callbacks());
        try (SliceKit k = off.kit) {
            k.http().get("/mock-gateway/abc").expect(404);
            k.http()
                    .post("/mock-gateway/abc/complete")
                    .json(Map.of("outcome", "SUCCESS"))
                    .expect(404);
            assertTrue(k.callbacks().sent.isEmpty());
        }
    }

    @Test
    void payingOnTheMockPageConfirmsTheBookingThroughTheSignedWebhook() {
        PaymentFlow real = PaymentFlow.boot(true, PaymentFlow::delivering);
        try (SliceKit k = real.kit) {
            String token = k.audience("bo@example.com");
            String[] p = real.pending(token, "C4");
            k.http()
                    .post("/mock-gateway/" + p[1] + "/complete")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(utf8("outcome=SUCCESS"))
                    .expect(303)
                    .header("Location", "/bookings/" + p[0]);
            assertEquals("CONFIRMED", real.bookingStatus(p[0]));
            k.http().get("/mock-gateway/" + p[1]).expect(200).bodyContains("SUCCEEDED");
        }
    }
}
