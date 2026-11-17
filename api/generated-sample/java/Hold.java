// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.Instant;
import java.util.List;

public record Hold(
        String id,
        long showId,
        List<String> seats,
        long amount,
        String currency,
        Instant expiresAt) {}
