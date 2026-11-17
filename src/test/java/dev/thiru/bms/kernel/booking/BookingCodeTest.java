package dev.thiru.bms.kernel.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BookingCodeTest {

    private static final Pattern FORMAT = Pattern.compile("BK[0-9A-HJKMNP-TV-Z]{8}");

    @Test
    void generatesBkPlusEightCrockfordCharacters() {
        Random random = new Random(42);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String code = BookingCode.generate(random);
            assertTrue(FORMAT.matcher(code).matches(), code);
            seen.add(code);
        }
        assertTrue(seen.size() > 1990);
    }

    @Test
    void neverUsesAmbiguousLetters() {
        Random random = new Random(7);
        for (int i = 0; i < 500; i++) {
            String body = BookingCode.generate(random).substring(2);
            assertFalse(body.matches(".*[ILOU].*"), body);
        }
    }

    @Test
    void validatesFormat() {
        assertTrue(BookingCode.isValid("BK0123ABCD"));
        assertFalse(BookingCode.isValid("BK0123ABCI"));
        assertFalse(BookingCode.isValid("BK0123ABC"));
        assertFalse(BookingCode.isValid("XX0123ABCD"));
        assertEquals(10, BookingCode.generate(new Random()).length());
    }
}
