package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.domain.port.RefreshTokenStore;
import dev.thiru.bms.domain.port.RefreshTokenStore.Entry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqlRefreshTokensTest extends DbTest {

    private RefreshTokenStore store;

    @BeforeEach
    void create() {
        store = new SqlRefreshTokens(db);
    }

    private static Entry entry(String hash, String subject, String family) {
        return new Entry(hash, subject, family, NOW.plus(Duration.ofDays(30)), false);
    }

    @Test
    void savesAndFindsAnEntry() {
        store.save(entry("h1", "7", "f1"));
        assertEquals(entry("h1", "7", "f1"), store.find("h1").orElseThrow());
        assertTrue(store.find("missing").isEmpty());
    }

    @Test
    void rotateMarksTheOldTokenUsedAndStoresTheNextOnce() {
        store.save(entry("h1", "7", "f1"));
        assertTrue(store.rotate("h1", entry("h2", "7", "f1")));
        assertTrue(store.find("h1").orElseThrow().used());
        assertFalse(store.find("h2").orElseThrow().used());
        // A replay of the old token must not mint another one.
        assertFalse(store.rotate("h1", entry("h3", "7", "f1")));
        assertTrue(store.find("h3").isEmpty());
        assertFalse(store.rotate("missing", entry("h4", "7", "f1")));
    }

    @Test
    void revokeFamilyRemovesOnlyThatFamily() {
        store.save(entry("h1", "7", "f1"));
        store.save(entry("h2", "7", "f2"));
        store.revokeFamily("f1");
        assertTrue(store.find("h1").isEmpty());
        assertTrue(store.find("h2").isPresent());
        assertFalse(store.rotate("h1", entry("h3", "7", "f1")));
    }

    @Test
    void revokeSubjectReturnsTheRevokedFamilies() {
        store.save(entry("h1", "7", "f1"));
        store.save(entry("h2", "7", "f2"));
        store.save(entry("h3", "8", "f3"));
        assertEquals(Set.of("f1", "f2"), store.revokeSubject("7"));
        assertTrue(store.find("h1").isEmpty());
        assertTrue(store.find("h3").isPresent());
        assertEquals(Set.of(), store.revokeSubject("7"));
    }

    @Test
    void purgeDropsExpiredTokensOnly() {
        store.save(new Entry("old", "7", "f1", NOW.minusSeconds(1), false));
        store.save(entry("live", "7", "f2"));
        assertEquals(1, ((SqlRefreshTokens) store).purgeExpired(NOW));
        assertTrue(store.find("old").isEmpty());
        assertTrue(store.find("live").isPresent());
    }
}
