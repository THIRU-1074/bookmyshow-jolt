package dev.thiru.bms.features.booking;

import java.time.Clock;
import java.time.Instant;

final class Expiry {
    private final Clock clock;

    Expiry(Clock clock) {
        this.clock = clock;
    }

    // The injected clock, a mention in a comment (Instant.now()) and a string are all fine.
    Instant fine() {
        String s = "Instant.now()";
        return Instant.now(clock);
    }

    Instant late() {
        return Instant.now();
    }
}
