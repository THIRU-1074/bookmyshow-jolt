// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.Instant;
import java.util.List;

public record ShowReportDetail(
        long showId,
        String movieTitle,
        String cinemaName,
        Instant startsAt,
        String status,
        int capacity,
        int booked,
        double occupancyPercent,
        long revenue,
        long refunded,
        List<CategoryReport> byCategory) {}
