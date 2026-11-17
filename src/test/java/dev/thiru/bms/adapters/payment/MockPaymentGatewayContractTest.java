package dev.thiru.bms.adapters.payment;

import dev.thiru.bms.contract.PaymentGatewayContract;
import dev.thiru.bms.domain.port.PaymentGateway;
import java.net.URI;

/** The production mock gateway, held to the gateway contract. */
class MockPaymentGatewayContractTest extends PaymentGatewayContract {

    @Override
    protected PaymentGateway newGateway() {
        return new MockPaymentGateway(URI.create("http://localhost:8080/"));
    }
}
