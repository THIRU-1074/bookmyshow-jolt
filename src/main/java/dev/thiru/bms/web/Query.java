package dev.thiru.bms.web;

import in.jolt.Params;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Query-string parsing with 422 field errors. Multi-value filters take comma lists only until Jolt
 * can read repeated parameters.
 *
 * <p>TODO(jolt#52): read repeated parameters with {@code req.queryAll} and drop the comma-only
 * limitation.
 */
final class Query {

    private Query() {}

    /** {@code ?genre=Action,Drama} to {@code {"Action","Drama"}}; blank parts are dropped. */
    static Set<String> list(Params p, String name) {
        String raw = p.query(name);
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(out::add);
        }
        return out;
    }

    static <T> Set<T> list(Params p, String name, Function<String, T> parse) {
        Set<T> out = new LinkedHashSet<>();
        for (String s : list(p, name)) {
            try {
                out.add(parse.apply(s));
            } catch (RuntimeException e) {
                throw ApiException.validation(name, "unknown value '" + s + "'");
            }
        }
        return out;
    }

    static LocalDate date(Params p, String name) {
        String raw = p.query(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.validation(name, "must be a date like 2026-11-20");
        }
    }

    static String text(Params p, String name) {
        String raw = p.query(name);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /** Path id as a positive long; anything else reads as "no such resource". */
    static long id(Params p, String name) {
        try {
            long v = Long.parseLong(p.param(name));
            if (v > 0) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // falls through
        }
        throw ApiException.notFound("No such resource");
    }
}
