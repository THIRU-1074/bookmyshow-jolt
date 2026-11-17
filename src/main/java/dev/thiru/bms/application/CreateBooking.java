package dev.thiru.bms.application;

import dev.thiru.bms.domain.Booking;
import dev.thiru.bms.domain.BookingCode;
import dev.thiru.bms.domain.BookingSeat;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.HoldStatus;
import dev.thiru.bms.domain.Screen;
import dev.thiru.bms.domain.SeatHold;
import dev.thiru.bms.domain.Show;
import dev.thiru.bms.domain.port.IdGenerator;
import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.SeatEvent;
import dev.thiru.bms.domain.port.SeatEventPublisher;
import dev.thiru.bms.domain.port.UnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** HELD booking from an ACTIVE hold; the hold becomes CONSUMED. */
public final class CreateBooking {

    private static final int CODE_ATTEMPTS = 10;

    private final UnitOfWork uow;
    private final SeatEventPublisher publisher;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateBooking(
            UnitOfWork uow, SeatEventPublisher publisher, IdGenerator ids, Clock clock) {
        this.uow = uow;
        this.publisher = publisher;
        this.ids = ids;
        this.clock = clock;
    }

    public Booking execute(long userId, String holdId) {
        EventBuffer events = new EventBuffer();
        Outcome<Booking> outcome = uow.inTransaction(r -> create(r, userId, holdId, events));
        events.publishTo(publisher);
        return outcome.orThrow();
    }

    private Outcome<Booking> create(
            Repositories r, long userId, String holdId, EventBuffer events) {
        Instant now = clock.instant();
        SeatHold hold =
                r.holds()
                        .findById(holdId)
                        .filter(h -> h.userId() == userId)
                        .orElseThrow(() -> DomainException.notFound("Hold"));
        if (hold.status() != HoldStatus.ACTIVE) {
            throw DomainException.bookingState("Hold is " + hold.status());
        }
        if (hold.isExpiredAt(now)) {
            // Commit the expiry so the seats come back even though the caller gets a 410.
            r.holds().update(hold.expire());
            events.released(
                    hold.showId(), r.locks().deleteByHold(holdId), SeatEvent.Reason.EXPIRED);
            return Outcome.fail(DomainException.holdExpired());
        }
        Show show = r.shows().findById(hold.showId()).orElseThrow();
        if (show.hasStarted(now)) {
            return Outcome.fail(DomainException.showStarted());
        }
        Screen screen = r.screens().findById(show.screenId()).orElseThrow();
        List<BookingSeat> seats = BookingSeat.price(show, screen.layout(), hold.seats());
        Booking booking =
                Booking.create(
                        ids.newId(),
                        uniqueCode(r),
                        userId,
                        show.id(),
                        holdId,
                        seats,
                        now,
                        hold.expiresAt());
        r.bookings().insert(booking);
        r.holds().update(hold.consume(now));
        r.locks().attachBooking(holdId, booking.id());
        return Outcome.ok(booking);
    }

    private String uniqueCode(Repositories r) {
        for (int i = 0; i < CODE_ATTEMPTS; i++) {
            String code = BookingCode.generate(ids.random());
            if (!r.bookings().existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("could not generate a unique booking code");
    }
}
