package dev.thiru.bms.testing;

import dev.thiru.bms.ports.Ids;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

/**
 * Deterministic {@link Ids}: {@code id-1}, {@code id-2}, ... and a seeded random source for booking
 * codes, so two runs of the same script give the same ids and codes. Thread-safe. Held to {@code
 * IdGeneratorContract}.
 */
public final class SequentialIds implements Ids {
    private final AtomicInteger next = new AtomicInteger();
    private final Random random = new Random(42);

    @Override
    public String newId() {
        return "id-" + next.incrementAndGet();
    }

    @Override
    public RandomGenerator random() {
        return random;
    }
}
