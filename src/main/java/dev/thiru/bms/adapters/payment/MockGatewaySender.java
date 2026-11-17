package dev.thiru.bms.adapters.payment;

import dev.thiru.bms.application.MockGatewayPayments;
import dev.thiru.bms.application.ProcessPaymentWebhook;

/** Delivers the mock gateway's webhook through {@link MockGatewayClient} (signed, real HTTP). */
public final class MockGatewaySender implements MockGatewayPayments.Sender {

    private final MockGatewayClient client;

    public MockGatewaySender(MockGatewayClient client) {
        this.client = client;
    }

    @Override
    public int send(ProcessPaymentWebhook.Event event) throws Exception {
        return client.post(toWire(event));
    }

    static MockGatewayClient.Event toWire(ProcessPaymentWebhook.Event e) {
        String type =
                switch (e.type()) {
                    case PAYMENT_SUCCEEDED -> "payment.succeeded";
                    case PAYMENT_FAILED -> "payment.failed";
                };
        return new MockGatewayClient.Event(
                e.eventId(),
                type,
                e.paymentId(),
                e.gatewayRef(),
                e.amount(),
                e.currency(),
                e.occurredAt());
    }
}
