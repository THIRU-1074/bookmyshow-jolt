package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpsApiTest {

    private final WebKit kit = new WebKit();

    @Test
    void healthReportsTheDatabase() {
        kit.http.get("/api/health").expect(200).jsonEquals("status", "UP").jsonEquals("db", "UP");
        kit.dbUp.set(false);
        kit.http
                .get("/api/health")
                .expect(503)
                .jsonEquals("status", "DOWN")
                .jsonEquals("db", "DOWN");
    }

    @Test
    void joltHealthReflectsTheDatabaseCheck() {
        kit.app.exposeMetrics();
        kit.http
                .get("/_jolt/health")
                .expect(200)
                .jsonEquals("status", "UP")
                .jsonEquals("checks.db", "UP");
        kit.dbUp.set(false);
        kit.http
                .get("/_jolt/health")
                .expect(503)
                .jsonEquals("status", "DOWN")
                .jsonEquals("checks.db", "DOWN");
    }

    @Test
    void securityHeadersAreOnEveryResponse() {
        var res = kit.http.get("/api/cities").expect(200);
        assertEquals(WebModules.CSP, res.header("Content-Security-Policy"));
        assertEquals("nosniff", res.header("X-Content-Type-Options"));
        assertEquals(null, res.header("Strict-Transport-Security"));
    }

    @Test
    void unexpectedFailuresAre500WithoutDetails() {
        Problems.Problem p = Problems.of(new IllegalStateException("db password is hunter2"));
        assertEquals(500, p.status());
        assertEquals("INTERNAL", p.code());
        assertFalse(p.detail().contains("hunter2"));
    }

    @Test
    void everyProblemCarriesTheRequestId() {
        var notFound =
                kit.http
                        .get("/api/movies/999")
                        .header("X-Request-Id", "r-404")
                        .expect(404)
                        .jsonEquals("code", "NOT_FOUND")
                        .jsonEquals("requestId", "r-404");
        assertTrue(notFound.header("Content-Type").startsWith("application/problem+json"));
        kit.http
                .get("/api/movies?size=0")
                .header("X-Request-Id", "r-422")
                .expect(422)
                .jsonEquals("errors.size", "must be 1 to 100")
                .jsonEquals("requestId", "r-422");
    }

    @Test
    void anUnexpectedFailureIs500WithTheRequestIdAndNoDetails() {
        kit.app.get(
                "/boom",
                (req, res) -> {
                    throw new IllegalStateException("db password is hunter2");
                });
        var res =
                kit.http
                        .get("/boom")
                        .header("X-Request-Id", "r-500")
                        .expect(500)
                        .jsonEquals("code", "INTERNAL")
                        .jsonEquals("requestId", "r-500");
        assertFalse(res.text().contains("hunter2"));
    }

    @Test
    void problemMappingCoversEveryDomainCode() {
        for (var code : dev.thiru.bms.domain.DomainException.Code.values()) {
            int status = Problems.statusOf(code);
            assertEquals(true, status >= 400 && status < 500, code.name());
        }
        assertEquals("RATE_LIMITED", Problems.codeOf(429));
        assertEquals("SERVICE_UNAVAILABLE", Problems.codeOf(503));
    }
}
