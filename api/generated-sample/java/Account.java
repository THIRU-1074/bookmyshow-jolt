// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.util.List;

public record Account(
        long id,
        String email,
        String name,
        List<String> roles,
        List<Long> cinemaIds) {}
