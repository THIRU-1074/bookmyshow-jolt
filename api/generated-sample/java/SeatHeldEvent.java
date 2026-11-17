// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.Instant;
import java.util.List;

public record SeatHeldEvent(
        long showId,
        List<String> seats,
        Instant until) {}
