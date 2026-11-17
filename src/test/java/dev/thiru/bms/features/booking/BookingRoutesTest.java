package dev.thiru.bms.features.booking;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.SliceKit;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The slice's HTTP surface on {@link SliceKit} and the V2 seed: the production pipeline (RBAC gate,
 * problems, Idempotency-Key, the per-user hold limit) in front of {@link BookingRoutes}. The show
 * is the first 2D show in Audi 1 three days out: rows A to F CLASSIC at 18000 paise, G to J PRIME
 * at 28000.
 */
class BookingRoutesTest {

    @RegisterExtension
    final SliceKit kit =
            SliceKit.boot(
                    k ->
                            List.of(
                                    new BookingRoutes(
                                            k.db(),
                                            k.events(),
                                            k.ids(),
                                            k.clock(),
                                            k.policy(),
                                            k.gateway(),
                                            k.pipeline().idempotent(),
                                            k.pipeline().holdLimit())));

    final long show = showOn(3);
    final String ada = kit.audience("ada@example.com");

    private long showOn(int day) {
        Instant from = kit.seedDate().plusDays(day).atStartOfDay(SliceKit.IST).toInstant();
        return kit.db()
                .inTransaction(
                        tx ->
                                tx.queryOne(
                                                "SELECT id FROM show WHERE screen_id = 1 AND"
                                                        + " format = '2D' AND starts_at >= ?"
                                                        + " ORDER BY starts_at LIMIT 1",
                                                rs -> rs.getLong(1),
                                                Db.millis(from))
                                        .orElseThrow());
    }

    private static String auth(String token) {
        return SliceKit.bearer(token);
    }

    private String hold(String token, String... seats) {
        return (String)
                kit.http()
                        .post("/api/holds")
                        .header("Authorization", auth(token))
                        .header("Idempotency-Key", kit.key())
                        .json(Map.of("showId", show, "seats", List.of(seats)))
                        .expect(201)
                        .jsonField("id");
    }

    private String book(String token, String holdId) {
        return (String)
                kit.http()
                        .post("/api/bookings")
                        .header("Authorization", auth(token))
                        .header("Idempotency-Key", kit.key())
                        .json(Map.of("holdId", holdId))
                        .expect(201)
                        .jsonField("id");
    }

    private String checkout(String token, String bookingId) {
        return (String)
                kit.http()
                        .post("/api/bookings/" + bookingId + "/checkout")
                        .header("Authorization", auth(token))
                        .header("Idempotency-Key", kit.key())
                        .expect(201)
                        .jsonField("paymentId");
    }

    /** What the payments webhook writes on success (the payments slice owns it). */
    private void paid(String bookingId, String paymentId) {
        kit.db()
                .inTransaction(
                        tx -> {
                            tx.update(
                                    "UPDATE payment SET status = 'SUCCEEDED' WHERE id = ?",
                                    paymentId);
                            tx.update(
                                    "UPDATE booking SET status = 'CONFIRMED', version = version"
                                            + " + 1 WHERE id = ?",
                                    bookingId);
                            tx.update(
                                    "UPDATE seat_lock SET state = 'BOOKED', expires_at = NULL"
                                            + " WHERE booking_id = ?",
                                    bookingId);
                            return null;
                        });
    }

    private void failed(String bookingId, String paymentId) {
        kit.db()
                .inTransaction(
                        tx -> {
                            tx.update(
                                    "UPDATE payment SET status = 'FAILED' WHERE id = ?", paymentId);
                            tx.update(
                                    "UPDATE booking SET status = 'HELD', version = version + 1"
                                            + " WHERE id = ?",
                                    bookingId);
                            return null;
                        });
    }

    private int count(String table) {
        return kit.db()
                .inTransaction(
                        tx ->
                                tx.queryOne("SELECT count(*) FROM " + table, rs -> rs.getInt(1))
                                        .orElseThrow());
    }

    @Test
    void holdReturnsServerComputedPriceInTheContractFieldOrder() {
        var res =
                kit.http()
                        .post("/api/holds")
                        .header("Authorization", auth(ada))
                        .header("Idempotency-Key", "h1")
                        .json(Map.of("showId", show, "seats", List.of("A2", "G1"), "amount", 1))
                        .expect(201)
                        .header("Cache-Control", "no-store")
                        .jsonEquals("showId", show)
                        .jsonEquals("seats.0", "A2")
                        .jsonEquals("seats.1", "G1")
                        .jsonEquals("amount", 46000)
                        .jsonEquals("currency", "INR")
                        .jsonEquals(
                                "expiresAt",
                                kit.clock().instant().plus(Duration.ofMinutes(7)).toString());
        String id = (String) res.jsonField("id");
        assertEquals(
                "{\"id\":\""
                        + id
                        + "\",\"showId\":"
                        + show
                        + ",\"seats\":[\"A2\",\"G1\"],\"amount\":46000,\"currency\":\"INR\","
                        + "\"expiresAt\":\""
                        + kit.clock().instant().plus(Duration.ofMinutes(7))
                        + "\"}",
                res.text());
        assertEquals(1, kit.events().published.size(), "the Held event left after the commit");
    }

