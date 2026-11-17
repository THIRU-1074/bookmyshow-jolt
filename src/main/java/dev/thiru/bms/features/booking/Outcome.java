package dev.thiru.bms.features.booking;

import dev.thiru.bms.kernel.DomainException;

/**
 * Result of a transaction body that must commit its writes even though the caller gets an error
 * (lazy expiry writes, then reports 410). Throwing inside the transaction would roll them back.
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
