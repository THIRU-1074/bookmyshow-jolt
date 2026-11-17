// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.Instant;

public record ShowReport(
        long showId,
        String movieTitle,
        String cinemaName,
        Instant startsAt,
        String status,
        int capacity,
        int booked,
        double occupancyPercent,
        long revenue,
        long refunded) {}
