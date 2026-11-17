package dev.thiru.bms.kernel.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.SeatLock;
import dev.thiru.bms.kernel.catalog.SeatMap.Price;
import dev.thiru.bms.kernel.catalog.SeatMap.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SeatMapTest {

    static final Instant NOW = Instant.parse("2026-11-20T04:30:00Z");
    static final Map<String, Long> PRICES = Map.of("CLASSIC", 15000L, "PRIME", 25000L);
    static final Duration TTL = Duration.ofMinutes(7);

    private static SeatMap map(List<SeatLock> locks) {
        return SeatMap.assemble(9, SeatLayoutTest.sample(), PRICES, locks, NOW, TTL);
    }

    private static SeatLock held(String seat, Instant expires) {
        return new SeatLock(9, SeatId.parse(seat), "h", null, SeatLock.State.HELD, expires);
    }

    private static SeatLock booked(String seat) {
        return new SeatLock(9, SeatId.parse(seat), "h", "b", SeatLock.State.BOOKED, null);
    }

    private static Status status(SeatMap map, String seat) {
        SeatId id = SeatId.parse(seat);
        return map.rows().stream()
                .flatMap(r -> r.seats().stream())
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow()
                .status();
    }

    @Test
    void rowsFollowTheLayoutWithCategoriesAislesAndBlockedSeats() {
        SeatMap m = map(List.of());
        assertEquals(9, m.showId());
        assertEquals(420, m.holdTtlSeconds());
        assertEquals(
                List.of(new Price("CLASSIC", "Classic", 15000), new Price("PRIME", "Prime", 25000)),
                m.categories());
        assertEquals(2, m.rows().size());
        assertEquals("A", m.rows().get(0).row());
        assertEquals(14, m.rows().get(0).seats().size());
        assertEquals(List.of(4, 10), m.rows().get(0).aisleAfter());
        assertEquals(List.of(), m.rows().get(1).aisleAfter());
        assertEquals(1, m.rows().get(0).seats().get(0).number());
        assertEquals("PRIME", m.rows().get(1).seats().get(0).category());
        assertEquals(Status.BLOCKED, status(m, "A1"));
        assertEquals(Status.AVAILABLE, status(m, "A2"));
    }

    @Test
    void liveLocksAreHeldOrBookedAndExpiredHoldsAreAvailable() {
        SeatMap m =
                map(
                        List.of(
                                held("A2", NOW.plusSeconds(60)),
                                held("A3", NOW),
                                held("A4", NOW.minusSeconds(1)),
                                booked("B1")));
        assertEquals(Status.HELD, status(m, "A2"));
        assertEquals(Status.AVAILABLE, status(m, "A3"), "a hold ends at its expiry instant");
        assertEquals(Status.AVAILABLE, status(m, "A4"));
        assertEquals(Status.BOOKED, status(m, "B1"));
    }

    @Test
    void aLockOnABlockedSeatStillShowsBlocked() {
        assertEquals(Status.BLOCKED, status(map(List.of(booked("A1"))), "A1"));
    }

    @Test
    void versionChangesExactlyWhenAStatusDoes() {
        SeatMap before = map(List.of());
        SeatMap held = map(List.of(held("A2", NOW.plusSeconds(60))));
        assertNotEquals(before.version(), held.version());
        assertEquals(held.version(), map(List.of(held("A2", NOW.plusSeconds(90)))).version());
        assertEquals(before.version(), map(List.of(held("A2", NOW))).version());
        assertEquals(before.version() & 0xffffffffL, before.version(), "unsigned 32 bits");
    }

    @Test
    void pricesFollowTheLayoutCategoryOrder() {
        assertEquals(
                List.of("CLASSIC", "PRIME"),
                SeatMap.prices(SeatLayoutTest.sample(), PRICES).stream()
                        .map(Price::category)
                        .toList());
    }
}
