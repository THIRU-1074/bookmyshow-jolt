package dev.thiru.bms.features.booking;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingCode;
import dev.thiru.bms.kernel.booking.BookingSeat;
import dev.thiru.bms.kernel.booking.HoldStatus;
import dev.thiru.bms.kernel.booking.SeatHold;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.Db.Tx;
import dev.thiru.bms.ports.Ids;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.ports.SeatEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** HELD booking from an ACTIVE hold; the hold becomes CONSUMED. */
final class CreateBooking {

    private static final int CODE_ATTEMPTS = 10;

    private final Db db;
    private final SeatEvents events;
    private final Ids ids;
    private final Clock clock;

    CreateBooking(Db db, SeatEvents events, Ids ids, Clock clock) {
        this.db = db;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    Booking execute(long userId, String holdId) {
        return db.inTransaction(tx -> create(tx, userId, holdId)).orThrow();
    }

    private Outcome<Booking> create(Tx tx, long userId, String holdId) {
        Instant now = clock.instant();
        SeatHold hold =
                BookingSql.hold(tx, holdId)
                        .filter(h -> h.userId() == userId)
                        .orElseThrow(() -> DomainException.notFound("Hold"));
        if (hold.status() != HoldStatus.ACTIVE) {
            throw DomainException.bookingState("Hold is " + hold.status());
        }
        if (hold.isExpiredAt(now)) {
            // Commit the expiry so the seats come back even though the caller gets a 410.
            BookingSql.updateHold(tx, hold.expire());
            BookingOps.released(
                    tx,
                    events,
                    hold.showId(),
                    BookingSql.deleteLocks(tx, holdId),
                    SeatEvent.Reason.EXPIRED);
            return Outcome.fail(DomainException.holdExpired());
        }
        Show show = BookingSql.show(tx, hold.showId()).orElseThrow();
        if (show.hasStarted(now)) {
            return Outcome.fail(DomainException.showStarted());
        }
        Screen screen = BookingSql.screen(tx, show.screenId());
        List<BookingSeat> seats = BookingSeat.price(show, screen.layout(), hold.seats());
        Booking booking =
                Booking.create(
                        ids.newId(),
                        uniqueCode(tx),
                        userId,
                        show.id(),
                        holdId,
                        seats,
                        now,
                        hold.expiresAt());
        BookingSql.insertBooking(tx, booking);
        BookingSql.updateHold(tx, hold.consume(now));
        BookingSql.attachBooking(tx, holdId, booking.id());
        return Outcome.ok(booking);
    }

    private String uniqueCode(Tx tx) {
        for (int i = 0; i < CODE_ATTEMPTS; i++) {
            String code = BookingCode.generate(ids.random());
            if (!BookingSql.codeExists(tx, code)) {
                return code;
            }
        }
        throw new IllegalStateException("could not generate a unique booking code");
    }
}
