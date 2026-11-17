package dev.thiru.bms.application;

import dev.thiru.bms.domain.BookingPolicy;
import dev.thiru.bms.domain.BookingSeat;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.HoldStatus;
import dev.thiru.bms.domain.Screen;
import dev.thiru.bms.domain.SeatHold;
import dev.thiru.bms.domain.Show;
import dev.thiru.bms.domain.port.IdGenerator;
import dev.thiru.bms.domain.port.SeatEvent;
import dev.thiru.bms.domain.port.SeatEventPublisher;
import dev.thiru.bms.domain.port.UnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** SPEC 5.3: all-or-nothing seat hold, in one transaction. */
public final class CreateHold {

    /** The new hold with its server-computed price. */
    public record Result(SeatHold hold, long amount, String currency) {}

    private final UnitOfWork uow;
    private final SeatEventPublisher publisher;
    private final IdGenerator ids;
    private final Clock clock;
    private final BookingPolicy policy;

    public CreateHold(
            UnitOfWork uow,
            SeatEventPublisher publisher,
            IdGenerator ids,
            Clock clock,
            BookingPolicy policy) {
        this.uow = uow;
        this.publisher = publisher;
        this.ids = ids;
        this.clock = clock;
        this.policy = policy;
    }

    public Result execute(long userId, long showId, List<String> seats) {
        EventBuffer events = new EventBuffer();
        Result result =
                uow.inTransaction(
                        r -> {
                            Instant now = clock.instant();
                            Show show =
                                    r.shows()
                                            .findById(showId)
                                            .orElseThrow(() -> DomainException.notFound("Show"));
                            Screen screen = r.screens().findById(show.screenId()).orElseThrow();
                            SeatHold hold =
                                    SeatHold.create(
                                            ids.newId(), show, screen, userId, seats, now, policy);

                            // One active hold per user per show: a new one replaces the old.
                            r.holds()
                                    .findActiveByUserAndShow(userId, showId)
                                    .ifPresent(
                                            old -> {
                                                r.holds().update(old.release());
                                                events.released(
                                                        showId,
                                                        r.locks().deleteByHold(old.id()),
                                                        SeatEvent.Reason.RELEASED);
                                            });

                            // Lazy expiry: dead HELD locks on the requested seats must not block
                            // us.
                            events.released(
                                    showId,
                                    r.locks().deleteExpiredHeld(showId, hold.seats(), now),
                                    SeatEvent.Reason.EXPIRED);

                            r.holds().insert(hold);
                            // Throws SEAT_UNAVAILABLE and rolls the whole hold back on any
                            // conflict.
                            r.locks().lock(showId, hold.id(), hold.seats(), hold.expiresAt());

                            events.add(new SeatEvent.Held(showId, hold.seats(), hold.expiresAt()));
                            List<BookingSeat> priced =
                                    BookingSeat.price(show, screen.layout(), hold.seats());
                            long amount = priced.stream().mapToLong(BookingSeat::price).sum();
                            return new Result(hold, amount, "INR");
                        });
        events.publishTo(publisher);
        return result;
    }

    /** Owner-only release; unknown, foreign and non-active holds all read as not found (SPEC 7). */
    public void release(long userId, String holdId) {
        EventBuffer events = new EventBuffer();
        uow.inTransaction(
                r -> {
                    SeatHold hold =
                            r.holds()
                                    .findById(holdId)
                                    .filter(
                                            h ->
                                                    h.userId() == userId
                                                            && h.status() == HoldStatus.ACTIVE)
                                    .orElseThrow(() -> DomainException.notFound("Hold"));
                    r.holds().update(hold.release());
                    events.released(
                            hold.showId(),
                            r.locks().deleteByHold(holdId),
                            SeatEvent.Reason.RELEASED);
                    return null;
                });
        events.publishTo(publisher);
    }
}
