package dev.thiru.bms.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.web.Principal;
import in.jolt.RouteModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class SliceKitTest {

    /** A stand-in slice: one public read on the database, one signed-in, one admin, one owner. */
    static RouteModule probe(SliceKit k) {
        return r -> {
            r.get(
                    "/api/cities",
                    p ->
                            k.db().inTransaction(
                                            tx ->
                                                    tx.query(
                                                            "SELECT id FROM city ORDER BY id",
                                                            rs -> rs.getString("id"))));
            r.get("/api/me", (req, res) -> res.json(Map.of("id", Principal.of(req).userId())));
            r.get("/api/admin/stats", p -> Map.of("ok", true));
            r.get("/api/owner/cinemas", p -> Map.of("ok", true));
        };
    }

    @RegisterExtension final SliceKit kit = SliceKit.boot(k -> List.of(probe(k)));

    @Test
    void servesMountedRoutesFromTheSeededDatabase() {
        assertEquals(
                List.of("bengaluru", "chennai", "mumbai"),
                kit.http().get("/api/cities").expect(200).json(List.class));
        kit.http().get("/api/nope").expect(404).jsonEquals("code", "NOT_FOUND");
    }

    @Test
    void tokensCarryTheStoredRolesThroughTheRealGate() {
        String ada = kit.audience("ada@example.com");
        kit.http().get("/api/me").expect(401);
        kit.http()
                .get("/api/me")
                .header("Authorization", SliceKit.bearer(ada))
                .expect(200)
                .jsonEquals("id", (int) kit.idOf("ada@example.com"));
        kit.http()
                .get("/api/admin/stats")
                .header("Authorization", SliceKit.bearer(ada))
                .expect(403);
        kit.http()
                .get("/api/admin/stats")
                .header("Authorization", SliceKit.bearer(kit.admin()))
                .expect(200);
        String owner = kit.owner("owner@example.com", 2);
        kit.http()
                .get("/api/owner/cinemas")
                .header("Authorization", SliceKit.bearer(owner))
                .expect(200);
        long links =
                kit.db()
                        .inTransaction(
                                tx ->
                                        tx.queryOne(
                                                        "SELECT count(*) FROM cinema_owner"
                                                                + " WHERE cinema_id = 2",
                                                        rs -> rs.getLong(1))
                                                .orElseThrow());
        assertEquals(1, links);
    }

    @Test
    void theClockStartsBeforeTheFirstSeededShowAndMoves() {
        assertEquals(
                kit.seedDate().atTime(LocalTime.of(8, 0)).atZone(SliceKit.IST).toInstant(),
                kit.clock().instant());
        kit.mutableClock().advance(Duration.ofMinutes(5));
        assertEquals(
                kit.seedDate().atTime(LocalTime.of(8, 5)).atZone(SliceKit.IST).toInstant(),
                kit.clock().instant());
    }

    @Test
    void aBareKitHasNoRoutesAndClosingRemovesItsDatabase() throws Exception {
        Path dir;
        try (SliceKit bare = SliceKit.builder().boot()) {
            assertEquals(List.of(), bare.app().routes());
            dir = bare.dir();
            assertTrue(Files.exists(dir.resolve("bms.db")));
        }
        assertFalse(Files.exists(dir));
    }

    @Test
    void eachKitHasItsOwnDatabase(@org.junit.jupiter.api.io.TempDir Path dir) {
        try (SliceKit other = SliceKit.builder().dir(dir).boot()) {
            other.audience("solo@example.com");
            assertTrue(Files.exists(dir.resolve("bms.db")));
        }
        assertEquals(
                0,
                kit.db().inTransaction(tx -> tx.query("SELECT id FROM app_user", rs -> 1)).size());
    }

    @Test
    void portFakesAreFreshPerKit() {
        assertEquals("id-1", kit.ids().newId());
        assertEquals(
                "http://pay.test/p1", kit.gateway().createSession("p1", 100, "INR").checkoutUrl());
        assertTrue(kit.events().published.isEmpty());
        try (SliceKit other = SliceKit.builder().boot()) {
            assertEquals("id-1", other.ids().newId());
            assertEquals(0, other.gateway().sessions.get());
        }
    }
}
