package dev.thiru.bms.adapters.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.App;
import in.jolt.Jolt;
import in.jolt.Webhooks;
import in.jolt.test.JoltTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    private static final Instant NOW = Instant.parse("2026-11-20T13:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SECRET = "webhook-secret-that-is-32-bytes-long!";
    private static final String BODY = "{\"eventId\":\"e1\"}";
    private final WebhookSigner signer = new WebhookSigner(SECRET);
    private final long ts = NOW.getEpochSecond();

    @Test
    void signatureHasV1HexFormatAndIsDeterministic() {
        String sig = signer.sign(ts, BODY);
        assertTrue(sig.matches("v1=[0-9a-f]{64}"), sig);
        assertEquals(sig, signer.sign(ts, BODY));
        assertNotEquals(sig, signer.sign(ts + 1, BODY));
        assertNotEquals(sig, signer.sign(ts, BODY + " "));
        assertNotEquals(sig, new WebhookSigner(SECRET + "x").sign(ts, BODY));
    }

    /** The receiving side is Jolt's middleware configured as in WebModules. */
    @Test
    void joltsWebhookCheckAcceptsOnlyAFreshCorrectSignature() {
        App app = Jolt.create();
        app.post(
                "/hook",
                Webhooks.hmacSha256(SECRET, "Bms-Signature")
                        .prefix("v1=")
                        .timestampHeader("Bms-Timestamp")
                        .tolerance(Duration.ofSeconds(300))
                        .clock(CLOCK),
                (req, res) -> res.status(204));
        JoltTest http = JoltTest.of(app);
        hook(http, ts, signer.sign(ts, BODY), BODY).expect(204);
        hook(http, ts, signer.sign(ts, BODY), BODY + " ").expect(401);
        long stale = ts - 301;
        hook(http, stale, signer.sign(stale, BODY), BODY).expect(401);
        hook(http, ts - 300, signer.sign(ts - 300, BODY), BODY).expect(204);
        hook(http, ts, new WebhookSigner(SECRET + "x").sign(ts, BODY), BODY).expect(401);
        http.post("/hook").text(BODY).expect(401);
    }

    private static JoltTest.Call hook(
            JoltTest http, long timestamp, String signature, String body) {
        return http.post("/hook")
                .header("Bms-Timestamp", Long.toString(timestamp))
                .header("Bms-Signature", signature)
                .text(body);
    }

    @Test
    void rejectsBlankSecret() {
        assertThrows(IllegalArgumentException.class, () -> new WebhookSigner(""));
    }
}
