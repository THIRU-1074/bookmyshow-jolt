package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.ProcessPaymentWebhook;
import dev.thiru.bms.application.fakes.FakeWorld;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BookingApiTest {

    private final WebKit kit = new WebKit();
    private final String ada = kit.user("ada@example.com");

    private String auth() {
        return WebKit.bearer(ada);
    }

    @Test
    void holdReturnsServerComputedPrice() {
        kit.http
                .post("/api/holds")
                .header("Authorization", auth())
                .header("Idempotency-Key", "h1")
                .json(Map.of("showId", 9, "seats", List.of("A2", "B1"), "amount", 1))
                .expect(201)
                .jsonEquals("showId", 9)
                .jsonEquals("seats.0", "A2")
                .jsonEquals("amount", 40000)
                .jsonEquals("currency", "INR")
                .jsonEquals("expiresAt", FakeWorld.T0.plus(Duration.ofMinutes(7)).toString());
    }

    @Test
    void holdNeedsAnIdempotencyKey() {
        kit.http
                .post("/api/holds")
                .header("Authorization", auth())
                .json(Map.of("showId", 9, "seats", List.of("A2")))
                .expect(400)
                .jsonEquals("code", "IDEMPOTENCY");
        kit.http
                .post("/api/holds")
                .header("Authorization", auth())
                .header("Idempotency-Key", "x".repeat(129))
                .json(Map.of("showId", 9, "seats", List.of("A2")))
                .expect(400);
    }

    @Test
    void aReplayReturnsTheStoredResponseAndADifferentBodyIs422() {
        var first =
                kit.http
                        .post("/api/holds")
                        .header("Authorization", auth())
                        .header("Idempotency-Key", "same")
                        .json(Map.of("showId", 9, "seats", List.of("A2")))
                        .expect(201);
        var replay =
                kit.http
                        .post("/api/holds")
                        .header("Authorization", auth())
                        .header("Idempotency-Key", "same")
                        .json(Map.of("showId", 9, "seats", List.of("A2")))
                        .expect(201)
                        .header("Idempotency-Replayed", "true");
        assertArrayEquals(first.bytes(), replay.bytes());
        // A replay keeps its own request id, not the first request's.
        kit.http
                .post("/api/holds")
                .header("Authorization", auth())
                .header("Idempotency-Key", "same")
                .header("X-Request-Id", "second-try")
                .json(Map.of("showId", 9, "seats", List.of("A2")))
                .expect(201)
                .header("Idempotency-Replayed", "true")
                .header("X-Request-Id", "second-try");
        assertTrue(replay.header("Content-Type").startsWith("application/json"));
        assertEquals(1, kit.world.allHolds().size());

        kit.http
                .post("/api/holds")
                .header("Authorization", auth())
                .header("Idempotency-Key", "same")
                .json(Map.of("showId", 9, "seats", List.of("A3")))
                .header("X-Request-Id", "mismatch")
                .expect(422)
                .jsonEquals("code", "IDEMPOTENCY")
                .jsonEquals("requestId", "mismatch");
    }

    @Test
    void aFailedRequestReleasesItsKey() {
        kit.http
                .post("/api/holds")
                .header("Authorization", auth())
                .header("Idempotency-Key", "retry")
                .json(Map.of("showId", 9, "seats", List.of("Z9")))
                .expect(422)
                .jsonEquals("code", "VALIDATION");
        kit.http
                .post("/api/holds")
                .header("Authorization", auth())
                .header("Idempotency-Key", "retry")
                .json(Map.of("showId", 9, "seats", List.of("Z9")))
                .expect(422);
    }

    @Test
    void holdValidationAndConflicts() {
        kit.http
                .post("/api/holds")
                .header("Authorization", auth())
                .header("Idempotency-Key", kit.key())
                .json(Map.of())
                .expect(422)
                .jsonEquals("errors.showId", "is required")
                .jsonHas("errors.seats");
        Map<String, Object> nullSeat = new java.util.HashMap<>();
        nullSeat.put("showId", dev.thiru.bms.application.fakes.FakeWorld.SHOW_ID);
        nullSeat.put("seats", java.util.Arrays.asList("A2", null));
        kit.http
                .post("/api/holds")
                .header("Authorization", auth())
                .header("Idempotency-Key", kit.key())
                .json(nullSeat)
                .expect(422)
                .jsonEquals("code", "VALIDATION")
                .jsonEquals("errors.seats[1]", "is required");
        kit.hold(ada, "A2", "A3");
        String eve = kit.user("eve@example.com");
        kit.http
                .post("/api/holds")
                .header("Authorization", WebKit.bearer(eve))
                .header("Idempotency-Key", kit.key())
                .json(Map.of("showId", 9, "seats", List.of("A3", "A4")))
                .expect(409)
                .jsonEquals("code", "SEAT_UNAVAILABLE")
                .jsonEquals("conflicts.0", "A3");
        kit.http
                .post("/api/holds")
                .header("Authorization", auth())
                .header("Idempotency-Key", kit.key())
                .json(Map.of("showId", 404, "seats", List.of("A3")))
                .expect(404);
    }

    @Test
    void holdsAreRateLimitedPerUser() {
        for (int i = 0; i < 20; i++) {
            kit.hold(ada, "B" + (i % 10 + 1));
        }
        var limited =
                kit.http
                        .post("/api/holds")
                        .header("Authorization", auth())
                        .header("Idempotency-Key", kit.key())
                        .json(Map.of("showId", 9, "seats", List.of("B1")))
                        .expect(429)
                        .jsonEquals("code", "RATE_LIMITED");
        assertNotNull(limited.header("Retry-After"));
        // Another user has their own budget.
        kit.hold(kit.user("eve@example.com"), "A9");
    }

    @Test
    void releaseHoldIsOwnerOnly() {
        String hold = kit.hold(ada, "A2");
        kit.http.delete("/api/holds/" + hold).header("Authorization", auth()).expect(204);
        kit.http.delete("/api/holds/" + hold).header("Authorization", auth()).expect(404);
    }

    @Test
    void fullFlowFromHoldToTicketAndCancel() {
        String hold = kit.hold(ada, "A2", "A3");
        var booking =
                kit.http
                        .post("/api/bookings")
                        .header("Authorization", auth())
                        .header("Idempotency-Key", kit.key())
                        .json(Map.of("holdId", hold))
                        .expect(201)
                        .jsonEquals("status", "HELD")
                        .jsonEquals("amount", 30000)
                        .jsonEquals("show.movieTitle", "Kaveri Nights")
                        .jsonEquals("seats.0.id", "A2")
                        .jsonEquals("seats.0.price", 15000)
                        .jsonEquals("version", 0);
        String id = (String) booking.jsonField("id");
        assertTrue(((String) booking.jsonField("code")).startsWith("BK"));

        kit.http
                .get("/api/bookings/" + id + "/ticket")
                .header("Authorization", auth())
                .expect(409)
                .jsonEquals("code", "BOOKING_STATE");

        var checkout =
                kit.http
                        .post("/api/bookings/" + id + "/checkout")
                        .header("Authorization", auth())
                        .header("Idempotency-Key", kit.key())
                        .expect(201)
                        .jsonEquals("amount", 30000)
                        .jsonEquals("currency", "INR");
        String payment = (String) checkout.jsonField("paymentId");
        assertEquals("http://pay.test/" + payment, checkout.jsonField("checkoutUrl"));

        kit.http
                .post("/api/bookings/" + id + "/cancel")
                .header("Authorization", auth())
                .header("Idempotency-Key", kit.key())
                .expect(409)
                .jsonEquals("code", "PAYMENT_IN_PROGRESS");

        kit.webhook("evt-1", "payment.succeeded", payment, 30000)
                .expect(200)
                .jsonEquals("status", "CONFIRMED");
        kit.webhook("evt-1", "payment.succeeded", payment, 30000)
                .expect(200)
                .jsonEquals("duplicate", true);

        var ticket =
                kit.http
                        .get("/api/bookings/" + id + "/ticket")
                        .header("Authorization", auth())
                        .expect(200)
                        .bodyContains(booking.jsonField("code").toString())
                        .bodyContains("Kaveri Nights")
                        .bodyContains("<li>A2</li>")
                        .bodyContains("INR 300.00");
        assertTrue(ticket.header("Content-Type").startsWith("text/html"));

        kit.http
                .get("/api/bookings?status=CONFIRMED")
                .header("Authorization", auth())
                .expect(200)
                .jsonEquals("total", 1)
                .jsonEquals("items.0.id", id);
        kit.http
                .get("/api/bookings?status=HELD")
                .header("Authorization", auth())
                .expect(200)
                .jsonEquals("total", 0);
        kit.http.get("/api/bookings?status=NOPE").header("Authorization", auth()).expect(422);

        // More than 24 h before the show: full refund.
        kit.http
                .post("/api/bookings/" + id + "/cancel")
                .header("Authorization", auth())
                .header("Idempotency-Key", kit.key())
                .expect(200)
                .jsonEquals("status", "REFUNDED")
                .jsonEquals("refundAmount", 30000);
    }

    @Test
    void expiredHoldGives410AndTooManyCheckoutsGive429() {
        String hold = kit.hold(ada, "A2");
        kit.world.clock.advance(Duration.ofMinutes(8));
        kit.http
                .post("/api/bookings")
                .header("Authorization", auth())
                .header("Idempotency-Key", kit.key())
                .json(Map.of("holdId", hold))
                .expect(410)
                .jsonEquals("code", "HOLD_EXPIRED");

        String booking = kit.book(ada, kit.hold(ada, "A5"));
        for (int i = 0; i < 3; i++) {
            String payment = kit.checkout(ada, booking);
            kit.webhook("fail-" + i, "payment.failed", payment, 15000)
                    .expect(200)
                    .jsonEquals("status", "HELD");
        }
        kit.http
                .post("/api/bookings/" + booking + "/checkout")
                .header("Authorization", auth())
                .header("Idempotency-Key", kit.key())
                .expect(429)
                .jsonEquals("code", "LIMIT_EXCEEDED");
    }

    @Test
    void webhookRejectsBadSignaturesAndMismatchedAmounts() {
        String booking = kit.book(ada, kit.hold(ada, "A2"));
        String payment = kit.checkout(ada, booking);
        kit.http
                .post("/api/payments/webhook")
                .header("Bms-Timestamp", "1")
                .header("Bms-Signature", "v1=forged")
                .json(Map.of("eventId", "e"))
                .expect(401)
                .jsonEquals("code", "UNAUTHENTICATED");
        kit.http.post("/api/payments/webhook").json(Map.of()).expect(401);
        byte[] body = "{\"eventId\":\"e\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long now = kit.world.clock.instant().getEpochSecond();
        // Tampered body, wrong secret's format, stale and future timestamps are all 401.
        kit.signedWebhook(
                        "{\"eventId\":\"f\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        now,
                        WebKit.sign(now, body))
                .expect(401);
        kit.signedWebhook(body, now - 301, WebKit.sign(now - 301, body))
                .expect(401)
                .jsonEquals("code", "UNAUTHENTICATED");
        kit.signedWebhook(body, now + 301, WebKit.sign(now + 301, body)).expect(401);
        kit.signedWebhook(body, now - 300, WebKit.sign(now - 300, body)).expect(422);
        kit.webhook("evt-x", "payment.refunded", payment, 15000).expect(422).jsonHas("errors.type");
        kit.webhook("evt-y", "payment.succeeded", payment, 1)
                .expect(422)
                .jsonEquals("code", "VALIDATION");
        kit.http
                .get("/api/bookings/" + booking)
                .header("Authorization", auth())
                .expect(200)
                .jsonEquals("status", "HELD");
    }

    @Test
    void mockGatewayPageAndCompletion() {
        String booking = kit.book(ada, kit.hold(ada, "A2"));
        String payment = kit.checkout(ada, booking);
        var page =
                kit.http
                        .get("/mock-gateway/" + payment)
                        .expect(200)
                        .bodyContains("INR 150.00")
                        .bodyContains("action=\"/mock-gateway/" + payment + "/complete\"");
        assertTrue(page.header("Content-Type").startsWith("text/html"));
        assertNotNull(page.header("Content-Security-Policy"));

        kit.http
                .post("/mock-gateway/" + payment + "/complete")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("outcome=SUCCESS".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .expect(303)
                .header("Location", "/bookings/" + booking);
        assertEquals(1, kit.sentWebhooks.size());
        assertEquals(ProcessPaymentWebhook.Type.PAYMENT_SUCCEEDED, kit.sentWebhooks.get(0).type());
        assertEquals(15000, kit.sentWebhooks.get(0).amount());

        kit.http
                .post("/mock-gateway/" + payment + "/complete")
                .json(Map.of("outcome", "MAYBE"))
                .expect(422);
        kit.http
                .post("/mock-gateway/" + payment + "/complete")
                .header("Cookie", "a=b")
                .header("Sec-Fetch-Site", "cross-site")
                .json(Map.of("outcome", "SUCCESS"))
                .expect(403);
        kit.http.get("/mock-gateway/nope").expect(404);
    }

    @Test
    void mockGatewayIsAbsentWhenDisabled() {
        WebKit off = new WebKit(false, Duration.ofSeconds(15));
        off.http.get("/mock-gateway/abc").expect(404);
    }
}
