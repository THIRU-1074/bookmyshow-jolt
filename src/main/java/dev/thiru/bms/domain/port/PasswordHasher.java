package dev.thiru.bms.domain.port;

/** Password hashing stays a port so the domain never depends on a crypto library. */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean verify(String rawPassword, String hash);
}
