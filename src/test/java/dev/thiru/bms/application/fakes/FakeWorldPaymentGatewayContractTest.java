package dev.thiru.bms.application.fakes;

import dev.thiru.bms.contract.PaymentGatewayContract;
import dev.thiru.bms.domain.port.PaymentGateway;

/** FakeWorld's gateway, held to the contract MockPaymentGateway meets. */
class FakeWorldPaymentGatewayContractTest extends PaymentGatewayContract {

    @Override
    protected PaymentGateway newGateway() {
        return new FakeWorld();
    }
}
