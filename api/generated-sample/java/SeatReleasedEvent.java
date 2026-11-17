// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.util.List;

public record SeatReleasedEvent(
        long showId,
        List<String> seats,
        String reason) {}
