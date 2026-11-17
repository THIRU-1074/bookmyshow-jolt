package dev.thiru.bms.application;

/**
 * Checks a webhook's signature and freshness. Defined here so the web layer needs no adapter; Main
 * adapts the HMAC implementation.
 */
@FunctionalInterface
public interface WebhookVerifier {

    /** True only for a correct signature on a timestamp within the allowed skew. */
    boolean verify(String timestampHeader, String signatureHeader, String rawBody);
}
