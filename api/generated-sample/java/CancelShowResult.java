// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

public record CancelShowResult(
        long showId,
        int refunded,
        int cancelled,
        int holdsReleased) {}
