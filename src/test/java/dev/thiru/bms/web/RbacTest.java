package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.fakes.FakeWorld;
import in.jolt.test.JoltTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Server-side role and ownership enforcement on every protected route. */
class RbacTest {

    @RegisterExtension final WebKit kit = new WebKit();

    private static String concrete(String pattern) {
        return pattern.replaceAll(":\\w+", "1");
    }

    private JoltTest.Result call(Permissions.Rule rule, String token) {
        JoltTest.Call c =
                kit.http
                        .call(rule.method(), concrete(rule.pattern()))
                        .header("Idempotency-Key", kit.key());
        if (token != null) {
            c.header("Authorization", WebKit.bearer(token));
        }
        if (!rule.method().equals("GET") && !rule.method().equals("DELETE")) {
            c.json(Map.of());
        }
        return c.send();
    }

    @Test
    void everyProtectedRouteRefusesAnonymousCallersWith401() {
        for (Permissions.Rule rule : Permissions.TABLE) {
            if (!rule.access().needsToken()) {
                continue;
            }
            JoltTest.Result res = call(rule, null);
            assertEquals(401, res.status(), rule.toString());
            assertEquals("UNAUTHENTICATED", res.jsonField("code"), rule.toString());
        }
    }

    private int refuses(String token, Set<Permissions.Access> levels) {
        int checked = 0;
        for (Permissions.Rule rule : Permissions.TABLE) {
            if (!levels.contains(rule.access())) {
                continue;
            }
            JoltTest.Result res = call(rule, token);
            assertEquals(403, res.status(), rule.toString());
            assertEquals("FORBIDDEN", res.jsonField("code"), rule.toString());
            checked++;
        }
        return checked;
    }

    @Test
    void anAudienceMemberGets403OnEveryOwnerAndAdminRoute() {
        String audience = kit.user("ada@example.com");
        assertEquals(
                22 + 7,
                refuses(
                        audience,
                        Set.of(Permissions.Access.ADMIN, Permissions.Access.THEATRE_OWNER)));
    }

    @Test
    void aTheatreOwnerGets403OnEveryAdminRoute() {
        String owner = kit.owner("owner@example.com", 2);
        assertEquals(22, refuses(owner, Set.of(Permissions.Access.ADMIN)));
    }

    @Test
    void aTheatreOwnerPassesTheRoleCheckOnEveryOwnerRoute() {
        String owner = kit.owner("owner@example.com", 2);
        for (Permissions.Rule rule : Permissions.TABLE) {
            if (rule.access() == Permissions.Access.THEATRE_OWNER) {
                int status = call(rule, owner).status();
                assertTrue(
                        status != 401 && status != 403 && status < 500, rule + " gave " + status);
            }
        }
    }

    @Test
    void anAdminPassesEveryProtectedRoute() {
        String admin = kit.admin();
        for (Permissions.Rule rule : Permissions.TABLE) {
            if (rule.access().needsToken()) {
                int status = call(rule, admin).status();
                assertTrue(
                        status != 401 && status != 403 && status < 500, rule + " gave " + status);
            }
        }
    }

    @Test
    void adminAndOwnerRoutesAreAlsoGuardedByPrefixRoleChecks() {
        // Defence in depth: even a rule mistakenly marked SIGNED_IN under either prefix is refused.
        String audience = kit.user("ada@example.com");
        String owner = kit.owner("owner@example.com", 2);
        assertTrue(
                Permissions.TABLE.stream()
                        .filter(r -> r.pattern().startsWith("/api/admin/"))
                        .allMatch(r -> r.access() == Permissions.Access.ADMIN));
        assertTrue(
                Permissions.TABLE.stream()
                        .filter(r -> r.pattern().startsWith("/api/owner/"))
                        .allMatch(r -> r.access() == Permissions.Access.THEATRE_OWNER));
        kit.http
                .get("/api/admin/stats")
                .header("Authorization", WebKit.bearer(audience))
                .expect(403);
        kit.http.get("/api/admin/stats").header("Authorization", WebKit.bearer(owner)).expect(403);
        kit.http
                .get("/api/owner/cinemas")
                .header("Authorization", WebKit.bearer(audience))
                .expect(403);
    }

    @Test
    void aTheatreOwnerGets404OnAnotherOwnersCinemaScreenShowAndReport() {
        String admin = kit.admin();
        Number otherCinema =
                (Number)
                        kit.http
                                .post("/api/admin/cinemas")
                                .header("Authorization", WebKit.bearer(admin))
                                .header("Idempotency-Key", kit.key())
                                .json(
                                        Map.of(
                                                "cityId", "bengaluru",
                                                "name", "Other",
                                                "address", "Road"))
                                .expect(201)
                                .jsonField("id");
        String mine = kit.owner("mine@example.com", 2);
        String theirs = kit.owner("theirs@example.com", otherCinema.longValue());
        String bearer = WebKit.bearer(theirs);
        long show = FakeWorld.SHOW_ID;

        JoltTest.Result cinemas =
                kit.http.get("/api/owner/cinemas").header("Authorization", bearer).expect(200);
        assertEquals(otherCinema.longValue(), ((Number) cinemas.jsonField("0.id")).longValue());
        assertEquals(1, cinemas.json(List.class).size());
        kit.http
                .post("/api/owner/cinemas/2/screens")
                .header("Authorization", bearer)
                .header("Idempotency-Key", kit.key())
                .json(Map.of("name", "Sneaky", "layout", OwnerApiTest.layout(10)))
                .expect(404)
                .jsonEquals("code", "NOT_FOUND");
        kit.http
                .put("/api/owner/screens/" + FakeWorld.SCREEN_ID)
                .header("Authorization", bearer)
                .json(Map.of("name", "Sneaky", "layout", OwnerApiTest.layout(10)))
                .expect(404);
        kit.http
                .post("/api/owner/shows")
                .header("Authorization", bearer)
                .header("Idempotency-Key", kit.key())
                .json(OwnerApiTest.show(FakeWorld.SCREEN_ID, FakeWorld.T0.plusSeconds(86_400 * 6)))
                .expect(404);
        kit.http
                .post("/api/owner/shows/" + show + "/cancel")
                .header("Authorization", bearer)
                .header("Idempotency-Key", kit.key())
                .expect(404);
        kit.http
                .get("/api/owner/reports/shows/" + show)
                .header("Authorization", bearer)
                .expect(404)
                .jsonEquals("code", "NOT_FOUND");
        kit.http
                .get("/api/owner/reports/shows")
                .header("Authorization", bearer)
                .expect(200)
                .jsonEquals("total", 0);

        // Nothing changed, and the real owner still sees it all.
        kit.http
                .get("/api/owner/reports/shows/" + show)
                .header("Authorization", WebKit.bearer(mine))
                .expect(200)
                .jsonEquals("status", "SCHEDULED");
        kit.http
                .get("/api/owner/reports/shows")
                .header("Authorization", WebKit.bearer(mine))
                .expect(200)
                .jsonEquals("total", 1);
    }

