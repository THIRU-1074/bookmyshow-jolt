package dev.thiru.bms.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.PaymentGateway.CheckoutSession;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The outbound gateway as checkout, cancel, the webhook's late-success refund and show cancel (the
 * booking, payments and admin slices) use it. Each of those calls the gateway inside its
 * transaction and relies on a failed refund throwing, so that the transaction rolls back and
 * nothing claims money was returned.
 */
public abstract class PaymentGatewayContract {

    private PaymentGateway gateway;

    protected abstract PaymentGateway newGateway();

    /**
     * Makes the next refund on {@code gateway} fail and returns the amount to ask for. A gateway
     * with an outage switch flips it and returns a valid amount; one without (the mock refunds
     * every valid amount, SPEC 4) returns an amount it rejects.
     */
    protected abstract long failNextRefund(PaymentGateway gateway);

    @BeforeEach
    protected void create() {
        gateway = newGateway();
    }

    @Test
    protected void aSessionHasAGatewayRefAndAnAbsoluteCheckoutUrlForThatPayment() {
        CheckoutSession s = gateway.createSession("pay-123", 30000, "INR");
        assertFalse(s.gatewayRef().isBlank());
        URI url = URI.create(s.checkoutUrl());
        assertTrue(url.isAbsolute());
        assertTrue(url.getScheme().startsWith("http"));
        assertTrue(s.checkoutUrl().contains("pay-123"));
    }

    @Test
    protected void differentPaymentsGetDifferentRefs() {
        assertNotEquals(
                gateway.createSession("pay-1", 100, "INR").gatewayRef(),
                gateway.createSession("pay-2", 100, "INR").gatewayRef());
    }

    @Test
    protected void aRefundOfAValidAmountSucceeds() {
        String ref = gateway.createSession("pay-9", 30000, "INR").gatewayRef();
        assertDoesNotThrow(() -> gateway.refund(ref, 15000));
        assertDoesNotThrow(() -> gateway.refund(ref, 0));
    }

    @Test
    protected void aNegativeRefundIsRejected() {
        String ref = gateway.createSession("pay-9", 30000, "INR").gatewayRef();
        assertThrows(IllegalArgumentException.class, () -> gateway.refund(ref, -1));
    }

    @Test
    protected void aFailedRefundThrowsSoTheCallersTransactionRollsBack() {
        String ref = gateway.createSession("pay-9", 30000, "INR").gatewayRef();
        long amount = failNextRefund(gateway);
        assertThrows(RuntimeException.class, () -> gateway.refund(ref, amount));
    }
}
