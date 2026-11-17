// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.Instant;
import java.util.Map;

public record Show(
        long id,
        long movieId,
        long screenId,
        Instant startsAt,
        String language,
        String format,
        Map<String, Long> prices,
        String status) {}