    @Test
    void anAdminCannotRemoveTheirOwnAdminRole() {
        String admin = kit.admin();
        long adminId = kit.idOf("admin@bms.test");
        kit.http
                .delete("/api/admin/users/" + adminId + "/roles/ADMIN")
                .header("Authorization", WebKit.bearer(admin))
                .expect(409)
                .jsonEquals("code", "CONFLICT");
        kit.http
                .get("/api/admin/stats")
                .header("Authorization", WebKit.bearer(kit.login("admin@bms.test")))
                .expect(200);
    }

    @Test
    void unknownApiRoutesAreDeniedByDefault() {
        String admin = kit.admin();
        kit.http
                .get("/api/admin/secret")
                .header("Authorization", WebKit.bearer(admin))
                .expect(404)
                .jsonEquals("code", "NOT_FOUND");
        kit.http.put("/api/bookings").expect(404);
    }

    @Test
    void aStrangerCannotSeeOrTouchAnotherUsersHoldsAndBookings() {
        String ada = kit.user("ada@example.com");
        String eve = kit.user("eve@example.com");
        String booking = kit.book(ada, kit.hold(ada, "A5"));
        String holdOnly = kit.hold(ada, "B2");

        kit.http
                .delete("/api/holds/" + holdOnly)
                .header("Authorization", WebKit.bearer(eve))
                .expect(404);
        kit.http
                .get("/api/bookings/" + booking)
                .header("Authorization", WebKit.bearer(eve))
                .expect(404)
                .jsonEquals("code", "NOT_FOUND");
        kit.http
                .post("/api/bookings/" + booking + "/checkout")
                .header("Authorization", WebKit.bearer(eve))
                .header("Idempotency-Key", kit.key())
                .expect(404);
        kit.http
                .post("/api/bookings/" + booking + "/cancel")
                .header("Authorization", WebKit.bearer(eve))
                .header("Idempotency-Key", kit.key())
                .expect(404);
        kit.http
                .get("/api/bookings/" + booking + "/ticket")
                .header("Authorization", WebKit.bearer(eve))
                .expect(404);
        kit.http
                .get("/api/bookings")
                .header("Authorization", WebKit.bearer(eve))
                .expect(200)
                .jsonEquals("total", 0);
        kit.http
                .post("/api/bookings")
                .header("Authorization", WebKit.bearer(eve))
                .header("Idempotency-Key", kit.key())
                .json(Map.of("holdId", holdOnly))
                .expect(404);

        // The owner still can.
        kit.http
                .get("/api/bookings/" + booking)
                .header("Authorization", WebKit.bearer(ada))
                .expect(200)
                .jsonEquals("id", booking);
        kit.http
                .delete("/api/holds/" + holdOnly)
                .header("Authorization", WebKit.bearer(ada))
                .expect(204);
    }

    @Test
    void anAdminMayReadAnyBookingButNotActAsItsOwner() {
        String ada = kit.user("ada@example.com");
        String admin = kit.admin();
        String booking = kit.book(ada, kit.hold(ada, "A5"));

        kit.http
                .get("/api/bookings/" + booking)
                .header("Authorization", WebKit.bearer(admin))
                .expect(200)
                .jsonEquals("id", booking);
        kit.http
                .post("/api/bookings/" + booking + "/cancel")
                .header("Authorization", WebKit.bearer(admin))
                .header("Idempotency-Key", kit.key())
                .expect(404);
        kit.http
                .post("/api/bookings/" + booking + "/checkout")
                .header("Authorization", WebKit.bearer(admin))
                .header("Idempotency-Key", kit.key())
                .expect(404);
        kit.http
                .get("/api/bookings")
                .header("Authorization", WebKit.bearer(admin))
                .expect(200)
                .jsonEquals("total", 0);
    }

    @Test
    void idempotencyKeysAreScopedPerUser() {
        String ada = kit.user("ada@example.com");
        String eve = kit.user("eve@example.com");
        Map<String, Object> body = Map.of("showId", 9, "seats", List.of("B5"));
        kit.http
                .post("/api/holds")
                .header("Authorization", WebKit.bearer(ada))
                .header("Idempotency-Key", "shared")
                .json(body)
                .expect(201);
        // Same key, same body, other user: a fresh request, never Ada's stored response.
        kit.http
                .post("/api/holds")
                .header("Authorization", WebKit.bearer(eve))
                .header("Idempotency-Key", "shared")
                .json(body)
                .expect(409)
                .jsonEquals("code", "SEAT_UNAVAILABLE");
    }
}
