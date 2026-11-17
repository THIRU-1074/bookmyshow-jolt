package dev.thiru.bms.adapters.ids;

import dev.thiru.bms.ports.Ids;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Unguessable ids (SPEC 2): random UUIDv4 strings and a {@link SecureRandom} for codes. */
public final class SecureIds implements Ids {

    private final SecureRandom random = new SecureRandom();

    @Override
    public String newId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public RandomGenerator random() {
        return random;
    }
}
