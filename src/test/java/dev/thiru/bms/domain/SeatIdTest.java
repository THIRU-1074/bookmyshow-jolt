package dev.thiru.bms.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SeatIdTest {

    @Test
    void parsesAndPrintsRowLetterPlusNumber() {
        SeatId id = SeatId.parse("C7");
        assertEquals('C', id.row());
        assertEquals(7, id.number());
        assertEquals("C7", id.toString());
        assertEquals("Z50", SeatId.parse("Z50").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "7", "c7", "C0", "C51", "C07", "C-1", "AA1", "C7 ", "[1"})
    void rejectsMalformedIds(String raw) {
        assertThrows(DomainException.class, () -> SeatId.parse(raw));
    }

    @Test
    void rejectsNull() {
        assertThrows(DomainException.class, () -> SeatId.parse(null));
    }

    @Test
    void ordersByRowThenNumber() {
        assertEquals(-1, Integer.signum(SeatId.parse("A9").compareTo(SeatId.parse("B1"))));
        assertEquals(1, Integer.signum(SeatId.parse("A10").compareTo(SeatId.parse("A2"))));
    }
}