    @Test
    void holdNeedsAnIdempotencyKey() {
        kit.http()
                .post("/api/holds")
                .header("Authorization", auth(ada))
                .json(Map.of("showId", show, "seats", List.of("A2")))
                .expect(400)
                .jsonEquals("code", "IDEMPOTENCY");
        kit.http()
                .post("/api/holds")
                .header("Authorization", auth(ada))
                .header("Idempotency-Key", "x".repeat(129))
                .json(Map.of("showId", show, "seats", List.of("A2")))
                .expect(400);
        kit.http()
                .post("/api/holds")
                .header("Idempotency-Key", kit.key())
                .json(Map.of("showId", show, "seats", List.of("A2")))
                .expect(401);
    }

    @Test
    void aReplayReturnsTheStoredResponseAndADifferentBodyIs422() {
        var first =
                kit.http()
                        .post("/api/holds")
                        .header("Authorization", auth(ada))
                        .header("Idempotency-Key", "same")
                        .json(Map.of("showId", show, "seats", List.of("A2")))
                        .expect(201);
        var replay =
                kit.http()
                        .post("/api/holds")
                        .header("Authorization", auth(ada))
                        .header("Idempotency-Key", "same")
                        .json(Map.of("showId", show, "seats", List.of("A2")))
                        .expect(201)
                        .header("Idempotency-Replayed", "true");
        assertArrayEquals(first.bytes(), replay.bytes());
        kit.http()
                .post("/api/holds")
                .header("Authorization", auth(ada))
                .header("Idempotency-Key", "same")
                .header("X-Request-Id", "second-try")
                .json(Map.of("showId", show, "seats", List.of("A2")))
                .expect(201)
                .header("Idempotency-Replayed", "true")
                .header("X-Request-Id", "second-try");
        assertTrue(replay.header("Content-Type").startsWith("application/json"));
        assertEquals(1, count("seat_hold"));
        assertEquals(1, kit.events().published.size(), "a replay publishes nothing");

        kit.http()
                .post("/api/holds")
                .header("Authorization", auth(ada))
                .header("Idempotency-Key", "same")
                .json(Map.of("showId", show, "seats", List.of("A3")))
                .header("X-Request-Id", "mismatch")
                .expect(422)
                .jsonEquals("code", "IDEMPOTENCY")
                .jsonEquals("requestId", "mismatch");
    }

    @Test
    void aFailedRequestReleasesItsKey() {
        for (int i = 0; i < 2; i++) {
            kit.http()
                    .post("/api/holds")
                    .header("Authorization", auth(ada))
                    .header("Idempotency-Key", "retry")
                    .json(Map.of("showId", show, "seats", List.of("Z9")))
                    .expect(422)
                    .jsonEquals("code", "VALIDATION")
                    .jsonEquals("errors.seats[0]", "unknown seat");
        }
    }

    @Test
    void holdValidationAndConflicts() {
        kit.http()
                .post("/api/holds")
                .header("Authorization", auth(ada))
                .header("Idempotency-Key", kit.key())
                .json(Map.of())
                .expect(422)
                .jsonEquals("errors.showId", "is required")
                .jsonEquals("errors.seats", "must list at least one seat");
        Map<String, Object> nullSeat = new HashMap<>();
        nullSeat.put("showId", show);
        nullSeat.put("seats", Arrays.asList("A2", null));
        kit.http()
                .post("/api/holds")
                .header("Authorization", auth(ada))
                .header("Idempotency-Key", kit.key())
                .json(nullSeat)
                .expect(422)
                .jsonEquals("code", "VALIDATION")
                .jsonEquals("errors.seats[1]", "is required");
        hold(ada, "A2", "A3");
        String eve = kit.audience("eve@example.com");
        kit.http()
                .post("/api/holds")
                .header("Authorization", auth(eve))
                .header("Idempotency-Key", kit.key())
                .json(Map.of("showId", show, "seats", List.of("A3", "A4")))
                .expect(409)
                .jsonEquals("code", "SEAT_UNAVAILABLE")
                .jsonEquals("conflicts.0", "A3");
        kit.http()
                .post("/api/holds")
                .header("Authorization", auth(ada))
                .header("Idempotency-Key", kit.key())
                .json(Map.of("showId", 999_999, "seats", List.of("A3")))
                .expect(404)
                .jsonEquals("code", "NOT_FOUND");
    }

