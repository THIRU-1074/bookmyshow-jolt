package dev.thiru.bms.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void formatsPaiseAsRupees() {
        assertEquals("450.00", Money.rupees(45000));
        assertEquals("0.05", Money.rupees(5));
        assertEquals("-1.50", Money.rupees(-150));
    }
}
