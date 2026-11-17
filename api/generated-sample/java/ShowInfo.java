// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.Instant;
import java.time.LocalTime;

public record ShowInfo(
        long id,
        String movieTitle,
        String cinemaName,
        String screenName,
        Instant startsAt,
        LocalTime localTime) {}
