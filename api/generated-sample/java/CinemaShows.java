// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.util.List;

public record CinemaShows(
        long id,
        String name,
        String address,
        List<Slot> shows) {}
