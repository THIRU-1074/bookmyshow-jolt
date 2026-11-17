package dev.thiru.bms.features.payments;

import java.time.Instant;

/**
 * The payments slice's wire types, copied component for component from {@code web.PaymentModule},
 * so the JSON is read exactly as before. {@code RecordGeneratorTest} holds them to {@code
 * api/openapi.yaml}.
 */
final class PaymentDtos {

    private PaymentDtos() {}

    /** Boxed amount so a missing field is a validation error, not a zero. */
    record WebhookBody(
            String eventId,
            String type,
            String paymentId,
            String gatewayRef,
            Long amount,
            String currency,
            Instant occurredAt) {}

    record CompleteRequest(String outcome) {}
}
