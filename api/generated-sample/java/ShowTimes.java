// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.time.LocalDate;
import java.util.List;

public record ShowTimes(
        LocalDate date,
        List<CinemaShows> cinemas) {}
