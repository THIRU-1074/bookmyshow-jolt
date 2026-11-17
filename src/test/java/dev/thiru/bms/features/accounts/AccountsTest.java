package dev.thiru.bms.features.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.ports.PasswordHasher;
import dev.thiru.bms.testing.MutableClock;
import dev.thiru.bms.testing.PlainHasher;
import dev.thiru.bms.testing.TestDb;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountsTest {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** Records the hashes login verifies against, to prove a miss still runs one verify. */
    static final class RecordingHasher implements PasswordHasher {
        final PlainHasher plain = new PlainHasher();
        final List<String> verified = new ArrayList<>();
        int hashes;

        @Override
        public String hash(String raw) {
            hashes++;
            return plain.hash(raw);
        }

        @Override
        public boolean verify(String raw, String hash) {
            verified.add(hash);
            return plain.verify(raw, hash);
        }
    }

    @TempDir Path dir;
    TestDb test;
    RecordingHasher hasher;
    MutableClock clock;
    Accounts accounts;

    @BeforeEach
    void open() {
        test = TestDb.open(dir);
        hasher = new RecordingHasher();
        clock = new MutableClock(T0);
        accounts = new Accounts(test.db(), hasher, clock);
    }

    @AfterEach
    void close() {
        test.close();
    }

    @Test
    void registerAlwaysCreatesAnAudienceAccount() {
        User u = accounts.register("Ada@Example.com", "correct horse", " Ada ");
        assertEquals("ada@example.com", u.email());
        assertEquals("Ada", u.name());
        assertEquals(Set.of(Role.AUDIENCE), u.roles());
        assertTrue(u.id() > 0);
        assertEquals("plain:correct horse", u.passwordHash());
        assertEquals(T0, u.createdAt());
        assertEquals(u, accounts.find(u.id()).orElseThrow());
    }

    @Test
    void registerReportsEveryInvalidField() {
        DomainException e =
                assertThrows(DomainException.class, () -> accounts.register("nope", "short", " "));
        assertEquals(Code.VALIDATION, e.code());
        assertEquals(Set.of("email", "password", "name"), e.errors().keySet());
        assertEquals("must be an email address", e.errors().get("email"));
        assertEquals(0, hasher.hashes, "nothing is hashed for an invalid request");
    }

    @Test
    void registerRejectsNullFields() {
        DomainException e =
                assertThrows(DomainException.class, () -> accounts.register(null, null, null));
        assertEquals(Set.of("email", "password", "name"), e.errors().keySet());
    }

    @Test
    void duplicateEmailIsAConflictIgnoringCase() {
        accounts.register("ada@example.com", "correct horse", "Ada");
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () -> accounts.register("ADA@example.com ", "another long one", "Ada2"));
        assertEquals(Code.CONFLICT, e.code());
        assertEquals("Email is already registered", e.getMessage());
    }

    @Test
    void loginFailsIdenticallyForUnknownEmailAndWrongPassword() {
        User u = accounts.register("ada@example.com", "correct horse", "Ada");
        assertEquals(u.id(), accounts.login(" ADA@example.com", "correct horse").id());
        DomainException wrong =
                assertThrows(DomainException.class, () -> accounts.login("ada@example.com", "bad"));
        DomainException unknown =
                assertThrows(DomainException.class, () -> accounts.login("who@example.com", "bad"));
        assertEquals(Code.UNAUTHENTICATED, wrong.code());
        assertEquals(Code.UNAUTHENTICATED, unknown.code());
        assertEquals(wrong.getMessage(), unknown.getMessage());
    }

    @Test
    void anUnknownEmailStillVerifiesAgainstADecoyHash() {
        accounts.register("ada@example.com", "correct horse", "Ada");
        hasher.verified.clear();
        assertThrows(DomainException.class, () -> accounts.login("who@example.com", "guess"));
        assertThrows(DomainException.class, () -> accounts.login(null, "guess"));
        assertEquals(2, hasher.verified.size(), "a miss costs one verify, like a wrong password");
        assertFalse(hasher.verified.get(0).equals("plain:correct horse"));
        assertEquals(hasher.verified.get(0), hasher.verified.get(1), "the decoy is made once");
    }

    @Test
    void aNullPasswordIsUnauthenticated() {
        accounts.register("ada@example.com", "correct horse", "Ada");
        DomainException e =
                assertThrows(DomainException.class, () -> accounts.login("ada@example.com", null));
        assertEquals(Code.UNAUTHENTICATED, e.code());
    }

    @Test
    void findOfAnUnknownIdIsEmpty() {
        assertTrue(accounts.find(999_999).isEmpty());
    }

    @Test
    void ensureAdminCreatesOnceAndKeepsTheExistingAccount() {
        assertTrue(accounts.ensureAdmin(" Root@Example.com", "twelve chars!"));
        assertFalse(accounts.ensureAdmin("root@example.com", "a different one!!"));
        User admin = accounts.login("root@example.com", "twelve chars!");
        assertEquals(Set.of(Role.ADMIN, Role.AUDIENCE), admin.roles());
        assertEquals("Administrator", admin.name());
    }

    @Test
    void ensureAdminRejectsAShortPassword() {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> accounts.ensureAdmin("root@example.com", "eleven char"));
        assertEquals("ADMIN_PASSWORD must be at least 12 characters", e.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> accounts.ensureAdmin("root@example.com", null));
        assertTrue(accounts.ensureAdmin("root@example.com", "twelve chars"));
    }
}
