package dev.thiru.bms.kernel;

import java.time.Clock;
import java.time.LocalDate;

final class Stamps {
    long millis() {
        return System.currentTimeMillis();
    }

    LocalDate today() {
        return LocalDate.now(java.time.ZoneOffset.UTC);
    }

    Clock own() {
        return Clock.systemUTC();
    }
}
