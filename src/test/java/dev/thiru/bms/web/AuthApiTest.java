package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.auth.JwtAuth;
import in.jolt.test.JoltTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthApiTest {

    private static final String PROBLEM = "application/problem+json";

    private final WebKit kit = new WebKit();

    private JoltTest.Call register(JoltTest client, String email) {
        return client.post("/api/auth/register")
                .remoteAddress(kit.nextAddress())
                .header("Sec-Fetch-Site", "same-origin")
                .json(Map.of("email", email, "password", WebKit.PASSWORD, "name", "Ada"));
    }

    @Test
    void registerReturnsUserTokenAndAStrictHttpOnlyRefreshCookie() {
        var res = register(kit.http, "Ada@Example.com").expect(201);
        res.jsonEquals("user.email", "ada@example.com")
                .jsonEquals("user.roles.0", "AUDIENCE")
                .jsonEquals("expiresIn", 900)
                .header("Cache-Control", "no-store");
        assertNotNull(res.jsonField("accessToken"));
        String cookie = res.header("Set-Cookie");
        assertTrue(cookie.startsWith("bms_refresh="), cookie);
        assertTrue(cookie.contains("HttpOnly"), cookie);
        assertTrue(cookie.contains("SameSite=Strict"), cookie);
        assertTrue(cookie.contains("Path=/api/auth"), cookie);
        assertTrue(!cookie.contains("Secure"), "Secure only over HTTPS: " + cookie);
    }

    @Test
    void registrationIgnoresRolesInTheBody() {
        String token =
                (String)
                        kit.http
                                .post("/api/auth/register")
                                .json(
                                        Map.of(
                                                "email",
                                                "mallory@example.com",
                                                "password",
                                                WebKit.PASSWORD,
                                                "name",
                                                "Mallory",
                                                "roles",
                                                java.util.List.of("ADMIN")))
                                .expect(201)
                                .jsonEquals("user.roles.0", "AUDIENCE")
                                .jsonField("accessToken");
        kit.http
                .get("/api/admin/stats")
                .header("Authorization", WebKit.bearer(token))
                .expect(403)
                .jsonEquals("code", "FORBIDDEN");
    }

    @Test
    void duplicateEmailIs409AndBadInputIs422WithFieldErrors() {
        register(kit.http, "ada@example.com").expect(201);
        register(kit.http, "ADA@example.com").expect(409);
        kit.http
                .post("/api/auth/register")
                .json(Map.of("email", "nope", "password", "short", "name", " "))
                .expect(422)
                .jsonEquals("code", "VALIDATION")
                .jsonEquals("errors.email", "must be an email address");
    }

    @Test
    void loginFailsWithOneMessageForUnknownEmailAndWrongPassword() {
        kit.user("ada@example.com");
        var wrong =
                kit.http
                        .post("/api/auth/login")
                        .json(Map.of("email", "ada@example.com", "password", "wrong-password!"))
                        .expect(401)
                        .jsonEquals("code", "UNAUTHENTICATED");
        var unknown =
                kit.http
                        .post("/api/auth/login")
                        .json(Map.of("email", "who@example.com", "password", "wrong-password!"))
                        .expect(401);
        assertEquals(wrong.jsonField("detail"), unknown.jsonField("detail"));
        assertTrue(wrong.header("Content-Type").startsWith(PROBLEM));
    }

    @Test
    void meNeedsAValidBearerToken() {
        String token = kit.user("ada@example.com");
        kit.http
                .get("/api/me")
                .header("Authorization", WebKit.bearer(token))
                .expect(200)
                .jsonEquals("email", "ada@example.com")
                .jsonEquals("roles.0", "AUDIENCE");
        var anon = kit.http.get("/api/me").expect(401).jsonEquals("code", "UNAUTHENTICATED");
        assertTrue(anon.header("Content-Type").startsWith(PROBLEM));
        assertEquals("Bearer", anon.header("WWW-Authenticate"));
        kit.http.get("/api/me").header("Authorization", "Bearer not.a.jwt").expect(401);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String forged =
                in.jolt.auth.JwtAuth.hs256("another-secret-that-is-also-32-bytes-long!")
                        .sign(
                                "1",
                                Map.of("roles", java.util.List.of("ADMIN")),
                                java.time.Duration.ofMinutes(5));
        kit.http
                .get("/api/admin/stats")
                .header("Authorization", WebKit.bearer(forged))
                .expect(401)
                .jsonEquals("code", "UNAUTHENTICATED");
    }

    @Test
    void refreshRotatesTheCookieAndReuseRevokesTheFamily() {
        JoltTest browser = kit.http.session();
        register(browser, "ada@example.com").expect(201);
        var first =
                browser.post("/api/auth/refresh")
                        .header("Sec-Fetch-Site", "same-origin")
                        .expect(200);
        assertNotNull(first.jsonField("accessToken"));
        String rotated = first.header("Set-Cookie");
        assertTrue(rotated.startsWith("bms_refresh="));

        // Replaying a redeemed token (a stolen copy) is refused and kills the session.
        JoltTest thief = kit.http;
        String stolen = rotated.substring("bms_refresh=".length(), rotated.indexOf(';'));
        browser.post("/api/auth/refresh").header("Sec-Fetch-Site", "same-origin").expect(200);
        thief.post("/api/auth/refresh")
                .header("Cookie", "bms_refresh=" + stolen)
                .header("Sec-Fetch-Site", "same-origin")
                .expect(401)
                .jsonEquals("code", "UNAUTHENTICATED");
        browser.post("/api/auth/refresh").header("Sec-Fetch-Site", "same-origin").expect(401);
    }

    @Test
    void refreshWithoutCookieIs401() {
        kit.http.post("/api/auth/refresh").expect(401).jsonEquals("code", "UNAUTHENTICATED");
    }

    @Test
    void logoutClearsTheCookieAndRevokesTheSession() {
        JoltTest browser = kit.http.session();
        register(browser, "ada@example.com").expect(201);
        String cookie =
                browser.post("/api/auth/logout")
                        .header("Sec-Fetch-Site", "same-origin")
                        .expect(204)
                        .header("Set-Cookie");
        assertTrue(cookie.contains("Max-Age=0"), cookie);
        browser.post("/api/auth/refresh").header("Sec-Fetch-Site", "same-origin").expect(401);
    }

    @Test
    void crossSiteCookiePostsToAuthAreRefused() {
        JoltTest browser = kit.http.session();
        register(browser, "ada@example.com").expect(201);
        browser.post("/api/auth/refresh")
                .header("Sec-Fetch-Site", "cross-site")
                .expect(403)
                .jsonEquals("code", "FORBIDDEN");
    }

    @Test
    void loginIsRateLimitedPerIp() {
        for (int i = 0; i < 10; i++) {
            kit.http
                    .post("/api/auth/login")
                    .remoteAddress("192.0.2.9")
                    .json(Map.of("email", "x@example.com", "password", "wrong-password!"))
                    .expect(401);
        }
        var limited =
                kit.http
                        .post("/api/auth/login")
                        .remoteAddress("192.0.2.9")
                        .json(Map.of("email", "x@example.com", "password", "wrong-password!"))
                        .expect(429)
                        .jsonEquals("code", "RATE_LIMITED");
        assertNotNull(limited.header("Retry-After"));
        assertTrue(limited.header("Content-Type").startsWith(PROBLEM));
    }

    @Test
    void problemsCarryTheRequestId() {
        var res = kit.http.get("/api/me").header("X-Request-Id", "req-123").expect(401);
        res.jsonEquals("requestId", "req-123");
        assertNotEquals(null, res.header("X-Request-Id"));
    }

    @Test
    void aTokenIssuedBeforeTheRoleRenameStillCountsAsAudience() {
        String legacy =
                JwtAuth.hs256(WebKit.SECRET)
                        .sign("1", Map.of("roles", List.of("USER")), Duration.ofMinutes(5));
        kit.http.get("/api/bookings").header("Authorization", WebKit.bearer(legacy)).expect(200);
        kit.http.get("/api/admin/stats").header("Authorization", WebKit.bearer(legacy)).expect(403);
        String unknown =
                JwtAuth.hs256(WebKit.SECRET)
                        .sign("1", Map.of("roles", List.of("ROOT")), Duration.ofMinutes(5));
        kit.http.get("/api/bookings").header("Authorization", WebKit.bearer(unknown)).expect(403);
    }
}
