// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

public record Screen(
        long id,
        long cinemaId,
        String name,
        int capacity,
        Layout layout) {}
