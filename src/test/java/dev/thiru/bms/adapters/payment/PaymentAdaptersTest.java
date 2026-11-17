package dev.thiru.bms.adapters.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.application.ProcessPaymentWebhook;
import dev.thiru.bms.application.WebhookVerifier;
import dev.thiru.bms.domain.port.PaymentGateway;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PaymentAdaptersTest {

    private static final Instant NOW = Instant.parse("2026-11-20T13:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SECRET = "webhook-secret-that-is-32-bytes-long!";

    @Test
    void webhookVerifierAcceptsOnlyAFreshCorrectSignature() {
        HmacVerifier hmac = new HmacVerifier(SECRET, CLOCK);
        WebhookVerifier verifier = new HmacWebhookVerifier(hmac);
        long ts = NOW.getEpochSecond();
        String body = "{\"eventId\":\"e1\"}";
        assertTrue(verifier.verify(Long.toString(ts), hmac.sign(ts, body), body));
        assertFalse(verifier.verify(Long.toString(ts), hmac.sign(ts, body), body + " "));
        long stale = ts - HmacVerifier.MAX_SKEW_SECONDS - 1;
        assertFalse(verifier.verify(Long.toString(stale), hmac.sign(stale, body), body));
        assertFalse(verifier.verify(null, null, body));
    }

    @Test
    void senderMapsUseCaseEventsToTheWireShape() {
        var succeeded =
                new ProcessPaymentWebhook.Event(
                        "evt_1",
                        ProcessPaymentWebhook.Type.PAYMENT_SUCCEEDED,
                        "pay_1",
                        "mock_pay_1",
                        45000,
                        "INR",
                        NOW);
        assertEquals(
                new MockGatewayClient.Event(
                        "evt_1", "payment.succeeded", "pay_1", "mock_pay_1", 45000, "INR", NOW),
                MockGatewaySender.toWire(succeeded));
        var failed =
                new ProcessPaymentWebhook.Event(
                        "evt_2",
                        ProcessPaymentWebhook.Type.PAYMENT_FAILED,
                        "pay_1",
                        "mock_pay_1",
                        45000,
                        "INR",
                        NOW);
        assertEquals("payment.failed", MockGatewaySender.toWire(failed).type());
    }

    @Test
    void mockGatewaySessionsPointAtTheMockPageUnderTheBaseUrl() {
        PaymentGateway gateway = new MockPaymentGateway(URI.create("https://bms.example.com/"));
        PaymentGateway.CheckoutSession s = gateway.createSession("pay-1", 45000, "INR");
        assertEquals("mock_pay-1", s.gatewayRef());
        assertEquals("https://bms.example.com/mock-gateway/pay-1", s.checkoutUrl());
        gateway.refund("mock_pay-1", 45000);
        assertThrows(IllegalArgumentException.class, () -> gateway.refund("mock_pay-1", -1));
    }
}
