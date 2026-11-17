package dev.thiru.bms.domain.port;

/** Outbound payment provider. Inbound results arrive as webhooks, not through this port. */
public interface PaymentGateway {

    record CheckoutSession(String gatewayRef, String checkoutUrl) {}

    CheckoutSession createSession(String paymentId, long amount, String currency);

    /**
     * Refunds synchronously (the mock gateway does); throws on failure so the transaction rolls
     * back.
     */
    void refund(String gatewayRef, long amount);
}
