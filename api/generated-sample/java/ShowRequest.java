// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.Instant;
import java.util.Map;

public record ShowRequest(
        Long movieId,
        Long screenId,
        Instant startsAt,
        String language,
        String format,
        Map<String, Long> prices) {}
