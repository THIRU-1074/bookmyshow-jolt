package dev.thiru.bms.kernel.booking;

import dev.thiru.bms.kernel.Money;

public enum BookingState {
    HELD;

    Money zero() {
        return new Money(0);
    }
}
