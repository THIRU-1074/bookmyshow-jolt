package dev.thiru.bms.adapters.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MockGatewayClientTest {

    private static final Instant NOW = Instant.parse("2026-11-20T13:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final MockGatewayClient.Event EVENT =
            new MockGatewayClient.Event(
                    "evt_1", "payment.succeeded", "pay_9", "gw_\"7\"", 45000L, "INR", NOW);

    @Test
    void jsonFollowsSpecFieldsAndEscapes() {
        assertEquals(
                "{\"eventId\":\"evt_1\",\"type\":\"payment.succeeded\",\"paymentId\":\"pay_9\","
                        + "\"gatewayRef\":\"gw_\\\"7\\\"\",\"amount\":45000,\"currency\":\"INR\","
                        + "\"occurredAt\":\"2026-11-20T13:00:00Z\"}",
                EVENT.toJson());
    }

    @Test
    void escapesControlCharacters() {
        MockGatewayClient.Event e =
                new MockGatewayClient.Event("a\n\t\\\u0001", "t", "p", "g", 1L, "INR", NOW);
        assertEquals(true, e.toJson().contains("\"eventId\":\"a\\n\\t\\\\\\u0001\""));
    }

    @Test
    void postsSignedWebhookOverHttp() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> ts = new AtomicReference<>();
        AtomicReference<String> sig = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/payments/webhook",
                ex -> {
                    body.set(
                            new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    ts.set(ex.getRequestHeaders().getFirst("Bms-Timestamp"));
                    sig.set(ex.getRequestHeaders().getFirst("Bms-Signature"));
                    contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
                    byte[] out = "{\"status\":\"CONFIRMED\"}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                    ex.close();
                });
        server.start();
        try {
            WebhookSigner signer = new WebhookSigner("s3cret");
            URI url =
                    URI.create(
                            "http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/api/payments/webhook");
            MockGatewayClient client =
                    new MockGatewayClient(HttpClient.newHttpClient(), signer, CLOCK, url);

            assertEquals(200, client.post(EVENT));

            assertEquals(EVENT.toJson(), body.get());
            assertEquals(Long.toString(NOW.getEpochSecond()), ts.get());
            assertEquals(signer.sign(NOW.getEpochSecond(), body.get()), sig.get());
            assertEquals("application/json", contentType.get());
        } finally {
            server.stop(0);
        }
    }
}
