package dev.thiru.bms.adapters.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The mock gateway's half of the webhook contract: {@code v1=<hex HMAC-SHA256(secret, timestamp +
 * "." + rawBody)>} (SPEC 7). Jolt's {@code Webhooks.hmacSha256} verifies it on the receiving side;
 * Jolt has no signer, so this class stays.
 */
public final class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "v1=";

    private final SecretKeySpec key;

    public WebhookSigner(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("webhook secret must not be empty");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /**
     * Returns the {@code Bms-Signature} header value for the given timestamp (unix seconds) and raw
     * body.
     */
    public String sign(long timestampSeconds, String rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] signed =
                    mac.doFinal(
                            (timestampSeconds + "." + rawBody).getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(signed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
