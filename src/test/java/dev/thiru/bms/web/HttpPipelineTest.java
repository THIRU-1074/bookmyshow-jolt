package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.thiru.bms.testing.SliceKit;
import in.jolt.App;
import in.jolt.IdempotencyStore;
import in.jolt.Jolt;
import in.jolt.JoltConfig;
import in.jolt.Route;
import in.jolt.test.JoltTest;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pipeline alone, with no route module of its own: a slice's routes mounted on it get the SPEC
 * 9 middleware (request id, security headers, problems, RBAC gate and layers) exactly as in
 * production.
 */
class HttpPipelineTest {

    private final App app = Jolt.create(JoltConfig.defaults().problemJson(true));
    private final HttpPipeline pipeline =
            HttpPipeline.install(
                    app,
                    new HttpPipeline.Config(
                            SliceKit.SECRET,
                            Clock.systemUTC(),
                            IdempotencyStore.inMemory(),
                            false,
                            Path.of("src/main/resources/views")));
    private final JoltTest http;

    HttpPipelineTest() {
        pipeline.module(
                r -> {
                    r.get("/api/cities", p -> List.of());
                    r.get("/api/me", p -> Map.of("ok", true));
                    r.get("/api/admin/stats", p -> Map.of("ok", true));
                });
        http = JoltTest.of(app);
    }

    private String token(String... roles) {
        return "Bearer "
                + pipeline.jwt().sign("7", Map.of("roles", List.of(roles)), Duration.ofMinutes(5));
    }

    @Test
    void registersNoRoutesOfItsOwn() {
        assertEquals(
                List.of("/api/admin/stats", "/api/cities", "/api/me"),
                app.routes().stream().map(Route::path).sorted().toList());
    }

    @Test
    void aMountedRouteRunsBehindTheSpecMiddleware() {
        var res =
                http.get("/api/cities")
                        .header("X-Request-Id", "abc")
                        .expect(200)
                        .header("X-Request-Id", "abc");
        assertEquals(HttpPipeline.CSP, res.header("Content-Security-Policy"));
        assertNotNull(res.header("X-Content-Type-Options"));
    }

    @Test
    void theGateAndRoleLayersGuardMountedRoutes() {
        http.get("/api/me").expect(401).jsonEquals("code", "UNAUTHENTICATED");
        http.get("/api/me").header("Authorization", token("AUDIENCE")).expect(200);
        http.get("/api/admin/stats")
                .header("Authorization", token("AUDIENCE"))
                .expect(403)
                .jsonEquals("code", "FORBIDDEN");
        http.get("/api/admin/stats").header("Authorization", token("ADMIN")).expect(200);
        http.get("/api/nope").expect(404).jsonEquals("code", "NOT_FOUND");
    }
}
