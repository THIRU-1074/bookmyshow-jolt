// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.util.List;

public record SeatMap(
        long showId,
        long version,
        List<Price> categories,
        List<SeatRow> rows,
        long holdTtlSeconds) {}
