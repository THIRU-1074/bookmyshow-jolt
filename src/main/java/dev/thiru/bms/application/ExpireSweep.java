package dev.thiru.bms.application;

import dev.thiru.bms.domain.Booking;
import dev.thiru.bms.domain.SeatHold;
import dev.thiru.bms.domain.port.SeatEvent;
import dev.thiru.bms.domain.port.SeatEventPublisher;
import dev.thiru.bms.domain.port.UnitOfWork;
import java.time.Clock;
import java.time.Instant;

/**
 * Periodic cleanup of expired holds and open bookings. Correctness never depends on it (every
 * transition re-checks expiry); it only frees seats nobody touched and keeps rows tidy.
 */
public final class ExpireSweep {

    public static final int BATCH = 500;

    public record Result(int holds, int bookings) {}

    private final UnitOfWork uow;
    private final SeatEventPublisher publisher;
    private final Clock clock;

    public ExpireSweep(UnitOfWork uow, SeatEventPublisher publisher, Clock clock) {
        this.uow = uow;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Runs batches of {@value #BATCH}, one transaction each, until a batch comes back short. */
    public Result run() {
        int holds = 0;
        int bookings = 0;
        while (true) {
            EventBuffer events = new EventBuffer();
            int[] counts = uow.inTransaction(r -> sweepBatch(r, events));
            events.publishTo(publisher);
            holds += counts[0];
            bookings += counts[1];
            if (counts[0] < BATCH && counts[1] < BATCH) {
                return new Result(holds, bookings);
            }
        }
    }

    private int[] sweepBatch(dev.thiru.bms.domain.port.Repositories r, EventBuffer events) {
        Instant now = clock.instant();
        var expiredHolds = r.holds().findExpiredActive(now, BATCH);
        for (SeatHold hold : expiredHolds) {
            r.holds().update(hold.expire());
            events.released(
                    hold.showId(), r.locks().deleteByHold(hold.id()), SeatEvent.Reason.EXPIRED);
        }
        var expiredBookings = r.bookings().findOpenExpired(now, BATCH);
        for (Booking booking : expiredBookings) {
            BookingOps.expire(r, booking, now, events);
        }
        return new int[] {expiredHolds.size(), expiredBookings.size()};
    }
}
