package dev.thiru.bms.features.booking;

import dev.thiru.bms.features.booking.sql.BookingSql;
import dev.thiru.bms.kernel.booking.BookingState;
import dev.thiru.bms.ports.Payments;
import dev.thiru.bms.web.Paging;
import in.jolt.Router;

final class BookingRoutes {
    BookingRoutes(Router r, Payments payments, BookingSql sql) {
        BookingState s = BookingState.HELD;
    }
}
