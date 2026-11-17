package dev.thiru.bms.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DomainExceptionCodesTest {

    @Test
    void conflictCarriesItsMessage() {
        DomainException e = DomainException.conflict("Cinema still has shows");
        assertEquals(DomainException.Code.CONFLICT, e.code());
        assertEquals("Cinema still has shows", e.getMessage());
    }

    @Test
    void unauthenticatedNeverSaysWhichCredentialWasWrong() {
        DomainException e = DomainException.unauthenticated();
        assertEquals(DomainException.Code.UNAUTHENTICATED, e.code());
        assertEquals("Invalid credentials", e.getMessage());
    }
}
