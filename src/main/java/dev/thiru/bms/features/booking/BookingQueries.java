package dev.thiru.bms.features.booking;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.ports.Db;
import java.util.Set;

/** Read side of bookings with ownership rules: strangers see "not found", never "forbidden". */
final class BookingQueries {

    record BookingView(Booking booking, BookingSql.ShowInfo show) {}

    private final Db db;

    BookingQueries(Db db) {
        this.db = db;
    }

    /** Owner or admin; anyone else gets NOT_FOUND so ids cannot be probed. */
    BookingView get(long userId, boolean admin, String bookingId) {
        return db.inTransaction(
                tx -> {
                    Booking b =
                            BookingSql.booking(tx, bookingId)
                                    .filter(x -> admin || x.userId() == userId)
                                    .orElseThrow(() -> DomainException.notFound("Booking"));
                    return new BookingView(b, BookingSql.showInfo(tx, b.showId()));
                });
    }

    Page<BookingView> list(long userId, Set<BookingStatus> statuses, int page, int size) {
        return db.inTransaction(
                tx ->
                        BookingSql.page(tx, userId, statuses, page, size)
                                .map(b -> new BookingView(b, BookingSql.showInfo(tx, b.showId()))));
    }

    /** Owner only, CONFIRMED only. */
    BookingView ticket(long userId, String bookingId) {
        BookingView v = get(userId, false, bookingId);
        if (v.booking().status() != BookingStatus.CONFIRMED) {
            throw DomainException.bookingState("A ticket exists only for a confirmed booking");
        }
        return v;
    }
}
