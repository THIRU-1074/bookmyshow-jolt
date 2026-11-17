package dev.thiru.bms.adapters.payment;

import dev.thiru.bms.ports.GatewayCallback;

/** Delivers the mock gateway's webhook through {@link MockGatewayClient} (signed, real HTTP). */
public final class MockGatewaySender implements GatewayCallback {

    private final MockGatewayClient client;

    public MockGatewaySender(MockGatewayClient client) {
        this.client = client;
    }

    @Override
    public int send(Event event) throws Exception {
        return client.post(toWire(event));
    }

    static MockGatewayClient.Event toWire(Event e) {
        return new MockGatewayClient.Event(
                e.eventId(),
                e.type(),
                e.paymentId(),
                e.gatewayRef(),
                e.amount(),
                e.currency(),
                e.occurredAt());
    }
}
