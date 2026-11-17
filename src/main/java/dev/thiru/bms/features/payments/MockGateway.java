package dev.thiru.bms.features.payments;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.GatewayCallback;
import dev.thiru.bms.ports.Ids;
import java.time.Clock;

/**
 * The mock gateway's side of a payment: shows the pending payment and, on Pay or Fail, delivers a
 * gateway event through {@link GatewayCallback} (in production a signed POST to the app's own
 * webhook, as a real gateway would send).
 */
final class MockGateway {

    record Pending(
            String paymentId,
            String bookingId,
            long amount,
            String currency,
            PaymentStatus status) {}

    private final Db db;
    private final GatewayCallback callback;
    private final Ids ids;
    private final Clock clock;

    MockGateway(Db db, GatewayCallback callback, Ids ids, Clock clock) {
        this.db = db;
        this.callback = callback;
        this.ids = ids;
        this.clock = clock;
    }

    Pending find(String paymentId) {
        Payment p = load(paymentId);
        return new Pending(p.id(), p.bookingId(), p.amount(), p.currency(), p.status());
    }

    /** Returns the booking id to redirect to. A rejected or lost event is an exception. */
    String complete(String paymentId, boolean success) {
        Payment p = load(paymentId);
        GatewayCallback.Event event =
                new GatewayCallback.Event(
                        ids.newId(),
                        success ? GatewayCallback.SUCCEEDED : GatewayCallback.FAILED,
                        p.id(),
                        p.gatewayRef(),
                        p.amount(),
                        p.currency(),
                        clock.instant());
        int status;
        try {
            status = callback.send(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while delivering the webhook", e);
        } catch (Exception e) {
            throw new IllegalStateException("webhook delivery failed", e);
        }
        if (status / 100 != 2) {
            throw new IllegalStateException("webhook rejected with status " + status);
        }
        return p.bookingId();
    }

    private Payment load(String paymentId) {
        return db.inTransaction(tx -> PaymentSql.payment(tx, paymentId))
                .orElseThrow(() -> DomainException.notFound("Payment"));
    }
}
