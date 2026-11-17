// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.util.List;

public record BookingPage(
        List<Booking> items,
        int page,
        int size,
        long total,
        int totalPages) {}
