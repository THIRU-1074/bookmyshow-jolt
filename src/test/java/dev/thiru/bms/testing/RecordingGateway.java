package dev.thiru.bms.testing;

import dev.thiru.bms.ports.PaymentGateway;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link PaymentGateway} as the mock gateway behaves: refs {@code gw_<paymentId>}, checkout URLs
 * {@code <base>/<paymentId>}. Records refunds as {@code ref:amount}; {@link #failRefunds} makes a
 * refund throw, as a gateway outage would. Held to {@code PaymentGatewayContract}.
 */
public final class RecordingGateway implements PaymentGateway {
    public final List<String> refunds = new CopyOnWriteArrayList<>();
    public final AtomicInteger sessions = new AtomicInteger();
    public volatile boolean failRefunds;
    private final String base;

    public RecordingGateway() {
        this("http://pay.test");
    }

    public RecordingGateway(String base) {
        this.base = base;
    }

    @Override
    public CheckoutSession createSession(String paymentId, long amount, String currency) {
        sessions.incrementAndGet();
        return new CheckoutSession("gw_" + paymentId, base + "/" + paymentId);
    }

    @Override
    public void refund(String gatewayRef, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("refund amount must not be negative");
        }
        if (failRefunds) {
            throw new IllegalStateException("gateway down");
        }
        refunds.add(gatewayRef + ":" + amount);
    }
}
