package dev.thiru.bms.testing;

import dev.thiru.bms.ports.RefreshTokenStore;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link RefreshTokenStore}, held to {@code RefreshTokenStoreContract}. */
public final class MemoryRefreshTokens implements RefreshTokenStore {
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();

    @Override
    public void save(Entry entry) {
        entries.put(entry.hash(), entry);
    }

    @Override
    public Optional<Entry> find(String hash) {
        return Optional.ofNullable(entries.get(hash));
    }

    @Override
    public synchronized boolean rotate(String oldHash, Entry next) {
        Entry old = entries.get(oldHash);
        if (old == null || old.used() || revoked.contains(old.family())) {
            return false;
        }
        entries.put(
                oldHash, new Entry(old.hash(), old.subject(), old.family(), old.expires(), true));
        entries.put(next.hash(), next);
        return true;
    }

    @Override
    public void revokeFamily(String family) {
        revoked.add(family);
        entries.values().removeIf(e -> e.family().equals(family));
    }

    @Override
    public Set<String> revokeSubject(String subject) {
        Set<String> families = new HashSet<>();
        entries.values().stream()
                .filter(e -> e.subject().equals(subject))
                .forEach(e -> families.add(e.family()));
        families.forEach(this::revokeFamily);
        return families;
    }
}
