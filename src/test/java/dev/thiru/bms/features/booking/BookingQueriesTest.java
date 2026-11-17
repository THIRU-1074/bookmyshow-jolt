package dev.thiru.bms.features.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The two {@code BookingQueries} cases of {@code AdminReportsAndQueriesTest}, on the V2 seed. */
class BookingQueriesTest {

    @RegisterExtension final BookingWorld w = new BookingWorld();
    final long show = w.show(3);
    final long ada = w.user("ada@example.com");
    final long eve = w.user("eve@example.com");
    final Booking confirmed = w.confirmed(ada, show, "A2", "G1");

    @Test
    void strangersGetNotFoundButAdminsAndOwnersGetTheBooking() {
        BookingQueries.BookingView mine = w.queries.get(ada, false, confirmed.id());
        assertEquals(show, mine.show().id());
        assertEquals("Koramangala Cineplex", mine.show().cinemaName());
        assertEquals("Audi 1", mine.show().screenName());
        assertEquals(w.seedDate.plusDays(3), mine.show().localDate());
        assertEquals(LocalTime.of(10, 0), mine.show().localTime());
        assertEquals(confirmed.id(), w.queries.get(9_999, true, confirmed.id()).booking().id());
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> w.queries.get(eve, false, confirmed.id()))
                        .code());
        assertEquals(1, w.queries.list(ada, Set.of(BookingStatus.CONFIRMED), 1, 20).total());
        assertEquals(0, w.queries.list(ada, Set.of(BookingStatus.HELD), 1, 20).total());
        assertEquals(0, w.queries.list(eve, Set.of(), 1, 20).total());
    }

    @Test
    void ticketIsOwnerOnlyAndConfirmedOnly() {
        assertEquals(confirmed.code(), w.queries.ticket(ada, confirmed.id()).booking().code());
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> w.queries.ticket(9_999, confirmed.id()))
                        .code());
        Booking other = w.held(eve, show, "A5");
        assertEquals(
                Code.BOOKING_STATE,
                assertThrows(DomainException.class, () -> w.queries.ticket(eve, other.id()))
                        .code());
    }
}
