package dev.thiru.bms.application;

import dev.thiru.bms.kernel.DomainException;

/**
 * Result of a transaction body that must commit its writes even though the caller gets an error
 * (lazy expiry writes, then reports 410). Throwing inside the transaction would roll them back.
 *
 * <p>Kept as a test fixture until wave 4; delete with {@code CreateBooking} and {@code Checkout}.
 * Production uses {@code features.booking.Outcome}.
 */
record Outcome<T>(T value, DomainException failure) {

    static <T> Outcome<T> ok(T value) {
        return new Outcome<>(value, null);
    }

    static <T> Outcome<T> fail(DomainException failure) {
        return new Outcome<>(null, failure);
    }

    T orThrow() {
        if (failure != null) {
            throw failure;
        }
        return value;
    }
}
