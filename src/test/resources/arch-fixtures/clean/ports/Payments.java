package dev.thiru.bms.ports;

import dev.thiru.bms.kernel.Money;
import java.time.Instant;

public interface Payments {
    String createSession(String paymentId, Money amount, Instant at);
}
