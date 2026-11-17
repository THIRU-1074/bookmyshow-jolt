// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.Instant;

public record WebhookEvent(
        String eventId,
        String type,
        String paymentId,
        String gatewayRef,
        Long amount,
        String currency,
        Instant occurredAt) {}
