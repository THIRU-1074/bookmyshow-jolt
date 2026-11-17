// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.util.List;

public record CinemaPage(
        List<Cinema> items,
        int page,
        int size,
        long total,
        int totalPages) {}
