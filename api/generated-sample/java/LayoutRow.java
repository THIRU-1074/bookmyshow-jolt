// Generated from api/openapi.yaml by RecordGenerator. Do not edit.
package dev.thiru.bms.api.generated;

import java.util.List;

public record LayoutRow(
        String row,
        int seats,
        String category,
        List<Integer> aisleAfter,
        List<Integer> blocked) {}