    @Test
    void holdsAreRateLimitedPerUser() {
        for (int i = 0; i < 20; i++) {
            hold(ada, "B" + (i % 10 + 1));
        }
        var limited =
                kit.http()
                        .post("/api/holds")
                        .header("Authorization", auth(ada))
                        .header("Idempotency-Key", kit.key())
                        .json(Map.of("showId", show, "seats", List.of("B1")))
                        .expect(429)
                        .jsonEquals("code", "RATE_LIMITED");
        assertNotNull(limited.header("Retry-After"));
        // Another user has their own budget.
        hold(kit.audience("eve@example.com"), "A9");
    }

    @Test
    void releaseHoldIsOwnerOnly() {
        String hold = hold(ada, "A2");
        String eve = kit.audience("eve@example.com");
        kit.http().delete("/api/holds/" + hold).header("Authorization", auth(eve)).expect(404);
        kit.http().delete("/api/holds/" + hold).header("Authorization", auth(ada)).expect(204);
        kit.http().delete("/api/holds/" + hold).header("Authorization", auth(ada)).expect(404);
        assertEquals(0, count("seat_lock"));
    }

    @Test
    void fullFlowFromHoldToTicketAndCancel() {
        String hold = hold(ada, "A2", "A3");
        var booking =
                kit.http()
                        .post("/api/bookings")
                        .header("Authorization", auth(ada))
                        .header("Idempotency-Key", kit.key())
                        .json(Map.of("holdId", hold))
                        .expect(201)
                        .header("Cache-Control", "no-store")
                        .jsonEquals("status", "HELD")
                        .jsonEquals("amount", 36000)
                        .jsonEquals("show.id", show)
                        .jsonEquals("show.cinemaName", "Koramangala Cineplex")
                        .jsonEquals("show.screenName", "Audi 1")
                        .jsonEquals("seats.0.id", "A2")
                        .jsonEquals("seats.0.category", "CLASSIC")
                        .jsonEquals("seats.0.price", 18000)
                        .jsonEquals("refundAmount", 0)
                        .jsonEquals("version", 0);
        String id = (String) booking.jsonField("id");
        String code = (String) booking.jsonField("code");
        assertTrue(code.startsWith("BK"));
        String movie = (String) booking.jsonField("show.movieTitle");
        assertEquals("10:00", booking.jsonField("show.localTime"));

        kit.http()
                .get("/api/bookings/" + id + "/ticket")
                .header("Authorization", auth(ada))
                .expect(409)
                .jsonEquals("code", "BOOKING_STATE");

        var checkout =
                kit.http()
                        .post("/api/bookings/" + id + "/checkout")
                        .header("Authorization", auth(ada))
                        .header("Idempotency-Key", kit.key())
                        .expect(201)
                        .header("Cache-Control", "no-store")
                        .jsonEquals("amount", 36000)
                        .jsonEquals("currency", "INR");
        String payment = (String) checkout.jsonField("paymentId");
        assertEquals("http://pay.test/" + payment, checkout.jsonField("checkoutUrl"));

        kit.http()
                .post("/api/bookings/" + id + "/cancel")
                .header("Authorization", auth(ada))
                .header("Idempotency-Key", kit.key())
                .expect(409)
                .jsonEquals("code", "PAYMENT_IN_PROGRESS");

        paid(id, payment);

        var ticket =
                kit.http()
                        .get("/api/bookings/" + id + "/ticket")
                        .header("Authorization", auth(ada))
                        .expect(200)
                        .header("Cache-Control", "no-store")
                        .bodyContains("Ticket <span class=\"ticket-code\">" + code + "</span>")
                        .bodyContains("<dd>" + movie + "</dd>")
                        .bodyContains("<dd>Audi 1</dd>")
                        .bodyContains("<dd>" + kit.seedDate().plusDays(3) + "</dd>")
                        .bodyContains("<dd>" + LocalTime.of(10, 0) + "</dd>")
                        .bodyContains("<li>A2</li><li>A3</li>")
                        .bodyContains("INR 360.00");
        assertTrue(ticket.header("Content-Type").startsWith("text/html"));

        kit.http()
                .get("/api/bookings?status=CONFIRMED")
                .header("Authorization", auth(ada))
                .expect(200)
                .jsonEquals("total", 1)
                .jsonEquals("items.0.id", id)
                .jsonEquals("items.0.status", "CONFIRMED");
        kit.http()
                .get("/api/bookings?status=held")
                .header("Authorization", auth(ada))
                .expect(200)
                .jsonEquals("total", 0);
        kit.http().get("/api/bookings?status=NOPE").header("Authorization", auth(ada)).expect(422);

        // More than 24 h before the show: full refund.
        kit.http()
                .post("/api/bookings/" + id + "/cancel")
                .header("Authorization", auth(ada))
                .header("Idempotency-Key", kit.key())
                .expect(200)
                .header("Cache-Control", "no-store")
                .jsonEquals("status", "REFUNDED")
                .jsonEquals("refundAmount", 36000)
                .jsonEquals("version", 3);
        assertEquals(List.of("gw_" + payment + ":36000"), kit.gateway().refunds);
    }

