package dev.thiru.bms;

import java.time.Clock;

/** The one place that creates the system clock: allowed. */
final class Main {
    Clock clock = Clock.systemUTC();
}
