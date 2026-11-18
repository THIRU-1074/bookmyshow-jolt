package dev.thiru.bms.kernel.catalog;

import dev.thiru.bms.kernel.DomainException;

/** An auditorium with a validated seat plan. */
public record Screen(long id, long cinemaId, String name, SeatLayout layout) {

    public static final int MAX_CAPACITY = 1000;

    public Screen {
        if (name == null || name.isBlank()) {
            throw DomainException.validation("name", "must not be blank");
        }
        if (layout == null) {
            throw DomainException.validation("layout", "is required");
        }
        int capacity = layout.capacity();
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw DomainException.validation("layout", "capacity must be 1 to " + MAX_CAPACITY);
        }
    }

    public int capacity() {
        return layout.capacity();
    }
}
