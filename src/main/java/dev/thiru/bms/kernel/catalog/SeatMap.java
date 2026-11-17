package dev.thiru.bms.kernel.catalog;

import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.SeatLock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A show's seat map: the screen layout with each seat's status from the live locks, and the
 * category prices. Catalog serves it and the live stream sends it as its snapshot, so the assembly
 * lives here rather than in either slice.
 *
 * <p>{@code version} is a content hash of every seat status, so it changes exactly when the map
 * does.
 */
public record SeatMap(
        long showId, long version, List<Price> categories, List<Row> rows, long holdTtlSeconds) {

    public enum Status {
        AVAILABLE,
        HELD,
        BOOKED,
        BLOCKED
    }

    public record Price(String category, String name, long amount) {}

    public record Seat(SeatId id, int number, String category, Status status) {}

    public record Row(String row, List<Seat> seats, List<Integer> aisleAfter) {}

    public static SeatMap assemble(
            long showId,
            SeatLayout layout,
            Map<String, Long> prices,
            Collection<SeatLock> locks,
            Instant now,
            Duration holdTtl) {
        Map<SeatId, Status> taken = new HashMap<>();
        for (SeatLock l : locks) {
            if (l.isLiveAt(now)) {
                taken.put(
                        l.seat(), l.state() == SeatLock.State.BOOKED ? Status.BOOKED : Status.HELD);
            }
        }
        List<Row> rows = new ArrayList<>();
        List<Object> fingerprint = new ArrayList<>();
        for (SeatLayout.Row row : layout.rows()) {
            List<Seat> seats = new ArrayList<>();
            for (int n = 1; n <= row.seats(); n++) {
                SeatId id = new SeatId(row.row().charAt(0), n);
                Status status =
                        layout.isBlocked(id)
                                ? Status.BLOCKED
                                : taken.getOrDefault(id, Status.AVAILABLE);
                seats.add(new Seat(id, n, row.category(), status));
                fingerprint.add(status);
            }
            rows.add(new Row(row.row(), seats, row.aisleAfter()));
        }
        return new SeatMap(
                showId,
                Arrays.hashCode(fingerprint.toArray()) & 0xffffffffL,
                prices(layout, prices),
                rows,
                holdTtl.toSeconds());
    }

    /** One price per layout category, in layout order. */
    public static List<Price> prices(SeatLayout layout, Map<String, Long> prices) {
        return layout.categories().stream()
                .map(c -> new Price(c.code(), c.name(), prices.get(c.code())))
                .toList();
    }
}
