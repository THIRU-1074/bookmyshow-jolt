package dev.thiru.bms.domain;

import java.time.Duration;
import java.time.Instant;

/** Time-based cancellation refund, SPEC section 4. */
public final class RefundPolicy {

    static final Duration FULL_BEFORE = Duration.ofHours(24);
    static final Duration HALF_BEFORE = Duration.ofHours(4);

    private RefundPolicy() {}

    /**
     * With t = startsAt - now: t > 24h refunds everything, 4h <= t <= 24h half rounded down, 0 < t
     * < 4h nothing. At or after the start the cancellation is refused.
     */
    public static long refundAmount(long amount, Instant startsAt, Instant now) {
        Duration t = Duration.between(now, startsAt);
        if (t.isZero() || t.isNegative()) {
            throw DomainException.showStarted();
        }
        if (t.compareTo(FULL_BEFORE) > 0) {
            return amount;
        }
        if (t.compareTo(HALF_BEFORE) >= 0) {
            return amount / 2;
        }
        return 0;
    }
}