    @Test
    void bookingNeedsAHoldId() {
        for (Map<String, Object> body :
                List.<Map<String, Object>>of(Map.of(), Map.of("holdId", " "))) {
            kit.http()
                    .post("/api/bookings")
                    .header("Authorization", auth(ada))
                    .header("Idempotency-Key", kit.key())
                    .json(body)
                    .expect(422)
                    .jsonEquals("errors.holdId", "is required");
        }
    }

    @Test
    void expiredHoldGives410AndTooManyCheckoutsGive429() {
        String hold = hold(ada, "A2");
        kit.mutableClock().advance(Duration.ofMinutes(8));
        kit.http()
                .post("/api/bookings")
                .header("Authorization", auth(ada))
                .header("Idempotency-Key", kit.key())
                .json(Map.of("holdId", hold))
                .expect(410)
                .jsonEquals("code", "HOLD_EXPIRED");
        assertEquals(0, count("seat_lock"), "the lazy expiry committed");

        String booking = book(ada, hold(ada, "A5"));
        for (int i = 0; i < 3; i++) {
            failed(booking, checkout(ada, booking));
        }
        kit.http()
                .post("/api/bookings/" + booking + "/checkout")
                .header("Authorization", auth(ada))
                .header("Idempotency-Key", kit.key())
                .expect(429)
                .jsonEquals("code", "LIMIT_EXCEEDED");
    }

    @Test
    void listIsTheCallersOwnNewestFirstAndPaged() {
        String first = book(ada, hold(ada, "A2"));
        kit.mutableClock().advance(Duration.ofSeconds(1));
        String second = book(ada, hold(ada, "A3"));
        String eve = kit.audience("eve@example.com");
        book(eve, hold(eve, "A4"));
        kit.http()
                .get("/api/bookings?size=1")
                .header("Authorization", auth(ada))
                .expect(200)
                .header("Cache-Control", "no-store")
                .jsonEquals("total", 2)
                .jsonEquals("page", 1)
                .jsonEquals("size", 1)
                .jsonEquals("totalPages", 2)
                .jsonEquals("items.0.id", second);
        kit.http()
                .get("/api/bookings?size=1&page=2")
                .header("Authorization", auth(ada))
                .expect(200)
                .jsonEquals("items.0.id", first);
        kit.http()
                .get("/api/bookings?size=101")
                .header("Authorization", auth(ada))
                .expect(422)
                .jsonEquals("errors.size", "must be 1 to 100");
    }

    @Test
    void aStrangerGets404AndAnAdminMayReadButNotAct() {
        String booking = book(ada, hold(ada, "A5"));
        String eve = kit.audience("eve@example.com");
        String admin = kit.admin();
        for (String token : List.of(eve, admin)) {
            kit.http()
                    .post("/api/bookings/" + booking + "/checkout")
                    .header("Authorization", auth(token))
                    .header("Idempotency-Key", kit.key())
                    .expect(404)
                    .jsonEquals("code", "NOT_FOUND");
            kit.http()
                    .post("/api/bookings/" + booking + "/cancel")
                    .header("Authorization", auth(token))
                    .header("Idempotency-Key", kit.key())
                    .expect(404);
            kit.http()
                    .get("/api/bookings/" + booking + "/ticket")
                    .header("Authorization", auth(token))
                    .expect(404);
            kit.http()
                    .get("/api/bookings")
                    .header("Authorization", auth(token))
                    .expect(200)
                    .jsonEquals("total", 0);
        }
        kit.http()
                .get("/api/bookings/" + booking)
                .header("Authorization", auth(eve))
                .expect(404)
                .jsonEquals("code", "NOT_FOUND");
        kit.http()
                .get("/api/bookings/" + booking)
                .header("Authorization", auth(admin))
                .expect(200)
                .jsonEquals("id", booking)
                .jsonEquals("status", "HELD");
        kit.http().get("/api/bookings/" + booking).expect(401);
        kit.http().get("/api/bookings/nope").header("Authorization", auth(admin)).expect(404);
    }
}
