package dev.thiru.bms.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.booking.BookingCode;
import dev.thiru.bms.ports.Ids;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Ids for holds, bookings and payments, and the randomness behind booking codes. */
public abstract class IdGeneratorContract {

    protected abstract Ids newIds();

    @Test
    protected void idsAreNonBlankAndUnique() {
        Ids ids = newIds();
        Set<String> seen = new HashSet<>();
        IntStream.range(0, 1000).forEach(i -> seen.add(ids.newId()));
        assertEquals(1000, seen.size());
        assertFalse(seen.stream().anyMatch(String::isBlank));
    }

    @Test
    protected void theRandomSourceMakesValidBookingCodes() {
        Ids ids = newIds();
        assertNotNull(ids.random());
        for (int i = 0; i < 50; i++) {
            String code = BookingCode.generate(ids.random());
            assertTrue(BookingCode.isValid(code), code);
        }
    }
}
