package dev.thiru.bms.application;

import dev.thiru.bms.domain.port.UnitOfWork;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import dev.thiru.bms.ports.GatewayCallback;
import dev.thiru.bms.ports.Ids;
import java.time.Clock;

/**
 * The mock gateway's side of a payment: shows the pending payment and, on Pay or Fail, delivers a
 * signed webhook through {@link Sender} (the app calls itself over HTTP like a real gateway).
 */
public final class MockGatewayPayments {

    /** Delivers the event to the webhook endpoint and returns its HTTP status. */
    @FunctionalInterface
    public interface Sender extends GatewayCallback {
        int send(ProcessPaymentWebhook.Event event) throws Exception;

        /** Never called; only makes a lambda pick the legacy constructor over the bridge. */
        @Override
        default int send(GatewayCallback.Event event) {
            throw new UnsupportedOperationException();
        }
    }

    public record Pending(
            String paymentId,
            String bookingId,
            long amount,
            String currency,
            PaymentStatus status) {}

    private final UnitOfWork uow;
    private final Sender sender;
    private final Ids ids;
    private final Clock clock;

    public MockGatewayPayments(UnitOfWork uow, Sender sender, Ids ids, Clock clock) {
        this.uow = uow;
        this.sender = sender;
        this.ids = ids;
        this.clock = clock;
    }

    /** Step 0 bridge to {@code ports.GatewayCallback}; deleted with this class in wave 4. */
    public MockGatewayPayments(UnitOfWork uow, GatewayCallback callback, Ids ids, Clock clock) {
        this(
                uow,
                (Sender)
                        e ->
                                callback.send(
                                        new GatewayCallback.Event(
                                                e.eventId(),
                                                e.type()
                                                                == ProcessPaymentWebhook.Type
                                                                        .PAYMENT_SUCCEEDED
                                                        ? GatewayCallback.SUCCEEDED
                                                        : GatewayCallback.FAILED,
                                                e.paymentId(),
                                                e.gatewayRef(),
                                                e.amount(),
                                                e.currency(),
                                                e.occurredAt())),
                ids,
                clock);
    }

    public Pending find(String paymentId) {
        Payment p = load(paymentId);
        return new Pending(p.id(), p.bookingId(), p.amount(), p.currency(), p.status());
    }

    /** Returns the booking id to redirect to. A rejected webhook surfaces as an exception. */
    public String complete(String paymentId, boolean success) {
        Payment p = load(paymentId);
        ProcessPaymentWebhook.Event event =
                new ProcessPaymentWebhook.Event(
                        ids.newId(),
                        success
                                ? ProcessPaymentWebhook.Type.PAYMENT_SUCCEEDED
                                : ProcessPaymentWebhook.Type.PAYMENT_FAILED,
                        p.id(),
                        p.gatewayRef(),
                        p.amount(),
                        p.currency(),
                        clock.instant());
        int status;
        try {
            status = sender.send(event);
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
        return uow.inTransaction(r -> r.payments().findById(paymentId))
                .orElseThrow(() -> DomainException.notFound("Payment"));
    }
}
