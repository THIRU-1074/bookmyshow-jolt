package dev.thiru.bms.testing;

import dev.thiru.bms.ports.PasswordHasher;

/** Test hasher: {@code plain:<raw>}, so fixtures can write hashes directly. */
public final class PlainHasher implements PasswordHasher {
    public static final String PREFIX = "plain:";

    @Override
    public String hash(String raw) {
        return PREFIX + raw;
    }

    @Override
    public boolean verify(String raw, String hash) {
        return hash.equals(PREFIX + raw);
    }
}
