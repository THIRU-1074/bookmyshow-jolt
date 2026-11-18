package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.testing.SliceKit;
import in.jolt.auth.JwtAuth;
import in.jolt.test.JoltTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Server-side role and ownership enforcement on every protected route. */
class RbacTest {

    @RegisterExtension final SliceKit kit = WebApp.boot();

    /** A show in cinema 2, a day after the seed date. */
    private final long show = WebApp.show(kit);

    /** Signs in over the API, so the token carries the roles stored now. */
    private String login(String email) {
        return (String)
                kit.http()
                        .post("/api/auth/login")
                        .remoteAddress(kit.nextAddress())
                        .json(Map.of("email", email, "password", SliceKit.PASSWORD))
                        .expect(200)
                        .jsonField("accessToken");
    }

    /** Holds seats on {@link #show} and returns the hold id. */
    private String hold(String token, String... seats) {
        return (String)
                kit.http()
                        .post("/api/holds")
                        .header("Authorization", SliceKit.bearer(token))
                        .header("Idempotency-Key", kit.key())
                        .json(Map.of("showId", show, "seats", List.of(seats)))
                        .expect(201)
                        .jsonField("id");
    }

    private String book(String token, String holdId) {
        return (String)
                kit.http()
                        .post("/api/bookings")
                        .header("Authorization", SliceKit.bearer(token))
                        .header("Idempotency-Key", kit.key())
                        .json(Map.of("holdId", holdId))
                        .expect(201)
                        .jsonField("id");
    }

    /** The owner report total for {@code token}. */
    private long reportTotal(String token) {
        return ((Number)
                        kit.http()
                                .get("/api/owner/reports/shows")
                                .header("Authorization", SliceKit.bearer(token))
                                .expect(200)
                                .jsonField("total"))
                .longValue();
    }

    private static String concrete(String pattern) {
        return pattern.replaceAll(":\\w+", "1");
    }

