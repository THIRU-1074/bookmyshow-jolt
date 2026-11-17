package dev.thiru.bms.web;

import in.jolt.Page;
import in.jolt.Params;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code ?page=1&size=20} (page from 1, size 1 to 100) and the paging envelope {@code {items, page,
 * size, total, totalPages}} of SPEC 7, on Jolt's {@link Page}.
 *
 * <p>Parameters are checked here rather than by {@code Page.of}: Jolt answers a bad value with a
 * 400 that does not name the field and silently caps an oversized {@code size}, while SPEC 7 wants
 * a 422 with {@code errors.page} or {@code errors.size}.
 */
final class Paging {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    private Paging() {}

    static Page request(Params p) {
        Map<String, String> errors = new LinkedHashMap<>();
        int page = number(p.query("page"), 1, "page", 1, Integer.MAX_VALUE, errors);
        int size = number(p.query("size"), DEFAULT_SIZE, "size", 1, MAX_SIZE, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return new Page(page, size);
    }

    /** The domain page as Jolt's JSON envelope, items mapped to their DTOs. */
    static <S, T> Page.Result<T> result(dev.thiru.bms.kernel.Page<S> page, Function<S, T> mapper) {
        return new Page(page.page(), page.size())
                .result(page.items().stream().map(mapper).toList(), page.total());
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
