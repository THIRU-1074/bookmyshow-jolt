package dev.thiru.bms.features.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.TestDb;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountsSqlTest {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir Path dir;
    TestDb test;
    Db db;

    @BeforeEach
    void open() {
        test = TestDb.open(dir);
        db = test.db();
    }

    @AfterEach
    void close() {
        test.close();
    }

    private static User user(String email, Role... roles) {
        return new User(0, email, "plain:x", "Ada", Set.of(roles), T0);
    }

    @Test
    void insertAssignsAnIdAndRoundTripsEveryColumn() {
        User saved =
                db.inTransaction(
                        tx -> AccountsSql.insert(tx, user("ada@example.com", Role.AUDIENCE)));
        assertTrue(saved.id() > 0);
        assertEquals(saved, db.inTransaction(tx -> AccountsSql.findById(tx, saved.id())).get());
        assertEquals(
                saved,
                db.inTransaction(tx -> AccountsSql.findByEmail(tx, "ada@example.com")).get());
    }

    @Test
    void rolesAreStoredSortedAndCommaSeparated() {
        User saved =
                db.inTransaction(
                        tx ->
                                AccountsSql.insert(
                                        tx, user("root@example.com", Role.AUDIENCE, Role.ADMIN)));
        String stored =
                db.inTransaction(
                        tx ->
                                tx.queryOne(
                                                "SELECT roles FROM app_user WHERE id = ?",
                                                rs -> rs.getString(1),
                                                saved.id())
                                        .orElseThrow());
        assertEquals("ADMIN,AUDIENCE", stored);
        assertEquals(
                Set.of(Role.ADMIN, Role.AUDIENCE),
                db.inTransaction(tx -> AccountsSql.findById(tx, saved.id())).get().roles());
    }

    @Test
    void findByEmailIgnoresCase() {
        db.inTransaction(tx -> AccountsSql.insert(tx, user("ada@example.com", Role.AUDIENCE)));
        assertTrue(
                db.inTransaction(tx -> AccountsSql.findByEmail(tx, "ADA@Example.COM")).isPresent());
        assertTrue(db.inTransaction(tx -> AccountsSql.findByEmail(tx, "who@x.co")).isEmpty());
        assertTrue(db.inTransaction(tx -> AccountsSql.findById(tx, 999_999)).isEmpty());
    }

    @Test
    void aRacingDuplicateThatPassedThePreCheckIsAValidationErrorOnEmail() {
        db.inTransaction(tx -> AccountsSql.insert(tx, user("ada@example.com", Role.AUDIENCE)));
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () ->
                                db.inTransaction(
                                        tx ->
                                                AccountsSql.insert(
                                                        tx,
                                                        user("ada@example.com", Role.AUDIENCE))));
        assertEquals(Code.VALIDATION, e.code());
        assertEquals(Map.of("email", "is already registered"), e.errors());
    }
}
