// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.Instant;
import java.time.LocalTime;

public record Slot(
        long id,
        Instant startsAt,
        LocalTime localTime,
        String language,
        String format,
        long minPrice,
        String availability) {}
