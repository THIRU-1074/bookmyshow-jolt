// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

public record CategoryReport(
        String category,
        int capacity,
        int booked,
        long revenue) {}
