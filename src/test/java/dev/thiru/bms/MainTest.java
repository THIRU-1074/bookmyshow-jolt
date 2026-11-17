package dev.thiru.bms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.JoltConfig;
import in.jolt.test.JoltTest;
import org.junit.jupiter.api.Test;

class MainTest {

    private final JoltTest http = JoltTest.of(Main.app(JoltConfig.defaults()));

    @Test
    void healthIsUp() {
        http.get("/api/health").expect(200).jsonEquals("status", "UP");
    }

    @Test
    void unknownRouteIsProblemJson() {
        http.get("/api/nope")
                .expect(404)
                .header("Content-Type", "application/problem+json; charset=utf-8")
                .jsonEquals("status", 404);
    }

    @Test
    void portDefaultsTo8080AndRejectsOutOfRange() {
        assertEquals(8080, Main.port(null));
        assertEquals(8080, Main.port(" "));
        assertEquals(9090, Main.port("9090"));
        assertThrows(IllegalArgumentException.class, () -> Main.port("70000"));
        assertThrows(NumberFormatException.class, () -> Main.port("abc"));
    }
}
