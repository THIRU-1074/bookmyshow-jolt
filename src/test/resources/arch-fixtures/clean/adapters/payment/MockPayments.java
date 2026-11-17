package dev.thiru.bms.adapters.payment;

import dev.thiru.bms.kernel.Money;
import dev.thiru.bms.ports.Payments;
import java.time.Instant;

public final class MockPayments implements Payments {
    public String createSession(String paymentId, Money amount, Instant at) {
        return "mock_" + paymentId;
    }
}
