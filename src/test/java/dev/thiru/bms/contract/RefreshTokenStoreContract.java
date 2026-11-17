package dev.thiru.bms.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.domain.port.RefreshTokenStore;
import dev.thiru.bms.domain.port.RefreshTokenStore.Entry;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Refresh-token persistence as Jolt's RefreshTokens relies on it (rotation, reuse detection). */
public abstract class RefreshTokenStoreContract {

    protected static final Instant EXPIRES = Instant.parse("2026-12-19T10:00:00Z");

    private RefreshTokenStore store;

    protected abstract RefreshTokenStore newStore();

    @BeforeEach
    protected void openStore() {
        store = newStore();
    }

    private static Entry entry(String hash, String subject, String family) {
        return new Entry(hash, subject, family, EXPIRES, false);
    }

    @Test
    protected void aSavedEntryIsFoundByItsHash() {
        Entry e = entry("h1", "7", "f1");
        store.save(e);
        assertEquals(Optional.of(e), store.find("h1"));
        assertEquals(Optional.empty(), store.find("unknown"));
    }

    @Test
    protected void rotationMarksTheOldEntryUsedAndStoresTheNextOne() {
        store.save(entry("h1", "7", "f1"));
        Entry next = entry("h2", "7", "f1");
        assertTrue(store.rotate("h1", next));
        assertTrue(store.find("h1").orElseThrow().used());
        assertEquals(Optional.of(next), store.find("h2"));
    }

    @Test
    protected void rotatingAUsedOrUnknownEntryFailsAndChangesNothing() {
        store.save(entry("h1", "7", "f1"));
        assertTrue(store.rotate("h1", entry("h2", "7", "f1")));
        assertFalse(store.rotate("h1", entry("h3", "7", "f1")));
        assertEquals(Optional.empty(), store.find("h3"));
        assertFalse(store.rotate("missing", entry("h4", "7", "f1")));
        assertEquals(Optional.empty(), store.find("h4"));
    }

    @Test
    protected void revokingAFamilyForgetsEveryTokenInItOnly() {
        store.save(entry("h1", "7", "f1"));
        store.rotate("h1", entry("h2", "7", "f1"));
        store.save(entry("h9", "7", "f2"));
        store.revokeFamily("f1");
        assertEquals(Optional.empty(), store.find("h1"));
        assertEquals(Optional.empty(), store.find("h2"));
        assertFalse(store.rotate("h2", entry("h3", "7", "f1")));
        assertTrue(store.find("h9").isPresent());
    }

    @Test
    protected void revokingASubjectReturnsItsFamiliesAndLeavesOthers() {
        store.save(entry("a1", "7", "fa"));
        store.save(entry("b1", "7", "fb"));
        store.save(entry("c1", "8", "fc"));
        assertEquals(Set.of("fa", "fb"), store.revokeSubject("7"));
        assertEquals(Optional.empty(), store.find("a1"));
        assertEquals(Optional.empty(), store.find("b1"));
        assertTrue(store.find("c1").isPresent());
        assertEquals(Set.of(), store.revokeSubject("7"));
    }
}
