// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.Instant;

public record Checkout(
        String paymentId,
        String checkoutUrl,
        long amount,
        String currency,
        Instant expiresAt) {}
