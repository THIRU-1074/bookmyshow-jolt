package dev.thiru.bms.application;

import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.Page;
import dev.thiru.bms.domain.Role;
import dev.thiru.bms.domain.User;
import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.UnitOfWork;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Administrator-only account management (SPEC 3 and 7): roles and theatre owner links. This is the
 * only way to make a theatre owner; registration always creates AUDIENCE.
 */
public final class UserAdmin {

    /** A user with the ids of the cinemas linked to them. */
    public record Account(User user, List<Long> cinemaIds) {}

    private final UnitOfWork uow;

    public UserAdmin(UnitOfWork uow) {
        this.uow = uow;
    }

    public Page<Account> list(String q, int page, int size) {
        return uow.inTransaction(r -> r.userRoles().page(q, page, size).map(u -> account(r, u)));
    }

    /** Adds the role; granting a role the user already holds changes nothing. */
    public Account grant(long userId, Role role) {
        return uow.inTransaction(
                r -> {
                    User user = find(r, userId);
                    Set<Role> roles = EnumSet.copyOf(user.roles());
                    roles.add(role);
                    return save(r, user, roles);
                });
    }

    /**
     * Removes the role. AUDIENCE is never removed, and an administrator cannot remove their own
     * ADMIN, so the last way back into the admin area is never closed by mistake. Revoking
     * THEATRE_OWNER also removes every cinema link.
     */
    public Account revoke(long actorId, long userId, Role role) {
        if (role == Role.AUDIENCE) {
            throw DomainException.validation("role", "AUDIENCE cannot be removed");
        }
        if (role == Role.ADMIN && actorId == userId) {
            throw DomainException.conflict("You cannot remove your own ADMIN role");
        }
        return uow.inTransaction(
                r -> {
                    User user = find(r, userId);
                    Set<Role> roles = EnumSet.copyOf(user.roles());
                    roles.remove(role);
                    if (role == Role.THEATRE_OWNER) {
                        r.cinemaOwners().unlinkAll(userId);
                    }
                    return save(r, user, roles);
                });
    }

    /** Links the user to the cinema and grants THEATRE_OWNER; repeating changes nothing. */
    public Account link(long cinemaId, long userId) {
        return uow.inTransaction(
                r -> {
                    requireCinema(r, cinemaId);
                    User user = find(r, userId);
                    Set<Role> roles = EnumSet.copyOf(user.roles());
                    roles.add(Role.THEATRE_OWNER);
                    r.cinemaOwners().link(cinemaId, userId);
                    return save(r, user, roles);
                });
    }

    /** Removes the link; the role stays even when it was the last cinema. */
    public Account unlink(long cinemaId, long userId) {
        return uow.inTransaction(
                r -> {
                    requireCinema(r, cinemaId);
                    User user = find(r, userId);
                    r.cinemaOwners().unlink(cinemaId, userId);
                    return account(r, user);
                });
    }

    private static Account save(Repositories r, User user, Set<Role> roles) {
        if (!roles.equals(user.roles())) {
            r.userRoles().saveRoles(user.id(), roles);
        }
        return account(
                r,
                new User(
                        user.id(),
                        user.email(),
                        user.passwordHash(),
                        user.name(),
                        roles,
                        user.createdAt()));
    }

    private static Account account(Repositories r, User user) {
        return new Account(user, r.cinemaOwners().cinemaIdsOf(user.id()));
    }

    private static User find(Repositories r, long userId) {
        return r.users().findById(userId).orElseThrow(() -> DomainException.notFound("User"));
    }

    private static void requireCinema(Repositories r, long cinemaId) {
        r.cinemas().findById(cinemaId).orElseThrow(() -> DomainException.notFound("Cinema"));
    }
}
