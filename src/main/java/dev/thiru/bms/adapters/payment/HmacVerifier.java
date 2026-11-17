package dev.thiru.bms.adapters.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies webhook requests: {@code v1=<hex HMAC-SHA256(secret, timestamp + "." +
 * rawBody)>}.
 *
 * <p>TODO(jolt#59): delete when Jolt ships webhook signature verification.
 */
public final class HmacVerifier {

    /** Maximum distance between the signed timestamp and now, in seconds. */
    public static final long MAX_SKEW_SECONDS = 300;

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "v1=";
    private static final int HEX_LENGTH = 64;

    /** Result of a verification; the caller maps anything but VALID to 401. */
    public enum Outcome {
        VALID,
        MALFORMED,
        STALE_TIMESTAMP,
        BAD_SIGNATURE
    }

    private final SecretKeySpec key;
    private final Clock clock;

    public HmacVerifier(String secret, Clock clock) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("webhook secret must not be empty");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.clock = clock;
    }

    /**
     * Returns the {@code Bms-Signature} header value for the given timestamp (unix seconds) and raw
     * body.
     */
    public String sign(long timestampSeconds, String rawBody) {
        return PREFIX + HexFormat.of().formatHex(mac(timestampSeconds, rawBody));
    }

    /**
     * Verifies the {@code Bms-Timestamp} and {@code Bms-Signature} headers against the raw body.
     */
    public Outcome verify(String timestampHeader, String signatureHeader, String rawBody) {
        if (timestampHeader == null || signatureHeader == null || rawBody == null) {
            return Outcome.MALFORMED;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return Outcome.MALFORMED;
        }
        if (!signatureHeader.startsWith(PREFIX)
                || signatureHeader.length() != PREFIX.length() + HEX_LENGTH) {
            return Outcome.MALFORMED;
        }
        String hex = signatureHeader.substring(PREFIX.length());
        byte[] provided;
        try {
            if (!hex.equals(hex.toLowerCase(Locale.ROOT))) {
                return Outcome.MALFORMED;
            }
            provided = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return Outcome.MALFORMED;
        }
        // Signature first: an unsigned probe learns nothing about the clock window.
        if (!MessageDigest.isEqual(mac(timestamp, rawBody), provided)) {
            return Outcome.BAD_SIGNATURE;
        }
        long skew = Math.abs(clock.instant().getEpochSecond() - timestamp);
        return skew > MAX_SKEW_SECONDS ? Outcome.STALE_TIMESTAMP : Outcome.VALID;
    }

    private byte[] mac(long timestampSeconds, String rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal((timestampSeconds + "." + rawBody).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
