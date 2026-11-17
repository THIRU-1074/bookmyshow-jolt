// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

public record ShowDetail(
        long id,
        MovieRef movie,
        NamedRef cinema,
        NamedRef screen,
        Instant startsAt,
        LocalTime localTime,
        String language,
        String format,
        List<Price> prices,
        String status) {}
