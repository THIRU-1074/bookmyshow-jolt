package dev.thiru.bms.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundPolicyTest {

    private static final Instant START = Instant.parse("2026-11-20T18:00:00Z");

    private record Case(String name, Duration untilStart, long amount, long expected) {}

    @Test
    void refundFollowsTheTimeBands() {
        long amount = 45000;
        List<Case> cases =
                List.of(
                        new Case("far ahead", Duration.ofDays(7), amount, 45000),
                        new Case(
                                "just over 24h", Duration.ofHours(24).plusMillis(1), amount, 45000),
                        new Case("exactly 24h", Duration.ofHours(24), amount, 22500),
                        new Case("mid band", Duration.ofHours(10), amount, 22500),
                        new Case("exactly 4h", Duration.ofHours(4), amount, 22500),
                        new Case("just under 4h", Duration.ofHours(4).minusMillis(1), amount, 0),
                        new Case("one minute before", Duration.ofMinutes(1), amount, 0),
                        new Case("one ms before", Duration.ofMillis(1), amount, 0));
        for (Case c : cases) {
            assertEquals(
                    c.expected(),
                    RefundPolicy.refundAmount(c.amount(), START, START.minus(c.untilStart())),
                    c.name());
        }
    }

    @Test
    void halfRefundRoundsDownToThePaisa() {
        Instant now = START.minus(Duration.ofHours(10));
        assertEquals(5000, RefundPolicy.refundAmount(10001, START, now));
        assertEquals(0, RefundPolicy.refundAmount(1, START, now));
        assertEquals(1, RefundPolicy.refundAmount(3, START, now));
        assertEquals(Long.MAX_VALUE / 2, RefundPolicy.refundAmount(Long.MAX_VALUE, START, now));
    }

    @Test
    void atOrAfterStartIsShowStarted() {
        for (Duration after : List.of(Duration.ZERO, Duration.ofMillis(1), Duration.ofHours(3))) {
            DomainException e =
                    assertThrows(
                            DomainException.class,
                            () -> RefundPolicy.refundAmount(45000, START, START.plus(after)));
            assertEquals(DomainException.Code.SHOW_STARTED, e.code());
        }
    }
}
