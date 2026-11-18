package dev.thiru.bms.kernel.catalog;

import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.SeatLock;
import dev.thiru.bms.kernel.catalog.SeatLayout.Category;
import dev.thiru.bms.kernel.catalog.SeatLayout.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One fixed seat state's version. It imports only the kernel and the JDK, so {@link SeatMapTest}
 * can load it, with the kernel, in a second class loader: that gives a second copy of {@code
 * SeatMap.Status}, as a restart does.
 */
public final class SeatMapVersionProbe implements Supplier<Long> {

    @Override
    public Long get() {
        SeatLayout layout =
                new SeatLayout(
                        List.of(new Category("CLASSIC", "Classic"), new Category("PRIME", "Prime")),
                        List.of(
                                new Row("A", 14, "CLASSIC", List.of(4, 10), List.of(1)),
                                new Row("B", 10, "PRIME", List.of(), List.of())));
        Instant now = Instant.parse("2026-11-20T04:30:00Z");
        List<SeatLock> locks =
                List.of(
                        new SeatLock(
                                9,
                                SeatId.parse("A2"),
                                "h",
                                null,
                                SeatLock.State.HELD,
                                now.plusSeconds(60)),
                        new SeatLock(9, SeatId.parse("B1"), "h", "b", SeatLock.State.BOOKED, null));
        return SeatMap.assemble(
                        9,
                        layout,
                        Map.of("CLASSIC", 15000L, "PRIME", 25000L),
                        locks,
                        now,
                        Duration.ofMinutes(7))
                .version();
    }
}
