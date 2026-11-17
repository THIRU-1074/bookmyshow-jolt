package dev.thiru.bms.features.booking;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.kernel.booking.BookingSeat;
import dev.thiru.bms.kernel.booking.HoldStatus;
import dev.thiru.bms.kernel.booking.SeatHold;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.Ids;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.ports.SeatEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** SPEC 5.3: all-or-nothing seat hold, in one transaction; and its owner-only release. */
final class CreateHold {

    /** The new hold with its server-computed price. */
    record Result(SeatHold hold, long amount, String currency) {}

    private final Db db;
    private final SeatEvents events;
    private final Ids ids;
    private final Clock clock;
    private final BookingPolicy policy;

    CreateHold(Db db, SeatEvents events, Ids ids, Clock clock, BookingPolicy policy) {
        this.db = db;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.policy = policy;
    }

    Result execute(long userId, long showId, List<String> seats) {
        return db.inTransaction(
                tx -> {
                    Instant now = clock.instant();
                    Show show =
                            BookingSql.show(tx, showId)
                                    .orElseThrow(() -> DomainException.notFound("Show"));
                    Screen screen = BookingSql.screen(tx, show.screenId());
                    SeatHold hold =
                            SeatHold.create(ids.newId(), show, screen, userId, seats, now, policy);

                    // One active hold per user per show: a new one replaces the old.
                    BookingSql.activeHold(tx, userId, showId)
                            .ifPresent(
                                    old -> {
                                        BookingSql.updateHold(tx, old.release());
                                        BookingOps.released(
                                                tx,
                                                events,
                                                showId,
                                                BookingSql.deleteLocks(tx, old.id()),
                                                SeatEvent.Reason.RELEASED);
                                    });

                    // Lazy expiry: dead HELD locks on the requested seats must not block us.
                    BookingOps.released(
                            tx,
                            events,
                            showId,
                            BookingSql.deleteExpiredHeld(tx, showId, hold.seats(), now),
                            SeatEvent.Reason.EXPIRED);

                    BookingSql.insertHold(tx, hold);
                    // Throws SEAT_UNAVAILABLE and rolls the whole hold back on any conflict.
                    BookingSql.lock(tx, showId, hold.id(), hold.seats(), hold.expiresAt());

                    BookingOps.publish(
                            tx, events, new SeatEvent.Held(showId, hold.seats(), hold.expiresAt()));
                    long amount =
                            BookingSeat.price(show, screen.layout(), hold.seats()).stream()
                                    .mapToLong(BookingSeat::price)
                                    .sum();
                    return new Result(hold, amount, "INR");
                });
    }

    /** Owner-only release; unknown, foreign and non-active holds all read as not found (SPEC 7). */
    void release(long userId, String holdId) {
        db.inTransaction(
                tx -> {
                    SeatHold hold =
                            BookingSql.hold(tx, holdId)
                                    .filter(
                                            h ->
                                                    h.userId() == userId
                                                            && h.status() == HoldStatus.ACTIVE)
                                    .orElseThrow(() -> DomainException.notFound("Hold"));
                    BookingSql.updateHold(tx, hold.release());
                    BookingOps.released(
                            tx,
                            events,
                            hold.showId(),
                            BookingSql.deleteLocks(tx, holdId),
                            SeatEvent.Reason.RELEASED);
                    return null;
                });
    }
}
