package dev.thiru.bms.adapters.payment;

import dev.thiru.bms.domain.port.PaymentGateway;
import java.net.URI;

/**
 * The outbound side of the mock gateway: a checkout session is the app's own {@code
 * /mock-gateway/{paymentId}} page, and refunds succeed synchronously (SPEC 4). The inbound result
 * arrives later as a signed webhook.
 */
public final class MockPaymentGateway implements PaymentGateway {

    private static final System.Logger LOG = System.getLogger("bms.payment");

    private final String baseUrl;

    /** {@code publicBaseUrl} is PUBLIC_BASE_URL; a trailing slash is ignored. */
    public MockPaymentGateway(URI publicBaseUrl) {
        String s = publicBaseUrl.toString();
        this.baseUrl = s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public CheckoutSession createSession(String paymentId, long amount, String currency) {
        return new CheckoutSession("mock_" + paymentId, baseUrl + "/mock-gateway/" + paymentId);
    }

    @Override
    public void refund(String gatewayRef, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("refund amount must not be negative");
        }
        LOG.log(System.Logger.Level.INFO, "mock refund " + gatewayRef);
    }
}
