package dev.thiru.bms.domain;

import java.util.List;

/** A seat on a booking with the category and price (paise) frozen at hold time. */
public record BookingSeat(SeatId seat, String category, long price) {

    /** Prices come from the show, never from the client (SPEC 5.9). */
    public static List<BookingSeat> price(Show show, SeatLayout layout, List<SeatId> seats) {
        return seats.stream()
                .map(
                        s -> {
                            String category = layout.categoryOf(s);
                            return new BookingSeat(s, category, show.prices().get(category));
                        })
                .toList();
    }
}
