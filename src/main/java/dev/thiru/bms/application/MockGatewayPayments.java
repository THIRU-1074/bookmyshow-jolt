package dev.thiru.bms.application;

import dev.thiru.bms.domain.port.UnitOfWork;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import dev.thiru.bms.ports.Ids;
import java.time.Clock;

/**
 * The mock gateway's side of a payment: shows the pending payment and, on Pay or Fail, delivers a
 * signed webhook through {@link Sender} (the app calls itself over HTTP like a real gateway).
 */
public final class MockGatewayPayments {

    /** Delivers the event to the webhook endpoint and returns its HTTP status. */
    @FunctionalInterface
    public interface Sender {
        int send(ProcessPaymentWebhook.Event event) throws Exception;
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
