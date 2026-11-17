package dev.thiru.bms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import in.jolt.App;
import in.jolt.Jolt;
import in.jolt.JoltConfig;
import in.jolt.test.JoltTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProxyDiagnosticsTest {

    private final List<String> lines = new ArrayList<>();

    /** An app trusting {@code trust}, with the diagnostics installed and two plain routes. */
    private JoltTest app(String... trust) {
        JoltConfig config = JoltConfig.defaults();
        if (trust.length > 0) {
            config = config.trustProxy(trust);
        }
        App app = Jolt.create(config);
        app.use(ProxyDiagnostics.firstRequest(lines::add));
        app.get("/api/health", (req, res) -> res.text("UP"));
        app.get("/_jolt/health", (req, res) -> res.text("UP"));
        app.get("/api/x", (req, res) -> res.text(req.scheme()));
        return JoltTest.of(app);
    }

    @Test
    void theTrustListIsDescribedOrNone() {
        assertEquals("trustProxy=none", ProxyDiagnostics.describe(List.of()));
        assertEquals(
                "trustProxy=[10.0.0.0/8, fc00::/7]",
                ProxyDiagnostics.describe(List.of("10.0.0.0/8", "fc00::/7")));
    }

    @Test
    void onlyTheFirstNonHealthRequestIsLoggedAndNoForwardedValueLeaks() {
        JoltTest http = app("10.0.0.0/8");
        http.get("/api/health").remoteAddress("10.1.2.3").expect(200);
        http.get("/_jolt/health").remoteAddress("10.1.2.3").expect(200);
        assertEquals(List.of(), lines);
        // An IPv4-mapped peer still matches the IPv4 block: Jolt folds it to four bytes.
        http.get("/api/x")
                .remoteAddress("::ffff:10.1.2.3")
                .header("X-Forwarded-For", "203.0.113.9")
                .header("X-Forwarded-Proto", "https")
                .expect(200)
                .bodyContains("https");
        http.get("/api/x").remoteAddress("192.0.2.1").expect(200);
        assertEquals(
                List.of(
                        "First request: peer=forwarded xForwardedFor=present"
                                + " xForwardedProto=https trusted=yes scheme=https"),
                lines);
        assertFalse(lines.get(0).contains("203.0.113.9"), "the client address stays out");
    }

    @Test
    void anUntrustedPeerIsLoggedWithTheSchemeItGot() {
        app("10.0.0.0/8")
                .get("/api/x")
                .remoteAddress("100.64.1.2")
                .header("X-Forwarded-Proto", "https")
                .expect(200)
                .bodyContains("http");
        assertEquals(
                List.of(
                        "First request: peer=100.64.1.2 xForwardedFor=absent"
                                + " xForwardedProto=https trusted=no scheme=http"),
                lines);
    }

    @Test
    void anUntrustedPeerBehindForwardedForIsNotTrusted() {
        app().get("/api/x")
                .remoteAddress("10.1.2.3")
                .header("X-Forwarded-For", "203.0.113.9")
                .header("X-Forwarded-Proto", "http")
                .expect(200);
        assertEquals(
                List.of(
                        "First request: peer=10.1.2.3 xForwardedFor=present"
                                + " xForwardedProto=http trusted=no scheme=http"),
                lines);
    }

    @Test
    void anIpv6UniqueLocalPeerIsTrustedThroughFc00() {
        app("fc00::/7")
                .get("/api/x")
                .remoteAddress("fd12:3456::1")
                .header("X-Forwarded-Proto", "https")
                .expect(200)
                .bodyContains("https");
        assertEquals(
                List.of(
                        "First request: peer=forwarded xForwardedFor=absent"
                                + " xForwardedProto=https trusted=yes scheme=https"),
                lines);
    }

    @Test
    void anOddProtoIsNeverEchoedAndNoHeadersIsUnknown() {
        JoltTest http = app("10.0.0.0/8");
        http.get("/api/x")
                .remoteAddress("127.0.0.1")
                .header("X-Forwarded-Proto", "<script>")
                .expect(200);
        assertEquals(
                List.of(
                        "First request: peer=127.0.0.1 xForwardedFor=absent"
                                + " xForwardedProto=other trusted=unknown scheme=http"),
                lines);
        lines.clear();
        app().get("/api/x").remoteAddress("127.0.0.1").expect(200);
        assertEquals(
                List.of(
                        "First request: peer=127.0.0.1 xForwardedFor=absent"
                                + " xForwardedProto=absent trusted=unknown scheme=http"),
                lines);
    }
}
