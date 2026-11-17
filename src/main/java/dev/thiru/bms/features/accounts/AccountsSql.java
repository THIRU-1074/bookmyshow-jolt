package dev.thiru.bms.features.accounts;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.ports.Db;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/** {@code app_user} reads and the insert. Roles are stored sorted and comma-separated. */
final class AccountsSql {

    private static final String COLS = "id, email, password_hash, name, roles, created_at";

    private static final Db.Row<User> USER =
            rs ->
                    new User(
                            rs.getLong("id"),
                            rs.getString("email"),
                            rs.getString("password_hash"),
                            rs.getString("name"),
                            Arrays.stream(rs.getString("roles").split(","))
                                    .map(Role::valueOf)
                                    .collect(Collectors.toSet()),
                            Db.instant(rs, "created_at"));

    private AccountsSql() {}

    static Optional<User> findById(Db.Tx tx, long id) {
        return tx.queryOne("SELECT " + COLS + " FROM app_user WHERE id = ?", USER, id);
    }

    static Optional<User> findByEmail(Db.Tx tx, String email) {
        return tx.queryOne(
                "SELECT " + COLS + " FROM app_user WHERE email = ?",
                USER,
                email.toLowerCase(Locale.ROOT));
    }

    /** Inserts {@code user} (its id is ignored) and returns it with the generated id. */
    static User insert(Db.Tx tx, User user) {
        String roles =
                user.roles().stream().map(Role::name).sorted().collect(Collectors.joining(","));
        long id;
        try {
            id =
                    tx.insert(
                            "INSERT INTO app_user (email, password_hash, name, roles, created_at)"
                                    + " VALUES (?, ?, ?, ?, ?)",
                            user.email(),
                            user.passwordHash(),
                            user.name(),
                            roles,
                            Db.millis(user.createdAt()));
        } catch (Db.ConstraintViolation e) {
            // A concurrent registration passed the pre-check first; the UNIQUE index decides.
            throw DomainException.validation("email", "is already registered");
        }
        return new User(
                id, user.email(), user.passwordHash(), user.name(), user.roles(), user.createdAt());
    }
}
