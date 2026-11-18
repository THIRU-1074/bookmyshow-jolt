package dev.thiru.bms.testing;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.contract.PaymentGatewayContract;
import dev.thiru.bms.ports.PaymentGateway;
import org.junit.jupiter.api.Test;

class RecordingGatewayContractTest extends PaymentGatewayContract {

    @Override
    protected PaymentGateway newGateway() {
        return new RecordingGateway();
    }

    @Override
    protected long failNextRefund(PaymentGateway gateway) {
        ((RecordingGateway) gateway).failRefunds = true;
        return 15000;
    }

    @Test
    void aFailedRefundIsNotRecorded() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.failRefunds = true;
        assertThrows(IllegalStateException.class, () -> gateway.refund("gw_p", 100));
        assertTrue(gateway.refunds.isEmpty());
    }
}
