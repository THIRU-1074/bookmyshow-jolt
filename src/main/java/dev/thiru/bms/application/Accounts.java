package dev.thiru.bms.application;

import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.Role;
import dev.thiru.bms.domain.User;
import dev.thiru.bms.domain.port.PasswordHasher;
import dev.thiru.bms.domain.port.UnitOfWork;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Account lifecycle. Public registration can only ever create an AUDIENCE account: roles are never
 * read from a request, so privilege comes only from {@link #ensureAdmin} (environment seeding) and
 * from an administrator through {@link UserAdmin}.
 */
public final class Accounts {

    private static final int MIN_ADMIN_PASSWORD = 12;

    private final UnitOfWork uow;
    private final PasswordHasher hasher;
    private final Clock clock;
    // Verified against when the email is unknown, so a miss costs the same time as a wrong
    // password and does not reveal which emails exist.
    private volatile String decoyHash;

    public Accounts(UnitOfWork uow, PasswordHasher hasher, Clock clock) {
        this.uow = uow;
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
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return uow.inTransaction(
                r -> {
                    if (r.users().findByEmail(normalized).isPresent()) {
                        throw DomainException.conflict("Email is already registered");
                    }
                    return r.users()
                            .insert(
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
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        Optional<User> user = uow.inTransaction(r -> r.users().findByEmail(normalized));
        boolean ok =
                password != null
                        && hasher.verify(password, user.map(User::passwordHash).orElse(decoy()));
        if (user.isEmpty() || !ok) {
            throw DomainException.unauthenticated();
        }
        return user.get();
    }

    public Optional<User> find(long id) {
        return uow.inTransaction(r -> r.users().findById(id));
    }

    /** Creates the admin when missing and keeps an existing account; true when created. */
    public boolean ensureAdmin(String email, String password) {
        if (password == null || password.length() < MIN_ADMIN_PASSWORD) {
            throw new IllegalArgumentException(
                    "ADMIN_PASSWORD must be at least " + MIN_ADMIN_PASSWORD + " characters");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (uow.inTransaction(r -> r.users().findByEmail(normalized)).isPresent()) {
            return false;
        }
        String hash = hasher.hash(password);
        return uow.inTransaction(
                r -> {
                    if (r.users().findByEmail(normalized).isPresent()) {
                        return false;
                    }
                    r.users()
                            .insert(
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

    private String decoy() {
        String h = decoyHash;
        if (h == null) {
            h = hasher.hash("decoy-password-not-a-real-account");
            decoyHash = h;
        }
        return h;
    }
}
