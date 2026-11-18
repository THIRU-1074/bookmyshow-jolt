package dev.thiru.bms.adapters.ids;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecureIdsTest {

    @Test
    void idsAreDistinctRandomUuidV4() {
        SecureIds ids = new SecureIds();
        String a = ids.newId();
        assertTrue(
                a.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
                a);
        assertNotEquals(a, ids.newId());
        assertNotEquals(ids.random().nextLong(), ids.random().nextLong());
    }
}
