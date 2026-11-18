package dev.thiru.bms.ports;

import java.util.random.RandomGenerator;

/** Random identifiers, behind a port so tests are deterministic. */
public interface Ids {

    /** A random UUIDv4 string for holds, bookings and payments. */
    String newId();

    /** Source for booking codes; production uses a {@code SecureRandom}. */
    RandomGenerator random();
}
