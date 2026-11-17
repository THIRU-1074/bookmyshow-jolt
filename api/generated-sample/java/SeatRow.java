// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.util.List;

public record SeatRow(
        String row,
        List<Seat> seats,
        List<Integer> aisleAfter) {}
