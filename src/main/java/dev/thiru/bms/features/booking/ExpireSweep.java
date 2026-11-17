package dev.thiru.bms.features.booking;

import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.SeatHold;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.Db.Tx;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.ports.SeatEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Periodic cleanup of expired holds and open bookings; {@code Main} schedules it. Correctness never
 * depends on it (every transition re-checks expiry); it only frees seats nobody touched and keeps
 * rows tidy.
 */
public final class ExpireSweep {

    public static final int BATCH = 500;

    public record Result(int holds, int bookings) {}

    private final Db db;
    private final SeatEvents events;
    private final Clock clock;

    public ExpireSweep(Db db, SeatEvents events, Clock clock) {
        this.db = db;
        this.events = events;
        this.clock = clock;
    }

    /** Runs batches of {@value #BATCH}, one transaction each, until a batch comes back short. */
    public Result run() {
        int holds = 0;
        int bookings = 0;
        while (true) {
            int[] counts = db.inTransaction(this::sweepBatch);
            holds += counts[0];
            bookings += counts[1];
            if (counts[0] < BATCH && counts[1] < BATCH) {
                return new Result(holds, bookings);
            }
        }
    }

    private int[] sweepBatch(Tx tx) {
        Instant now = clock.instant();
        List<SeatHold> expiredHolds = BookingSql.expiredActiveHolds(tx, now, BATCH);
        for (SeatHold hold : expiredHolds) {
            BookingSql.updateHold(tx, hold.expire());
            BookingOps.released(
                    tx,
                    events,
                    hold.showId(),
                    BookingSql.deleteLocks(tx, hold.id()),
                    SeatEvent.Reason.EXPIRED);
        }
        List<Booking> expiredBookings = BookingSql.openExpiredBookings(tx, now, BATCH);
        for (Booking booking : expiredBookings) {
            BookingOps.expire(tx, events, booking, now);
        }
        return new int[] {expiredHolds.size(), expiredBookings.size()};
    }
}
