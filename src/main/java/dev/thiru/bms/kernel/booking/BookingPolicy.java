package dev.thiru.bms.kernel.booking;

import java.time.Duration;

/** Tunable limits from SPEC section 5.6 and the environment table. */
public record BookingPolicy(
        Duration holdTtl, Duration paymentWindow, int maxSeats, int maxCheckouts) {

    public BookingPolicy {
        if (holdTtl.isZero() || holdTtl.isNegative()) {
            throw new IllegalArgumentException("holdTtl must be positive");
        }
        if (paymentWindow.isZero() || paymentWindow.isNegative()) {
            throw new IllegalArgumentException("paymentWindow must be positive");
        }
        if (maxSeats < 1 || maxCheckouts < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }

    public static BookingPolicy defaults() {
        return new BookingPolicy(Duration.ofMinutes(7), Duration.ofMinutes(5), 10, 3);
    }
}
