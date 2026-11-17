package dev.thiru.bms.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.ports.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What Accounts relies on from a password hasher. Salting is tested on the real adapter only. */
public abstract class PasswordHasherContract {

    private static final String UNICODE = "pässwörd-नमस्ते";

    private PasswordHasher hasher;

    protected abstract PasswordHasher newHasher();

    @BeforeEach
    protected void create() {
        hasher = newHasher();
    }

    @Test
    protected void theHashIsNotThePasswordAndVerifiesIt() {
        String hash = hasher.hash("correct-horse-battery");
        assertNotEquals("correct-horse-battery", hash);
        assertTrue(hasher.verify("correct-horse-battery", hash));
    }

    @Test
    protected void aWrongPasswordDoesNotVerify() {
        String hash = hasher.hash("correct-horse-battery");
        assertFalse(hasher.verify("correct-horse-batterY", hash));
        assertFalse(hasher.verify("", hash));
    }

    @Test
    protected void everyHashOfAUnicodePasswordVerifies() {
        String a = hasher.hash(UNICODE);
        String b = hasher.hash(UNICODE);
        assertTrue(hasher.verify(UNICODE, a));
        assertTrue(hasher.verify(UNICODE, b));
    }

    @Test
    protected void aMalformedStoredHashIsAFailedCheckNotAnError() {
        assertFalse(hasher.verify("anything", "not-a-hash"));
        assertFalse(hasher.verify("anything", ""));
    }
}
