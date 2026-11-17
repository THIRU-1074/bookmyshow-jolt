package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void problemMappingCoversEveryDomainCode() {
        for (var code : dev.thiru.bms.domain.DomainException.Code.values()) {
            int status = Problems.statusOf(code);
            assertEquals(true, status >= 400 && status < 500, code.name());
        }
        assertEquals("RATE_LIMITED", Problems.codeOf(429));
        assertEquals("SERVICE_UNAVAILABLE", Problems.codeOf(503));
    }
}
