// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.util.List;

public record CinemaDetail(
        long id,
        String cityId,
        String name,
        String address,
        List<ScreenSummary> screens) {}
