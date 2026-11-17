// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.LocalDate;
import java.util.List;

public record MovieDetail(
        long id,
        String title,
        List<String> languages,
        List<String> genres,
        String certificate,
        int durationMinutes,
        LocalDate releaseDate,
        String posterUrl,
        String description) {}
