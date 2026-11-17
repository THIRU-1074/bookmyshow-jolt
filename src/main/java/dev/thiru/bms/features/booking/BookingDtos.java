package dev.thiru.bms.features.booking;

import dev.thiru.bms.api.generated.BookingSeat;
import dev.thiru.bms.api.generated.ShowInfo;
import dev.thiru.bms.kernel.booking.Booking;

/**
 * Maps the slice's views to its wire types, which are the records generated from {@code
 * api/openapi.yaml} ({@code dev.thiru.bms.api.generated}).
 */
final class BookingDtos {

    private BookingDtos() {}

    static dev.thiru.bms.api.generated.Booking booking(BookingQueries.BookingView v) {
        Booking b = v.booking();
        BookingSql.ShowInfo s = v.show();
        return new dev.thiru.bms.api.generated.Booking(
                b.id(),
                b.code(),
                b.status().name(),
                new ShowInfo(
                        s.id(),
                        s.movieTitle(),
                        s.cinemaName(),
                        s.screenName(),
                        s.startsAt(),
                        s.localTime()),
                b.seats().stream()
                        .map(x -> new BookingSeat(x.seat().toString(), x.category(), x.price()))
                        .toList(),
                b.amount(),
                b.currency(),
                b.refundAmount(),
                b.expiresAt(),
                b.version(),
                b.createdAt());
    }
}
