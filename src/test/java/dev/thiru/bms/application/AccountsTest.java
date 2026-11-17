package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.ports.PasswordHasher;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountsTest {

    static final PasswordHasher HASHER =
            new PasswordHasher() {
                public String hash(String raw) {
                    return "h:" + raw;
                }

                public boolean verify(String raw, String hash) {
                    return hash.equals("h:" + raw);
                }
            };

    FakeWorld w;
    Accounts accounts;

    @BeforeEach
    void setUp() {
        w = FakeWorld.seeded();
        accounts = new Accounts(w, HASHER, w.clock);
    }

    @Test
    void registerAlwaysCreatesAPlainUser() {
        User u = accounts.register("Ada@Example.com", "correct horse", "Ada");
        assertEquals("ada@example.com", u.email());
        assertEquals(Set.of(Role.AUDIENCE), u.roles());
        assertTrue(u.id() > 0);
        assertEquals("h:correct horse", u.passwordHash());
    }

    @Test
    void registerReportsEveryInvalidField() {
        DomainException e =
                assertThrows(DomainException.class, () -> accounts.register("nope", "short", " "));
        assertEquals(Code.VALIDATION, e.code());
        assertEquals(Set.of("email", "password", "name"), e.errors().keySet());
    }

    @Test
    void duplicateEmailIsAConflictIgnoringCase() {
        accounts.register("ada@example.com", "correct horse", "Ada");
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () -> accounts.register("ADA@example.com", "another long one", "Ada2"));
        assertEquals(Code.CONFLICT, e.code());
    }

    @Test
    void loginFailsIdenticallyForUnknownEmailAndWrongPassword() {
        User u = accounts.register("ada@example.com", "correct horse", "Ada");
        assertEquals(u.id(), accounts.login("ADA@example.com", "correct horse").id());
        DomainException wrong =
                assertThrows(DomainException.class, () -> accounts.login("ada@example.com", "bad"));
        DomainException unknown =
                assertThrows(DomainException.class, () -> accounts.login("who@example.com", "bad"));
        assertEquals(Code.UNAUTHENTICATED, wrong.code());
        assertEquals(wrong.getMessage(), unknown.getMessage());
        assertEquals(Code.UNAUTHENTICATED, unknown.code());
    }

    @Test
    void ensureAdminCreatesOnceAndKeepsTheExistingAccount() {
        assertTrue(accounts.ensureAdmin("root@example.com", "twelve chars!"));
        assertFalse(accounts.ensureAdmin("root@example.com", "a different one!!"));
        User admin = accounts.login("root@example.com", "twelve chars!");
        assertEquals(Set.of(Role.ADMIN, Role.AUDIENCE), admin.roles());
    }

    @Test
    void ensureAdminRejectsAShortPassword() {
        assertThrows(
                IllegalArgumentException.class,
                () -> accounts.ensureAdmin("root@example.com", "short"));
    }
}
