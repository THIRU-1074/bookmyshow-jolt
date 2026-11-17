package dev.thiru.bms.adapters.auth;

import dev.thiru.bms.ports.PasswordHasher;

/** The domain hashing port on Jolt's PBKDF2 hasher (salted, 210 000 iterations by default). */
public final class Pbkdf2PasswordHasher implements PasswordHasher {

    @Override
    public String hash(String rawPassword) {
        return in.jolt.auth.PasswordHasher.hash(rawPassword);
    }

    @Override
    public boolean verify(String rawPassword, String hash) {
        try {
            return in.jolt.auth.PasswordHasher.verify(rawPassword, hash);
        } catch (RuntimeException malformed) {
            // A corrupt stored hash must read as a failed login, never as a 500.
            return false;
        }
    }
}
