package dev.thiru.bms.domain;

import java.util.List;
import java.util.function.Function;

/** One page of results; {@code page} counts from 1. */
public record Page<T>(List<T> items, int page, int size, long total) {

    public Page {
        items = List.copyOf(items);
    }

    public int totalPages() {
        return size == 0 ? 0 : (int) ((total + size - 1) / size);
    }

    public <R> Page<R> map(Function<T, R> f) {
        return new Page<>(items.stream().map(f).toList(), page, size, total);
    }
}
