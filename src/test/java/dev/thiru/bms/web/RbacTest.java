package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Server-side role and ownership enforcement on every protected route. */
class RbacTest {

    private final WebKit kit = new WebKit();

    private static String concrete(String pattern) {
        return pattern.replace(":id", "1");
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

    @Test
    void everyAdminRouteRefusesAUserWith403() {
        String user = kit.user("ada@example.com");
        int checked = 0;
        for (Permissions.Rule rule : Permissions.TABLE) {
            if (rule.access() != Permissions.Access.ADMIN) {
                continue;
            }
            JoltTest.Result res = call(rule, user);
            assertEquals(403, res.status(), rule.toString());
            assertEquals("FORBIDDEN", res.jsonField("code"), rule.toString());
            checked++;
        }
        assertEquals(17, checked);
    }

    @Test
    void everyAdminRouteAdmitsAnAdmin() {
        String admin = kit.admin();
        for (Permissions.Rule rule : Permissions.TABLE) {
            if (rule.access() == Permissions.Access.ADMIN) {
                int status = call(rule, admin).status();
                assertTrue(
                        status != 401 && status != 403 && status < 500, rule + " gave " + status);
            }
        }
    }

    @Test
    void adminRoutesAreAlsoGuardedByThePrefixRoleCheck() {
        // Defence in depth: even a rule mistakenly marked USER under /api/admin is refused.
        String user = kit.user("ada@example.com");
        assertTrue(
                Permissions.TABLE.stream()
                        .filter(r -> r.pattern().startsWith("/api/admin/"))
                        .allMatch(r -> r.access() == Permissions.Access.ADMIN));
        kit.http.get("/api/admin/stats").header("Authorization", WebKit.bearer(user)).expect(403);
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
