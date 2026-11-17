package dev.thiru.bms.features.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.MutableClock;
import dev.thiru.bms.testing.TestDb;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserAdminTest {

    static final long CINEMA = 2;

    @TempDir Path dir;
    TestDb test;
    Db db;
    AdminFixtures f;
    UserAdmin users;
    long admin;
    long ada;

    @BeforeEach
    void open() {
        test = TestDb.open(dir);
        db = test.db();
        MutableClock clock =
                new MutableClock(
                        AdminFixtures.seedDate(db)
                                .atTime(LocalTime.of(8, 0))
                                .atZone(AdminFixtures.IST)
                                .toInstant());
        f = new AdminFixtures(db, clock);
        users = new UserAdmin(db);
        admin = f.user("root@x.in");
        db.inTransaction(
                tx ->
                        tx.update(
                                "UPDATE app_user SET roles = 'ADMIN,AUDIENCE' WHERE id = ?",
                                admin));
        ada = f.user("ada@x.in");
    }

    @AfterEach
    void close() {
        test.close();
    }

    Set<Role> rolesOf(long id) {
        return db.inTransaction(tx -> AdminSql.user(tx, id)).orElseThrow().roles();
    }

    String storedRoles(long id) {
        return db.inTransaction(
                tx ->
                        tx.queryOne(
                                        "SELECT roles FROM app_user WHERE id = ?",
                                        rs -> rs.getString(1),
                                        id)
                                .orElseThrow());
    }

    @Test
    void listsUsersByIdWithRolesAndCinemasAndSearches() {
        users.link(CINEMA, ada);
        Page<UserAdmin.Account> page = users.list(null, 1, 20);
        assertEquals(2, page.total());
        assertEquals(List.of(admin, ada), page.items().stream().map(a -> a.user().id()).toList());
        assertEquals(List.of(CINEMA), page.items().get(1).cinemaIds());
        assertEquals(1, users.list("ADA@", 1, 20).total());
        assertEquals(2, users.list("  ", 1, 20).total());
        assertEquals(1, users.list(null, 2, 1).items().size());
        assertEquals(ada, users.list(null, 2, 1).items().get(0).user().id());
        // LIKE wildcards are literal.
        assertEquals(0, users.list("%", 1, 20).total());
        assertEquals(0, users.list("_", 1, 20).total());
    }

    @Test
    void grantAndRevokeTheatreOwnerRemovesLinks() {
        UserAdmin.Account granted = users.grant(ada, Role.THEATRE_OWNER);
        assertEquals(Set.of(Role.AUDIENCE, Role.THEATRE_OWNER), granted.user().roles());
        assertEquals("AUDIENCE,THEATRE_OWNER", storedRoles(ada));
        users.grant(ada, Role.THEATRE_OWNER);
        users.link(CINEMA, ada);
        users.link(1, ada);
        UserAdmin.Account revoked = users.revoke(admin, ada, Role.THEATRE_OWNER);
        assertEquals(Set.of(Role.AUDIENCE), revoked.user().roles());
        assertEquals(List.of(), revoked.cinemaIds());
        assertEquals(List.of(), db.inTransaction(tx -> AdminSql.cinemaIdsOf(tx, ada)));
    }

    @Test
    void linkingImpliesTheRoleAndUnlinkingKeepsIt() {
        UserAdmin.Account linked = users.link(CINEMA, ada);
        assertEquals(Set.of(Role.AUDIENCE, Role.THEATRE_OWNER), linked.user().roles());
        assertEquals(List.of(CINEMA), linked.cinemaIds());
        assertEquals(List.of(CINEMA), users.link(CINEMA, ada).cinemaIds());
        assertEquals(List.of(1L, CINEMA), users.link(1, ada).cinemaIds());
        UserAdmin.Account unlinked = users.unlink(CINEMA, ada);
        assertEquals(List.of(1L), unlinked.cinemaIds());
        users.unlink(1, ada);
        assertEquals(Set.of(Role.AUDIENCE, Role.THEATRE_OWNER), rolesOf(ada));
        assertEquals(List.of(), users.unlink(CINEMA, ada).cinemaIds());
    }

    @Test
    void unknownUsersAndCinemasAreNotFound() {
        assertEquals(
                "User not found",
                assertThrows(DomainException.class, () -> users.grant(999, Role.ADMIN))
                        .getMessage());
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> users.revoke(admin, 999, Role.ADMIN))
                        .code());
        assertEquals(
                "Cinema not found",
                assertThrows(DomainException.class, () -> users.link(999, ada)).getMessage());
        assertEquals(
                "User not found",
                assertThrows(DomainException.class, () -> users.link(CINEMA, 999)).getMessage());
        assertEquals(
                "Cinema not found",
                assertThrows(DomainException.class, () -> users.unlink(999, ada)).getMessage());
        assertEquals(Set.of(Role.AUDIENCE), rolesOf(ada));
    }

    @Test
    void audienceCannotBeRemovedAndAnAdminCannotDemoteThemselves() {
        DomainException audience =
                assertThrows(DomainException.class, () -> users.revoke(admin, ada, Role.AUDIENCE));
        assertEquals(Code.VALIDATION, audience.code());
        assertEquals("AUDIENCE cannot be removed", audience.errors().get("role"));
        DomainException self =
                assertThrows(DomainException.class, () -> users.revoke(admin, admin, Role.ADMIN));
        assertEquals(Code.CONFLICT, self.code());
        assertEquals("You cannot remove your own ADMIN role", self.getMessage());
        assertEquals(Set.of(Role.AUDIENCE, Role.ADMIN), rolesOf(admin));
        // Checked before the user is read: even an unknown id gets the same answer.
        assertEquals(
                Code.CONFLICT,
                assertThrows(DomainException.class, () -> users.revoke(999, 999, Role.ADMIN))
                        .code());

        users.grant(ada, Role.ADMIN);
        assertEquals(Set.of(Role.AUDIENCE), users.revoke(admin, ada, Role.ADMIN).user().roles());
        assertEquals(Set.of(Role.AUDIENCE), users.revoke(admin, ada, Role.ADMIN).user().roles());
    }
}
