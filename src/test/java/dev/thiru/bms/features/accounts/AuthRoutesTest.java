package dev.thiru.bms.features.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.testing.SliceKit;
import in.jolt.HttpException;
import in.jolt.auth.JwtAuth;
import in.jolt.test.JoltTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class AuthRoutesTest {

    private static final String PROBLEM = "application/problem+json";
    private static final String SAME_ORIGIN = "same-origin";

    @RegisterExtension
    final SliceKit kit =
            SliceKit.boot(
                    k ->
                            List.of(
                                    AuthRoutes.of(
                                            k.pipeline().jwt(),
                                            new Accounts(k.db(), k.hasher(), k.clock()),
                                            k.refreshTokens())));

    private JoltTest.Call register(JoltTest client, String email) {
        return client.post("/api/auth/register")
                .remoteAddress(kit.nextAddress())
                .header("Sec-Fetch-Site", SAME_ORIGIN)
                .json(Map.of("email", email, "password", SliceKit.PASSWORD, "name", "Ada"));
    }

    private JoltTest.Call refresh(JoltTest client) {
        return client.post("/api/auth/refresh")
                .remoteAddress(kit.nextAddress())
                .header("Sec-Fetch-Site", SAME_ORIGIN);
    }

    private JoltTest.Call login(String email, String password) {
        return kit.http()
                .post("/api/auth/login")
                .remoteAddress(kit.nextAddress())
                .json(Map.of("email", email, "password", password));
    }

    /** The JSON payload of a JWT, decoded without verifying (the server verified it). */
    private static String payload(String jwt) {
        return new String(
                Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    private void setRoles(long id, String roles) {
        kit.db()
                .inTransaction(
                        tx -> tx.update("UPDATE app_user SET roles = ? WHERE id = ?", roles, id));
    }

    @Test
    void registerReturnsUserTokenAndAStrictHttpOnlyRefreshCookie() {
        var res = register(kit.http(), "Ada@Example.com").expect(201);
        res.jsonEquals("user.email", "ada@example.com")
                .jsonEquals("user.name", "Ada")
                .jsonEquals("user.roles.0", "AUDIENCE")
                .jsonEquals("expiresIn", 900)
                .header("Cache-Control", "no-store");
        String token = (String) res.jsonField("accessToken");
        assertNotNull(token);
        assertTrue(payload(token).contains("\"roles\":[\"AUDIENCE\"]"), payload(token));
        assertEquals(
                "{\"user\":{\"id\":"
                        + kit.idOf("ada@example.com")
                        + ",\"email\":\"ada@example.com\",\"name\":\"Ada\","
                        + "\"roles\":[\"AUDIENCE\"]},\"accessToken\":\""
                        + token
                        + "\",\"expiresIn\":900}",
                res.text());
        String cookie = res.header("Set-Cookie");
        assertTrue(cookie.startsWith("bms_refresh="), cookie);
        assertTrue(
                cookie.endsWith("; Path=/api/auth; HttpOnly; SameSite=Strict; Max-Age=2592000"),
                cookie);
        assertFalse(cookie.contains("Secure"), "Secure only over HTTPS: " + cookie);
    }

    @Test
    void theCookieIsSecureOnlyOverHttps() {
        assertEquals(
                "Path=/api/auth; HttpOnly; SameSite=Strict; Max-Age=2592000; Secure",
                AuthRoutes.cookieAttributes(true, AuthRoutes.REFRESH_TTL));
        assertEquals(
                "Path=/api/auth; HttpOnly; SameSite=Strict; Max-Age=0",
                AuthRoutes.cookieAttributes(false, Duration.ZERO));
    }

    @Test
    void registrationIgnoresRolesInTheBody() {
        String token =
                (String)
                        kit.http()
                                .post("/api/auth/register")
                                .remoteAddress(kit.nextAddress())
                                .json(
                                        Map.of(
                                                "email",
                                                "mallory@example.com",
                                                "password",
                                                SliceKit.PASSWORD,
                                                "name",
                                                "Mallory",
                                                "roles",
                                                List.of("ADMIN")))
                                .expect(201)
                                .jsonEquals("user.roles.0", "AUDIENCE")
                                .jsonField("accessToken");
        assertFalse(payload(token).contains("ADMIN"), payload(token));
        // The gate refuses an audience token before routing, so no admin route needs mounting.
        kit.http()
                .get("/api/admin/stats")
                .header("Authorization", SliceKit.bearer(token))
                .expect(403)
                .jsonEquals("code", "FORBIDDEN");
    }

    @Test
    void duplicateEmailIs409AndBadInputIs422WithFieldErrors() {
        register(kit.http(), "ada@example.com").expect(201);
        register(kit.http(), "ADA@example.com").expect(409).jsonEquals("code", "CONFLICT");
        kit.http()
                .post("/api/auth/register")
                .remoteAddress(kit.nextAddress())
                .json(Map.of("email", "nope", "password", "short", "name", " "))
                .expect(422)
                .jsonEquals("code", "VALIDATION")
                .jsonEquals("errors.email", "must be an email address")
                .jsonEquals("errors.password", "must be 10 to 128 characters")
                .jsonEquals("errors.name", "must not be blank");
    }

    @Test
    void loginReturnsTheUserAndACookie() {
        kit.audience("ada@example.com");
        var res =
                login("ADA@example.com", SliceKit.PASSWORD)
                        .expect(200)
                        .jsonEquals("user.email", "ada@example.com")
                        .jsonEquals("expiresIn", 900)
                        .header("Cache-Control", "no-store");
        assertTrue(res.header("Set-Cookie").startsWith("bms_refresh="));
    }

    @Test
    void loginFailsWithOneMessageForUnknownEmailAndWrongPassword() {
        kit.audience("ada@example.com");
        var wrong =
                login("ada@example.com", "wrong-password!")
                        .expect(401)
                        .jsonEquals("code", "UNAUTHENTICATED");
        var unknown = login("who@example.com", "wrong-password!").expect(401);
        assertEquals(wrong.jsonField("detail"), unknown.jsonField("detail"));
        assertTrue(wrong.header("Content-Type").startsWith(PROBLEM));
        assertEquals(null, wrong.header("Set-Cookie"));
    }

    @Test
    void meNeedsAValidBearerToken() {
        String token = kit.audience("ada@example.com");
        var me =
                kit.http()
                        .get("/api/me")
                        .header("Authorization", SliceKit.bearer(token))
                        .expect(200)
                        .jsonEquals("email", "ada@example.com")
                        .jsonEquals("roles.0", "AUDIENCE")
                        .header("Cache-Control", "no-store");
        assertEquals(
                "{\"id\":"
                        + kit.idOf("ada@example.com")
                        + ",\"email\":\"ada@example.com\",\"name\":\"Test\","
                        + "\"roles\":[\"AUDIENCE\"]}",
                me.text());
        var anon = kit.http().get("/api/me").expect(401).jsonEquals("code", "UNAUTHENTICATED");
        assertTrue(anon.header("Content-Type").startsWith(PROBLEM));
        assertEquals("Bearer", anon.header("WWW-Authenticate"));
        kit.http().get("/api/me").header("Authorization", "Bearer not.a.jwt").expect(401);
    }

    @Test
    void meForADeletedAccountIs401() {
        String token = kit.audience("ada@example.com");
        long id = kit.idOf("ada@example.com");
        kit.db().inTransaction(tx -> tx.update("DELETE FROM app_user WHERE id = ?", id));
        kit.http()
                .get("/api/me")
                .header("Authorization", SliceKit.bearer(token))
                .expect(401)
                .jsonEquals("code", "UNAUTHENTICATED")
                .jsonEquals("detail", "Unknown user");
    }

    @Test
    void meListsRolesSorted() {
        String token = kit.admin();
        kit.http()
                .get("/api/me")
                .header("Authorization", SliceKit.bearer(token))
                .expect(200)
                .jsonEquals("roles.0", "ADMIN")
                .jsonEquals("roles.1", "AUDIENCE");
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String forged =
                JwtAuth.hs256("another-secret-that-is-also-32-bytes-long!")
                        .sign("1", Map.of("roles", List.of("ADMIN")), Duration.ofMinutes(5));
        kit.http()
                .get("/api/me")
                .header("Authorization", SliceKit.bearer(forged))
                .expect(401)
                .jsonEquals("code", "UNAUTHENTICATED");
    }

    @Test
    void refreshRotatesTheCookieAndReuseRevokesTheFamily() {
        JoltTest browser = kit.http().session();
        register(browser, "ada@example.com").expect(201);
        var first =
                refresh(browser)
                        .expect(200)
                        .jsonEquals("expiresIn", 900)
                        .header("Cache-Control", "no-store");
        String access = (String) first.jsonField("accessToken");
        assertEquals("{\"accessToken\":\"" + access + "\",\"expiresIn\":900}", first.text());
        String rotated = first.header("Set-Cookie");
        assertTrue(rotated.startsWith("bms_refresh="));
        assertTrue(rotated.contains("Max-Age=2592000"), rotated);

        // Replaying a redeemed token (a stolen copy) is refused and kills the session.
        String stolen = rotated.substring("bms_refresh=".length(), rotated.indexOf(';'));
        refresh(browser).expect(200);
        var replay =
                refresh(kit.http())
                        .header("Cookie", "bms_refresh=" + stolen)
                        .expect(401)
                        .jsonEquals("code", "UNAUTHENTICATED");
        assertTrue(replay.header("Set-Cookie").contains("Max-Age=0"), "a failed refresh clears");
        refresh(browser).expect(401);
    }

    @Test
    void refreshReReadsTheRolesSoAPromotionIsPickedUp() {
        JoltTest browser = kit.http().session();
        register(browser, "ada@example.com").expect(201);
        setRoles(kit.idOf("ada@example.com"), "ADMIN,AUDIENCE");
        String access = (String) refresh(browser).expect(200).jsonField("accessToken");
        assertTrue(payload(access).contains("\"roles\":[\"ADMIN\",\"AUDIENCE\"]"), payload(access));
    }

    @Test
    void refreshForADeletedAccountIs401() {
        JoltTest browser = kit.http().session();
        register(browser, "ada@example.com").expect(201);
        long id = kit.idOf("ada@example.com");
        kit.db().inTransaction(tx -> tx.update("DELETE FROM app_user WHERE id = ?", id));
        refresh(browser)
                .expect(401)
                .jsonEquals("code", "UNAUTHENTICATED")
                .jsonEquals("detail", "Invalid token");
    }

    @Test
    void theClaimsRefuseAnUnknownOrNonNumericSubject() {
        Accounts accounts = new Accounts(kit.db(), kit.hasher(), kit.clock());
        long id = kit.createUser("ada@example.com", dev.thiru.bms.kernel.account.Role.AUDIENCE);
        assertEquals(
                Map.of("roles", List.of("AUDIENCE")),
                AuthRoutes.claimsFor(accounts, Long.toString(id)));
        for (String subject : List.of("not-a-number", "999999", "")) {
            HttpException e =
                    assertThrows(
                            HttpException.class, () -> AuthRoutes.claimsFor(accounts, subject));
            assertEquals(401, e.status());
            assertEquals("Invalid token", e.getMessage());
        }
    }

    @Test
    void refreshWithoutCookieIs401() {
        refresh(kit.http())
                .expect(401)
                .jsonEquals("code", "UNAUTHENTICATED")
                .jsonEquals("detail", "Missing refresh token");
        refresh(kit.http()).header("Cookie", "bms_refresh=").expect(401);
    }

    @Test
    void logoutClearsTheCookieAndRevokesTheSession() {
        JoltTest browser = kit.http().session();
        register(browser, "ada@example.com").expect(201);
        String cookie =
                browser.post("/api/auth/logout")
                        .header("Sec-Fetch-Site", SAME_ORIGIN)
                        .expect(204)
                        .header("Set-Cookie");
        assertEquals("bms_refresh=; Path=/api/auth; HttpOnly; SameSite=Strict; Max-Age=0", cookie);
        refresh(browser).expect(401);
    }

    @Test
    void logoutWithoutOrWithAnUnknownCookieStillSucceeds() {
        kit.http().post("/api/auth/logout").expect(204);
        kit.http()
                .post("/api/auth/logout")
                .header("Cookie", "bms_refresh=unknown")
                .header("Sec-Fetch-Site", SAME_ORIGIN)
                .expect(204);
    }

    @Test
    void crossSiteCookiePostsToAuthAreRefused() {
        JoltTest browser = kit.http().session();
        register(browser, "ada@example.com").expect(201);
        browser.post("/api/auth/refresh")
                .header("Sec-Fetch-Site", "cross-site")
                .expect(403)
                .jsonEquals("code", "FORBIDDEN");
    }

    @Test
    void loginIsRateLimitedPerIp() {
        for (int i = 0; i < 10; i++) {
            kit.http()
                    .post("/api/auth/login")
                    .remoteAddress("192.0.2.9")
                    .json(Map.of("email", "x@example.com", "password", "wrong-password!"))
                    .expect(401);
        }
        var limited =
                kit.http()
                        .post("/api/auth/login")
                        .remoteAddress("192.0.2.9")
                        .json(Map.of("email", "x@example.com", "password", "wrong-password!"))
                        .expect(429)
                        .jsonEquals("code", "RATE_LIMITED");
        assertNotNull(limited.header("Retry-After"));
        assertTrue(limited.header("Content-Type").startsWith(PROBLEM));
    }

    @Test
    void registerIsRateLimitedPerIp() {
        for (int i = 0; i < 10; i++) {
            kit.http()
                    .post("/api/auth/register")
                    .remoteAddress("192.0.2.10")
                    .json(Map.of("email", "nope", "password", "short", "name", " "))
                    .expect(422);
        }
        kit.http()
                .post("/api/auth/register")
                .remoteAddress("192.0.2.10")
                .json(Map.of("email", "nope", "password", "short", "name", " "))
                .expect(429);
    }

    @Test
    void problemsCarryTheRequestId() {
        var res = kit.http().get("/api/me").header("X-Request-Id", "req-123").expect(401);
        res.jsonEquals("requestId", "req-123");
        assertNotEquals(null, res.header("X-Request-Id"));
    }
}
