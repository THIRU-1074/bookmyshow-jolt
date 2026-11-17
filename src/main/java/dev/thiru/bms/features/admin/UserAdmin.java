package dev.thiru.bms.features.admin;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.ports.Db;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Administrator-only account management (SPEC 3 and 7): roles and theatre owner links. This is the
 * only way to make a theatre owner; registration always creates AUDIENCE.
 */
final class UserAdmin {

    /** A user with the ids of the cinemas linked to them. */
    record Account(User user, List<Long> cinemaIds) {}

    // '!' escapes LIKE wildcards, so a search for "%" or "_" matches only those characters.
    private static final String MATCH =
            " WHERE ?1 IS NULL OR lower(email) LIKE ?1 ESCAPE '!' OR lower(name) LIKE ?1 ESCAPE '!'";

    private final Db db;

    UserAdmin(Db db) {
        this.db = db;
    }

    /** Users by id; a non-blank {@code q} keeps those whose email or name contains it. */
    Page<Account> list(String q, int page, int size) {
        String like = q == null || q.isBlank() ? null : "%" + escape(q.strip()) + "%";
        return db.inTransaction(
                tx -> {
                    long total =
                            tx.queryOne(
                                            "SELECT count(*) FROM app_user" + MATCH,
                                            rs -> rs.getLong(1),
                                            like)
                                    .orElse(0L);
                    List<User> users =
                            tx.query(
                                    "SELECT "
                                            + AdminSql.USER_COLS
                                            + " FROM app_user"
                                            + MATCH
                                            + " ORDER BY id LIMIT ?2 OFFSET ?3",
                                    AdminSql.USER,
                                    like,
                                    size,
                                    (long) Math.max(page - 1, 0) * size);
                    return new Page<>(
                            users.stream().map(u -> account(tx, u)).toList(), page, size, total);
                });
    }

    private static String escape(String q) {
        return q.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** Adds the role; granting a role the user already holds changes nothing. */
    Account grant(long userId, Role role) {
        return db.inTransaction(
                tx -> {
                    User user = find(tx, userId);
                    Set<Role> roles = EnumSet.copyOf(user.roles());
                    roles.add(role);
                    return save(tx, user, roles);
                });
    }

    /**
     * Removes the role. AUDIENCE is never removed, and an administrator cannot remove their own
     * ADMIN, so the last way back into the admin area is never closed by mistake. Revoking
     * THEATRE_OWNER also removes every cinema link.
     */
    Account revoke(long actorId, long userId, Role role) {
        if (role == Role.AUDIENCE) {
            throw DomainException.validation("role", "AUDIENCE cannot be removed");
        }
        if (role == Role.ADMIN && actorId == userId) {
            throw DomainException.conflict("You cannot remove your own ADMIN role");
        }
        return db.inTransaction(
                tx -> {
                    User user = find(tx, userId);
                    Set<Role> roles = EnumSet.copyOf(user.roles());
                    roles.remove(role);
                    if (role == Role.THEATRE_OWNER) {
                        tx.update("DELETE FROM cinema_owner WHERE user_id = ?", userId);
                    }
                    return save(tx, user, roles);
                });
    }

    /** Links the user to the cinema and grants THEATRE_OWNER; repeating changes nothing. */
    Account link(long cinemaId, long userId) {
        return db.inTransaction(
                tx -> {
                    requireCinema(tx, cinemaId);
                    User user = find(tx, userId);
                    Set<Role> roles = EnumSet.copyOf(user.roles());
                    roles.add(Role.THEATRE_OWNER);
                    tx.update(
                            "INSERT INTO cinema_owner (cinema_id, user_id) VALUES (?, ?)"
                                    + " ON CONFLICT DO NOTHING",
                            cinemaId,
                            userId);
                    return save(tx, user, roles);
                });
    }

    /** Removes the link; the role stays even when it was the last cinema. */
    Account unlink(long cinemaId, long userId) {
        return db.inTransaction(
                tx -> {
                    requireCinema(tx, cinemaId);
                    User user = find(tx, userId);
                    tx.update(
                            "DELETE FROM cinema_owner WHERE cinema_id = ? AND user_id = ?",
                            cinemaId,
                            userId);
                    return account(tx, user);
                });
    }

    private static Account save(Db.Tx tx, User user, Set<Role> roles) {
        if (!roles.equals(user.roles())) {
            tx.update(
                    "UPDATE app_user SET roles = ? WHERE id = ?",
                    roles.stream().map(Role::name).sorted().collect(Collectors.joining(",")),
                    user.id());
        }
        return account(
                tx,
                new User(
                        user.id(),
                        user.email(),
                        user.passwordHash(),
                        user.name(),
                        roles,
                        user.createdAt()));
    }

    private static Account account(Db.Tx tx, User user) {
        return new Account(user, AdminSql.cinemaIdsOf(tx, user.id()));
    }

    private static User find(Db.Tx tx, long userId) {
        return AdminSql.user(tx, userId).orElseThrow(() -> DomainException.notFound("User"));
    }

    private static void requireCinema(Db.Tx tx, long cinemaId) {
        if (!AdminSql.exists(tx, "SELECT 1 FROM cinema WHERE id = ?", cinemaId)) {
            throw DomainException.notFound("Cinema");
        }
    }
}
