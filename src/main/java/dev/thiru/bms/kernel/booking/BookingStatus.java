package dev.thiru.bms.kernel.booking;

import java.util.Set;

/** Booking lifecycle states. The {@link #next()} table is the diagram of SPEC section 4. */
public enum BookingStatus {
    HELD,
    PAYMENT_PENDING,
    CONFIRMED,
    EXPIRED,
    CANCELLED,
    REFUNDED;

    public Set<BookingStatus> next() {
        return switch (this) {
            case HELD -> Set.of(PAYMENT_PENDING, EXPIRED, CANCELLED);
            case PAYMENT_PENDING -> Set.of(CONFIRMED, HELD, EXPIRED, CANCELLED);
            case CONFIRMED -> Set.of(REFUNDED, CANCELLED);
            case EXPIRED, CANCELLED, REFUNDED -> Set.of();
        };
    }

    public boolean canTransitionTo(BookingStatus to) {
        return next().contains(to);
    }

    public boolean isTerminal() {
        return next().isEmpty();
    }

    /** HELD and PAYMENT_PENDING are the states that still own seat locks and can expire. */
    public boolean isOpen() {
        return this == HELD || this == PAYMENT_PENDING;
    }
}
