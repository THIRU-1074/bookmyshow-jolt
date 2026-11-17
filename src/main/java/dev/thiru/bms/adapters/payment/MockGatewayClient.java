package dev.thiru.bms.adapters.payment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Mock gateway side of the webhook: signs an event and POSTs it over real HTTP to the app's own
 * webhook endpoint (SPEC section 7).
 *
 * <p>TODO(jolt#59): signing lives in {@link HmacVerifier} until Jolt ships webhook support.
 */
public final class MockGatewayClient {

    /** Webhook body; amount is in paise. {@code type} is payment.succeeded or payment.failed. */
    public record Event(
            String eventId,
            String type,
            String paymentId,
            String gatewayRef,
            long amount,
            String currency,
            Instant occurredAt) {

        /** Serialises in the field order of the spec; this exact string is what gets signed. */
        public String toJson() {
            return "{\"eventId\":"
                    + quote(eventId)
                    + ",\"type\":"
                    + quote(type)
                    + ",\"paymentId\":"
                    + quote(paymentId)
                    + ",\"gatewayRef\":"
                    + quote(gatewayRef)
                    + ",\"amount\":"
                    + amount
                    + ",\"currency\":"
                    + quote(currency)
                    + ",\"occurredAt\":"
                    + quote(occurredAt.toString())
                    + "}";
        }

        private static String quote(String s) {
            StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.append('"').toString();
        }
    }

    private final HttpClient http;
    private final HmacVerifier signer;
    private final Clock clock;
    private final URI webhookUrl;

    public MockGatewayClient(HttpClient http, HmacVerifier signer, Clock clock, URI webhookUrl) {
        this.http = http;
        this.signer = signer;
        this.clock = clock;
        this.webhookUrl = webhookUrl;
    }

    /** Signs and delivers the event; returns the HTTP status of the webhook response. */
    public int post(Event event) throws IOException, InterruptedException {
        String body = event.toJson();
        long ts = clock.instant().getEpochSecond();
        HttpRequest request =
                HttpRequest.newBuilder(webhookUrl)
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Bms-Timestamp", Long.toString(ts))
                        .header("Bms-Signature", signer.sign(ts, body))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
