// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.util.List;
import java.util.Map;

public record Problem(
        String type,
        String title,
        int status,
        String detail,
        String code,
        Map<String, String> errors,
        List<String> conflicts,
        String requestId) {}
