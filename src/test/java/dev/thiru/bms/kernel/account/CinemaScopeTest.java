package dev.thiru.bms.kernel.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.DomainException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CinemaScopeTest {

    @Test
    void anAdminHasEveryCinemaAndAnyoneElseOnlyTheirOwn() {
        assertTrue(CinemaScope.forCaller(7, true).isEverything());
        assertNull(CinemaScope.forCaller(7, true).ownerId());
        assertEquals(CinemaScope.ownedBy(7), CinemaScope.forCaller(7, false));
        assertFalse(CinemaScope.ownedBy(7).isEverything());
        assertEquals(CinemaScope.everything(), new CinemaScope(null));
    }

    @Test
    void everythingNeverLooksUpLinks() {
        CinemaScope.everything()
                .require(
                        (user, cinema) -> {
                            throw new AssertionError("no lookup for an admin");
                        },
                        2,
                        "Cinema");
    }

    @Test
    void anOwnerOutsideTheirCinemasGetsNotFoundNamingTheResource() {
        List<String> asked = new ArrayList<>();
        CinemaScope owner = CinemaScope.ownedBy(7);
        owner.require(
                (user, cinema) -> {
                    asked.add(user + ":" + cinema);
                    return true;
                },
                2,
                "Screen");
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () -> owner.require((user, cinema) -> false, 3, "Show"));
        assertEquals(DomainException.Code.NOT_FOUND, e.code());
        assertEquals("Show not found", e.getMessage());
        assertEquals(List.of("7:2"), asked);
    }
}
