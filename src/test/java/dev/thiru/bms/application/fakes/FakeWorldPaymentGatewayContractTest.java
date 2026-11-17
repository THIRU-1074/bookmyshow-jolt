package dev.thiru.bms.application.fakes;

import dev.thiru.bms.contract.PaymentGatewayContract;
import dev.thiru.bms.ports.PaymentGateway;

/** FakeWorld's gateway, held to the contract MockPaymentGateway meets. */
class FakeWorldPaymentGatewayContractTest extends PaymentGatewayContract {

    @Override
    protected PaymentGateway newGateway() {
        return new FakeWorld();
    }

    @Override
    protected long failNextRefund(PaymentGateway gateway) {
        ((FakeWorld) gateway).failRefunds = true;
        return 15000;
    }
}
