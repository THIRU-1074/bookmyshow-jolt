package dev.thiru.bms.adapters.payment;

import dev.thiru.bms.application.WebhookVerifier;

/**
 * The application's webhook check on {@link HmacVerifier}: anything but a valid, fresh signature is
 * rejected, so the web layer answers 401 without learning why.
 *
 * <p>TODO(jolt#59): delete with {@link HmacVerifier} when Jolt ships webhook verification.
 */
public final class HmacWebhookVerifier implements WebhookVerifier {

    private final HmacVerifier hmac;

    public HmacWebhookVerifier(HmacVerifier hmac) {
        this.hmac = hmac;
    }

    @Override
    public boolean verify(String timestampHeader, String signatureHeader, String rawBody) {
        return hmac.verify(timestampHeader, signatureHeader, rawBody) == HmacVerifier.Outcome.VALID;
    }
}
