package dev.thiru.bms.features.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.thiru.bms.features.booking.ExpireSweep;
import dev.thiru.bms.features.payments.ProcessWebhook.Event;
import dev.thiru.bms.features.payments.ProcessWebhook.Type;
import dev.thiru.bms.testing.SliceKit;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A success webhook races the sweep for the same booking, on SQLite with virtual threads. The
 * sweeper's clock runs 8 minutes ahead, so it sees the booking expired while the webhook sees it
 * live: whoever takes the writer lock first wins. Either way the booking ends in exactly one
 * consistent state, and no seat is ever sold twice.
 */
class WebhookSweepRaceTest {

    static final int ROUNDS = 10;

    final PaymentFlow flow = PaymentFlow.boot();
    final SliceKit kit = flow.kit;
    final ProcessWebhook webhook =
            new ProcessWebhook(kit.db(), kit.gateway(), kit.events(), kit.clock());
    final ExpireSweep sweep =
            new ExpireSweep(
                    kit.db(), kit.events(), Clock.offset(kit.clock(), Duration.ofMinutes(8)));

    @AfterEach
    void close() {
        kit.close();
    }

    @Test
    void aSuccessAndTheSweepNeverBothWin() throws Exception {
        int confirmed = 0;
        int expired = 0;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < ROUNDS; i++) {
                List<String> seats = List.of("C" + (i + 1), "D" + (i + 1));
                String buyer = kit.audience("buyer" + i + "@example.com");
                String[] p = flow.pending(buyer, seats.toArray(String[]::new));
                CountDownLatch start = new CountDownLatch(1);
                Future<?> pay =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return webhook.execute(
                                            new Event(
                                                    "race-" + seats.get(0),
                                                    Type.PAYMENT_SUCCEEDED,
                                                    p[1],
                                                    "gw_" + p[1],
                                                    36000,
                                                    "INR",
                                                    kit.clock().instant()));
                                });
                Future<?> swept =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return sweep.run();
                                });
                start.countDown();
                pay.get();
                swept.get();

                String status = flow.bookingStatus(p[0]);
                String rival = kit.audience("rival" + i + "@example.com");
                int rehold =
                        kit.http()
                                .post("/api/holds")
                                .header("Authorization", SliceKit.bearer(rival))
                                .header("Idempotency-Key", kit.key())
                                .json(Map.of("showId", flow.show, "seats", seats))
                                .send()
                                .status();
                switch (status) {
                    case "CONFIRMED" -> {
                        confirmed++;
                        assertEquals("SUCCEEDED", flow.paymentStatus(p[1]));
                        assertEquals(0, flow.refunded(p[1]));
                        assertEquals(409, rehold, "a confirmed seat cannot be held again");
                    }
                    case "EXPIRED" -> {
                        expired++;
                        assertEquals("REFUNDED", flow.paymentStatus(p[1]));
                        assertEquals(36000, flow.refunded(p[1]), "a full refund");
                        assertTrue(kit.gateway().refunds.contains("gw_" + p[1] + ":36000"));
                        assertEquals(201, rehold, "expired seats are free again");
                    }
                    default -> fail("round " + i + " ended " + status);
                }
            }
        }
        assertEquals(ROUNDS, confirmed + expired);
        assertEquals(expired, kit.gateway().refunds.size(), "one refund per expired booking");
        long bookedTwice =
                kit.db()
                        .inTransaction(
                                tx ->
                                        tx.queryOne(
                                                        "SELECT count(*) FROM (SELECT seat_id FROM"
                                                                + " seat_lock WHERE show_id = ?"
                                                                + " GROUP BY seat_id HAVING"
                                                                + " count(*) > 1)",
                                                        rs -> rs.getLong(1),
                                                        flow.show)
                                                .orElseThrow());
        assertEquals(0, bookedTwice);
    }
}
