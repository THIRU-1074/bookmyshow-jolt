package dev.thiru.bms.adapters.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.domain.port.PasswordHasher;
import org.junit.jupiter.api.Test;

class Pbkdf2PasswordHasherTest {

    private final PasswordHasher hasher = new Pbkdf2PasswordHasher();

    @Test
    void verifiesTheRightPasswordOnly() {
        String hash = hasher.hash("correct-horse-battery");
        assertTrue(hasher.verify("correct-horse-battery", hash));
        assertFalse(hasher.verify("correct-horse-battery!", hash));
    }

    @Test
    void saltsEveryHashAndNeverStoresThePassword() {
        String a = hasher.hash("correct-horse-battery");
        String b = hasher.hash("correct-horse-battery");
        assertNotEquals(a, b);
        assertFalse(a.contains("correct-horse-battery"));
    }

    @Test
    void rejectsGarbageHashesWithoutThrowing() {
        assertFalse(hasher.verify("correct-horse-battery", "not-a-hash"));
        assertFalse(hasher.verify("correct-horse-battery", ""));
    }
}
