package dev.thiru.bms.web;

import in.jolt.Params;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code ?page=1&size=20}: page from 1, size 1 to 100 (SPEC 7).
 *
 * <p>TODO(jolt#57): delete when Jolt ships a pagination helper.
 */
record PageRequest(int page, int size) {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    static PageRequest of(Params p) {
        Map<String, String> errors = new LinkedHashMap<>();
        int page = number(p.query("page"), 1, "page", 1, Integer.MAX_VALUE, errors);
        int size = number(p.query("size"), DEFAULT_SIZE, "size", 1, MAX_SIZE, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return new PageRequest(page, size);
    }

    private static int number(
            String raw, int fallback, String name, int min, int max, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < min || v > max) {
                errors.put(name, "must be " + min + " to " + max);
                return fallback;
            }
            return v;
        } catch (NumberFormatException e) {
            errors.put(name, "must be a number");
            return fallback;
        }
    }
}
