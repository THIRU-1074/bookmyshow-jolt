package dev.thiru.bms.testing;

import dev.thiru.bms.contract.PaymentGatewayContract;
import dev.thiru.bms.ports.PaymentGateway;

class RecordingGatewayContractTest extends PaymentGatewayContract {

    @Override
    protected PaymentGateway newGateway() {
        return new RecordingGateway();
    }
}
