package dev.thiru.bms.web;

import dev.thiru.bms.ports.RefreshTokenStore;
import in.jolt.auth.RefreshStore;
import java.util.Set;

/** Lets Jolt's {@code RefreshTokens} persist through the domain {@link RefreshTokenStore} port. */
final class JoltRefreshStore implements RefreshStore {

    private final RefreshTokenStore store;

    JoltRefreshStore(RefreshTokenStore store) {
        this.store = store;
    }

    @Override
    public void save(Entry entry) {
        store.save(toDomain(entry));
    }

    @Override
    public Entry find(String hash) {
        return store.find(hash).map(JoltRefreshStore::toJolt).orElse(null);
    }

    @Override
    public boolean rotate(String oldHash, Entry next) {
        return store.rotate(oldHash, toDomain(next));
    }

    @Override
    public void revokeFamily(String family) {
        store.revokeFamily(family);
    }

    @Override
    public Set<String> revokeSubject(String subject) {
        return store.revokeSubject(subject);
    }

    private static RefreshTokenStore.Entry toDomain(Entry e) {
        return new RefreshTokenStore.Entry(
                e.hash(), e.subject(), e.family(), e.expires(), e.used());
    }

    private static Entry toJolt(RefreshTokenStore.Entry e) {
        return new Entry(e.hash(), e.subject(), e.family(), e.expires(), e.used());
    }
}
