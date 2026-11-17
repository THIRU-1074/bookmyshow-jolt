package dev.thiru.bms.web;

import dev.thiru.bms.domain.Page;
import java.util.List;
import java.util.function.Function;

/**
 * The paging envelope {@code {items, page, size, total, totalPages}}.
 *
 * <p>TODO(jolt#57): delete when Jolt ships a pagination helper.
 */
record PageResult<T>(List<T> items, int page, int size, long total, int totalPages) {

    static <S, T> PageResult<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResult<>(
                page.items().stream().map(mapper).toList(),
                page.page(),
                page.size(),
                page.total(),
                page.totalPages());
    }
}
