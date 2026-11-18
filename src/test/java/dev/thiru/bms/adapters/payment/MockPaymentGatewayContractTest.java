package dev.thiru.bms.adapters.payment;

import dev.thiru.bms.contract.PaymentGatewayContract;
import dev.thiru.bms.ports.PaymentGateway;
import java.net.URI;

/** The production mock gateway, held to the gateway contract. */
class MockPaymentGatewayContractTest extends PaymentGatewayContract {

    @Override
    protected PaymentGateway newGateway() {
        return new MockPaymentGateway(URI.create("http://localhost:8080/"));
    }

    /**
     * The mock has no outage mode: it refunds every valid amount synchronously (SPEC 4). The one
     * refund it fails is one it rejects, and that failure must throw like any other.
     */
    @Override
    protected long failNextRefund(PaymentGateway gateway) {
        return -1;
    }
}
