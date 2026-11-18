package dev.thiru.bms.ports;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence for refresh-token hashes (table {@code refresh_token}). Only SHA-256 hashes are
 * stored, never the opaque token. Semantics mirror Jolt's {@code RefreshStore} so the web layer can
 * adapt one to the other.
 */
public interface RefreshTokenStore {

    record Entry(String hash, String subject, String family, Instant expires, boolean used) {}

    void save(Entry entry);

    Optional<Entry> find(String hash);

    /**
     * Atomically marks {@code oldHash} used and stores {@code next}. Returns false when the old
     * entry is missing or already used (a replay), leaving the store unchanged.
     */
    boolean rotate(String oldHash, Entry next);

    void revokeFamily(String family);

    /** Removes every token of the subject and returns the families that were revoked. */
    Set<String> revokeSubject(String subject);
}
