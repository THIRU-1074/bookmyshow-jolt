// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.Instant;
import java.util.List;

public record Booking(
        String id,
        String code,
        String status,
        ShowInfo show,
        List<BookingSeat> seats,
        long amount,
        String currency,
        long refundAmount,
        Instant expiresAt,
        long version,
        Instant createdAt) {}
