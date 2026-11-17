package dev.thiru.bms.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.thiru.bms.application.fakes.FakeWorld;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserAdminTest {

    static final long CINEMA = 2;

    FakeWorld w;
    UserAdmin users;
    long admin;
    long ada;

    @BeforeEach
    void setUp() {
        w = FakeWorld.seeded();
        users = new UserAdmin(w);
        admin = insert("admin@example.com", Set.of(Role.AUDIENCE, Role.ADMIN));
        ada = insert("ada@example.com", Set.of(Role.AUDIENCE));
    }

    private long insert(String email, Set<Role> roles) {
        return w.inTransaction(
                        r -> r.users().insert(new User(0, email, "h", "Name", roles, FakeWorld.T0)))
                .id();
    }

    private Set<Role> rolesOf(long id) {
        return w.inTransaction(r -> r.users().findById(id)).orElseThrow().roles();
    }

    @Test
    void listsUsersWithRolesAndCinemas() {
        users.link(CINEMA, ada);
        var page = users.list(null, 1, 20);
        assertEquals(2, page.total());
        UserAdmin.Account a = page.items().get(1);
        assertEquals(ada, a.user().id());
        assertEquals(List.of(CINEMA), a.cinemaIds());
        assertEquals(1, users.list("ADA@", 1, 20).total());
    }

    @Test
    void grantAndRevokeTheatreOwnerRemovesLinks() {
        UserAdmin.Account granted = users.grant(ada, Role.THEATRE_OWNER);
        assertEquals(Set.of(Role.AUDIENCE, Role.THEATRE_OWNER), granted.user().roles());
        users.grant(ada, Role.THEATRE_OWNER);
        users.link(CINEMA, ada);

        UserAdmin.Account revoked = users.revoke(admin, ada, Role.THEATRE_OWNER);
        assertEquals(Set.of(Role.AUDIENCE), revoked.user().roles());
        assertEquals(List.of(), revoked.cinemaIds());
        assertEquals(List.of(), w.inTransaction(r -> r.cinemaOwners().cinemaIdsOf(ada)));
    }

    @Test
    void linkingImpliesTheRoleAndUnlinkingKeepsIt() {
        UserAdmin.Account linked = users.link(CINEMA, ada);
        assertEquals(Set.of(Role.AUDIENCE, Role.THEATRE_OWNER), linked.user().roles());
        assertEquals(List.of(CINEMA), linked.cinemaIds());
        assertEquals(List.of(CINEMA), users.link(CINEMA, ada).cinemaIds());

        UserAdmin.Account unlinked = users.unlink(CINEMA, ada);
        assertEquals(List.of(), unlinked.cinemaIds());
        assertEquals(Set.of(Role.AUDIENCE, Role.THEATRE_OWNER), rolesOf(ada));
        assertEquals(List.of(), users.unlink(CINEMA, ada).cinemaIds());
    }

    @Test
    void unknownUsersAndCinemasAreNotFound() {
        assertEquals(
                Code.NOT_FOUND,
                AdminCatalogTest.codeOf(() -> users.grant(999, Role.THEATRE_OWNER)));
        assertEquals(
                Code.NOT_FOUND,
                AdminCatalogTest.codeOf(() -> users.revoke(admin, 999, Role.THEATRE_OWNER)));
        assertEquals(Code.NOT_FOUND, AdminCatalogTest.codeOf(() -> users.link(999, ada)));
        assertEquals(Code.NOT_FOUND, AdminCatalogTest.codeOf(() -> users.link(CINEMA, 999)));
        assertEquals(Code.NOT_FOUND, AdminCatalogTest.codeOf(() -> users.unlink(999, ada)));
        assertEquals(Set.of(Role.AUDIENCE), rolesOf(ada));
    }

    @Test
    void audienceCannotBeRemovedAndAnAdminCannotDemoteThemselves() {
        assertEquals(
                Code.VALIDATION,
                AdminCatalogTest.codeOf(() -> users.revoke(admin, ada, Role.AUDIENCE)));
        assertEquals(
                Code.CONFLICT,
                AdminCatalogTest.codeOf(() -> users.revoke(admin, admin, Role.ADMIN)));
        assertEquals(Set.of(Role.AUDIENCE, Role.ADMIN), rolesOf(admin));

        // Another admin may be demoted, and an admin may drop roles other than their own ADMIN.
        users.grant(ada, Role.ADMIN);
        assertEquals(Set.of(Role.AUDIENCE), users.revoke(admin, ada, Role.ADMIN).user().roles());
        users.grant(admin, Role.THEATRE_OWNER);
        assertEquals(
                Set.of(Role.AUDIENCE, Role.ADMIN),
                users.revoke(admin, admin, Role.THEATRE_OWNER).user().roles());
    }
}
