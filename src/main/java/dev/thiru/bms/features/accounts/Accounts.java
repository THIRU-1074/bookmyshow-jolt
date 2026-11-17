package dev.thiru.bms.features.accounts;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.PasswordHasher;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Account lifecycle. Public registration can only ever create an AUDIENCE account: roles are never
 * read from a request, so privilege comes only from {@link #ensureAdmin} (environment seeding,
 * called by {@code Main}) and from an administrator.
 */
public final class Accounts {

    private static final int MIN_ADMIN_PASSWORD = 12;

    private final Db db;
    private final PasswordHasher hasher;
    private final Clock clock;
    // Verified against when the email is unknown, so a miss costs the same time as a wrong
    // password and does not reveal which emails exist.
    private volatile String decoyHash;

    public Accounts(Db db, PasswordHasher hasher, Clock clock) {
        this.db = db;
        this.hasher = hasher;
        this.clock = clock;
    }

    public User register(String email, String password, String name) {
        Map<String, String> errors = new LinkedHashMap<>();
        try {
            User.validatePassword(password);
        } catch (DomainException e) {
            errors.putAll(e.errors());
        }
        // The User constructor stops at its first bad field, so each field is probed on its own
        // (the hash is a placeholder) to report every problem at once.
        for (String[] probe : new String[][] {{email, "Ada"}, {"a@b.co", name}}) {
            try {
                new User(0, probe[0], "x", probe[1], Set.of(Role.AUDIENCE), clock.instant());
            } catch (DomainException e) {
                errors.putAll(e.errors());
            }
        }
        if (!errors.isEmpty()) {
            throw DomainException.validation(errors);
        }
        String hash = hasher.hash(password);
        String normalized = normalize(email);
        return db.inTransaction(
                tx -> {
                    if (AccountsSql.findByEmail(tx, normalized).isPresent()) {
                        throw DomainException.conflict("Email is already registered");
                    }
                    return AccountsSql.insert(
                            tx,
                            new User(
                                    0,
                                    normalized,
                                    hash,
                                    name.trim(),
                                    Set.of(Role.AUDIENCE),
                                    clock.instant()));
                });
    }

    /** Any mismatch, unknown email included, gives the same UNAUTHENTICATED error. */
    public User login(String email, String password) {
        String normalized = email == null ? "" : normalize(email);
        Optional<User> user = db.inTransaction(tx -> AccountsSql.findByEmail(tx, normalized));
        boolean ok =
                password != null
                        && hasher.verify(password, user.map(User::passwordHash).orElse(decoy()));
        if (user.isEmpty() || !ok) {
            throw DomainException.unauthenticated();
        }
        return user.get();
    }

    public Optional<User> find(long id) {
        return db.inTransaction(tx -> AccountsSql.findById(tx, id));
    }

    /** Creates the admin when missing and keeps an existing account; true when created. */
    public boolean ensureAdmin(String email, String password) {
        if (password == null || password.length() < MIN_ADMIN_PASSWORD) {
            throw new IllegalArgumentException(
                    "ADMIN_PASSWORD must be at least " + MIN_ADMIN_PASSWORD + " characters");
        }
        String normalized = normalize(email);
        if (db.inTransaction(tx -> AccountsSql.findByEmail(tx, normalized)).isPresent()) {
            return false;
        }
        // Hashed outside the writer transaction: PBKDF2 is slow and must not hold the lock.
        String hash = hasher.hash(password);
        return db.inTransaction(
                tx -> {
                    if (AccountsSql.findByEmail(tx, normalized).isPresent()) {
                        return false;
                    }
                    AccountsSql.insert(
                            tx,
                            new User(
                                    0,
                                    normalized,
                                    hash,
                                    "Administrator",
                                    Set.of(Role.ADMIN, Role.AUDIENCE),
                                    clock.instant()));
                    return true;
                });
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String decoy() {
        String h = decoyHash;
        if (h == null) {
            h = hasher.hash("decoy-password-not-a-real-account");
            decoyHash = h;
        }
        return h;
    }
}
