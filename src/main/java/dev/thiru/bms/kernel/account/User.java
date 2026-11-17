package dev.thiru.bms.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * An account. The hash comes from the {@code PasswordHasher} port; raw passwords never live here.
 */
public record User(
        long id,
        String email,
        String passwordHash,
        String name,
        Set<Role> roles,
        Instant createdAt) {

    public User {
        if (email == null || email.indexOf('@') < 1 || email.indexOf('@') == email.length() - 1) {
            throw DomainException.validation("email", "must be an email address");
        }
        email = email.toLowerCase(Locale.ROOT);
        if (passwordHash == null || passwordHash.isEmpty()) {
            throw DomainException.validation("passwordHash", "is required");
        }
        if (name == null || name.isBlank()) {
            throw DomainException.validation("name", "must not be blank");
        }
        if (roles == null || roles.isEmpty()) {
            throw DomainException.validation("roles", "must not be empty");
        }
        roles = Set.copyOf(roles);
    }

    /** Checked before hashing, since the hash hides the length. */
    public static void validatePassword(String raw) {
        if (raw == null || raw.length() < 10 || raw.length() > 128) {
            throw DomainException.validation("password", "must be 10 to 128 characters");
        }
    }
}