    private JoltTest.Result call(Permissions.Rule rule, String token) {
        JoltTest.Call c =
                kit.http()
                        .call(rule.method(), concrete(rule.pattern()))
                        .header("Idempotency-Key", kit.key());
        if (token != null) {
            c.header("Authorization", SliceKit.bearer(token));
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
        String audience = kit.audience("ada@example.com");
        assertEquals(
                22 + 7,
                refuses(
                        audience,
                        Set.of(Permissions.Access.ADMIN, Permissions.Access.THEATRE_OWNER)));
    }

    @Test
    void aTheatreOwnerGets403OnEveryAdminRoute() {
        String owner = kit.owner("owner@example.com", WebApp.CINEMA_ID);
        assertEquals(22, refuses(owner, Set.of(Permissions.Access.ADMIN)));
    }

    @Test
    void aTheatreOwnerPassesTheRoleCheckOnEveryOwnerRoute() {
        String owner = kit.owner("owner@example.com", WebApp.CINEMA_ID);
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
        String audience = kit.audience("ada@example.com");
        String owner = kit.owner("owner@example.com", WebApp.CINEMA_ID);
        assertTrue(
                Permissions.TABLE.stream()
                        .filter(r -> r.pattern().startsWith("/api/admin/"))
                        .allMatch(r -> r.access() == Permissions.Access.ADMIN));
        assertTrue(
                Permissions.TABLE.stream()
                        .filter(r -> r.pattern().startsWith("/api/owner/"))
                        .allMatch(r -> r.access() == Permissions.Access.THEATRE_OWNER));
        kit.http()
                .get("/api/admin/stats")
                .header("Authorization", SliceKit.bearer(audience))
                .expect(403);
        kit.http()
                .get("/api/admin/stats")
                .header("Authorization", SliceKit.bearer(owner))
                .expect(403);
        kit.http()
                .get("/api/owner/cinemas")
                .header("Authorization", SliceKit.bearer(audience))
                .expect(403);
    }

    @Test
    void aTheatreOwnerGets404OnAnotherOwnersCinemaScreenShowAndReport() {
        String admin = kit.admin();
        Number otherCinema =
                (Number)
                        kit.http()
                                .post("/api/admin/cinemas")
                                .header("Authorization", SliceKit.bearer(admin))
                                .header("Idempotency-Key", kit.key())
                                .json(
                                        Map.of(
                                                "cityId", "bengaluru",
                                                "name", "Other",
                                                "address", "Road"))
                                .expect(201)
                                .jsonField("id");
        String mine = kit.owner("mine@example.com", WebApp.CINEMA_ID);
        String theirs = kit.owner("theirs@example.com", otherCinema.longValue());
        String bearer = SliceKit.bearer(theirs);
        long mineTotal = reportTotal(mine);
        assertTrue(mineTotal > 0);

        JoltTest.Result cinemas =
                kit.http().get("/api/owner/cinemas").header("Authorization", bearer).expect(200);
        assertEquals(otherCinema.longValue(), ((Number) cinemas.jsonField("0.id")).longValue());
        assertEquals(1, cinemas.json(List.class).size());
        kit.http()
                .post("/api/owner/cinemas/" + WebApp.CINEMA_ID + "/screens")
                .header("Authorization", bearer)
                .header("Idempotency-Key", kit.key())
                .json(Map.of("name", "Sneaky", "layout", layout(10)))
                .expect(404)
                .jsonEquals("code", "NOT_FOUND");
        kit.http()
                .put("/api/owner/screens/" + WebApp.SCREEN_ID)
                .header("Authorization", bearer)
                .json(Map.of("name", "Sneaky", "layout", layout(10)))
                .expect(404);
        kit.http()
                .post("/api/owner/shows")
                .header("Authorization", bearer)
                .header("Idempotency-Key", kit.key())
                .json(
                        show(
                                WebApp.SCREEN_ID,
                                kit.seedDate()
                                        .plusDays(20)
                                        .atTime(9, 0)
                                        .atZone(SliceKit.IST)
                                        .toInstant()))
                .expect(404);
        kit.http()
                .post("/api/owner/shows/" + show + "/cancel")
                .header("Authorization", bearer)
                .header("Idempotency-Key", kit.key())
                .expect(404);
        kit.http()
                .get("/api/owner/reports/shows/" + show)
                .header("Authorization", bearer)
                .expect(404)
                .jsonEquals("code", "NOT_FOUND");
        kit.http()
                .get("/api/owner/reports/shows")
                .header("Authorization", bearer)
                .expect(200)
                .jsonEquals("total", 0);

        // Nothing changed, and the real owner still sees it all.
        kit.http()
                .get("/api/owner/reports/shows/" + show)
                .header("Authorization", SliceKit.bearer(mine))
                .expect(200)
                .jsonEquals("status", "SCHEDULED");
        assertEquals(mineTotal, reportTotal(mine));
    }

    @Test
    void anAdminCannotRemoveTheirOwnAdminRole() {
        String admin = kit.admin();
        long adminId = kit.idOf(SliceKit.ADMIN_EMAIL);
        kit.http()
                .delete("/api/admin/users/" + adminId + "/roles/ADMIN")
                .header("Authorization", SliceKit.bearer(admin))
                .expect(409)
                .jsonEquals("code", "CONFLICT");
        kit.http()
                .get("/api/admin/stats")
                .header("Authorization", SliceKit.bearer(login(SliceKit.ADMIN_EMAIL)))
                .expect(200);
    }

    @Test
    void unknownApiRoutesAreDeniedByDefault() {
        String admin = kit.admin();
        kit.http()
                .get("/api/admin/secret")
                .header("Authorization", SliceKit.bearer(admin))
                .expect(404)
                .jsonEquals("code", "NOT_FOUND");
        kit.http().put("/api/bookings").expect(404);
    }

    @Test
    void aStrangerCannotSeeOrTouchAnotherUsersHoldsAndBookings() {
        String ada = kit.audience("ada@example.com");
        String eve = kit.audience("eve@example.com");
        String booking = book(ada, hold(ada, "A5"));
        String holdOnly = hold(ada, "B2");

        kit.http()
                .delete("/api/holds/" + holdOnly)
                .header("Authorization", SliceKit.bearer(eve))
                .expect(404);
        kit.http()
                .get("/api/bookings/" + booking)
                .header("Authorization", SliceKit.bearer(eve))
                .expect(404)
                .jsonEquals("code", "NOT_FOUND");
        kit.http()
                .post("/api/bookings/" + booking + "/checkout")
                .header("Authorization", SliceKit.bearer(eve))
                .header("Idempotency-Key", kit.key())
                .expect(404);
        kit.http()
                .post("/api/bookings/" + booking + "/cancel")
                .header("Authorization", SliceKit.bearer(eve))
                .header("Idempotency-Key", kit.key())
                .expect(404);
        kit.http()
                .get("/api/bookings/" + booking + "/ticket")
                .header("Authorization", SliceKit.bearer(eve))
                .expect(404);
        kit.http()
                .get("/api/bookings")
                .header("Authorization", SliceKit.bearer(eve))
                .expect(200)
                .jsonEquals("total", 0);
        kit.http()
                .post("/api/bookings")
                .header("Authorization", SliceKit.bearer(eve))
                .header("Idempotency-Key", kit.key())
                .json(Map.of("holdId", holdOnly))
                .expect(404);

        // The owner still can.
        kit.http()
                .get("/api/bookings/" + booking)
                .header("Authorization", SliceKit.bearer(ada))
                .expect(200)
                .jsonEquals("id", booking);
        kit.http()
                .delete("/api/holds/" + holdOnly)
                .header("Authorization", SliceKit.bearer(ada))
                .expect(204);
    }

    @Test
    void anAdminMayReadAnyBookingButNotActAsItsOwner() {
        String ada = kit.audience("ada@example.com");
        String admin = kit.admin();
        String booking = book(ada, hold(ada, "A5"));

        kit.http()
                .get("/api/bookings/" + booking)
                .header("Authorization", SliceKit.bearer(admin))
                .expect(200)
                .jsonEquals("id", booking);
        kit.http()
                .post("/api/bookings/" + booking + "/cancel")
                .header("Authorization", SliceKit.bearer(admin))
                .header("Idempotency-Key", kit.key())
                .expect(404);
        kit.http()
                .post("/api/bookings/" + booking + "/checkout")
                .header("Authorization", SliceKit.bearer(admin))
                .header("Idempotency-Key", kit.key())
                .expect(404);
        kit.http()
                .get("/api/bookings")
                .header("Authorization", SliceKit.bearer(admin))
                .expect(200)
                .jsonEquals("total", 0);
    }

    @Test
    void idempotencyKeysAreScopedPerUser() {
        String ada = kit.audience("ada@example.com");
        String eve = kit.audience("eve@example.com");
        Map<String, Object> body = Map.of("showId", show, "seats", List.of("B5"));
        kit.http()
                .post("/api/holds")
                .header("Authorization", SliceKit.bearer(ada))
                .header("Idempotency-Key", "shared")
                .json(body)
                .expect(201);
        // Same key, same body, other user: a fresh request, never Ada's stored response.
        kit.http()
                .post("/api/holds")
                .header("Authorization", SliceKit.bearer(eve))
                .header("Idempotency-Key", "shared")
                .json(body)
                .expect(409)
                .jsonEquals("code", "SEAT_UNAVAILABLE");
    }

    @Test
    void aTokenIssuedBeforeTheRoleRenameStillCountsAsAudience() {
        String legacy =
                JwtAuth.hs256(SliceKit.SECRET)
                        .sign("1", Map.of("roles", List.of("USER")), Duration.ofMinutes(5));
        kit.http()
                .get("/api/bookings")
                .header("Authorization", SliceKit.bearer(legacy))
                .expect(200);
        kit.http()
                .get("/api/admin/stats")
                .header("Authorization", SliceKit.bearer(legacy))
                .expect(403);
        String unknown =
                JwtAuth.hs256(SliceKit.SECRET)
                        .sign("1", Map.of("roles", List.of("ROOT")), Duration.ofMinutes(5));
        kit.http()
                .get("/api/bookings")
                .header("Authorization", SliceKit.bearer(unknown))
                .expect(403);
    }

    /** A one-row layout of {@code seats} CLASSIC seats. */
    private static Map<String, Object> layout(int seats) {
        return Map.of(
                "categories",
                List.of(Map.of("code", "CLASSIC", "name", "Classic")),
                "rows",
                List.of(
                        Map.of(
                                "row",
                                "A",
                                "seats",
                                seats,
                                "category",
                                "CLASSIC",
                                "aisleAfter",
                                List.of(),
                                "blocked",
                                List.of())));
    }

    /** A show body for the seeded movie with the seeded CLASSIC and PRIME prices. */
    private static Map<String, Object> show(long screenId, java.time.Instant startsAt) {
        return Map.of(
                "movieId",
                1,
                "screenId",
                screenId,
                "startsAt",
                startsAt.toString(),
                "language",
                "en",
                "format",
                "2D",
                "prices",
                Map.of("CLASSIC", 15000, "PRIME", 25000));
    }
}
