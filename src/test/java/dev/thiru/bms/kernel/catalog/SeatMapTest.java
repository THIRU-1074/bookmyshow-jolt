package dev.thiru.bms.kernel.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.SeatLock;
import dev.thiru.bms.kernel.catalog.SeatMap.Price;
import dev.thiru.bms.kernel.catalog.SeatMap.Status;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
    void everySingleSeatChangeChangesTheVersion() {
        long before = map(List.of()).version();
        for (SeatLayout.Row row : SeatLayoutTest.sample().rows()) {
            for (int n = 1; n <= row.seats(); n++) {
                String seat = row.row() + n;
                if (SeatLayoutTest.sample().isBlocked(SeatId.parse(seat))) {
                    continue;
                }
                long held = map(List.of(held(seat, NOW.plusSeconds(60)))).version();
                long booked = map(List.of(booked(seat))).version();
                assertNotEquals(before, held, seat + " held");
                assertNotEquals(before, booked, seat + " booked");
                assertNotEquals(held, booked, seat + " held then booked");
            }
        }
    }

    /**
     * The version, and the seat map ETag built from it, must survive a restart: it hashes each
     * status by name, never by the enum constant's identity hash.
     */
    @Test
    void theVersionIsTheSameAcrossRestarts() throws Exception {
        long here = new SeatMapVersionProbe().get();
        assertEquals(here, versionInAFreshClassLoader(), "a second load of the kernel");
        // Pinned: a fresh JVM must compute exactly this for the probe's seat state.
        assertEquals(PROBE_VERSION, here);
    }

    static final long PROBE_VERSION = 1_434_321_867L;

    @SuppressWarnings("unchecked")
    private static long versionInAFreshClassLoader() throws Exception {
        URL kernel = SeatMap.class.getProtectionDomain().getCodeSource().getLocation();
        URL probe = SeatMapVersionProbe.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {kernel, probe}, ClassLoader.getPlatformClassLoader())) {
            Class<?> status = loader.loadClass(Status.class.getName());
            assertNotEquals(Status.class, status, "the kernel was loaded again");
            Class<?> cls = loader.loadClass(SeatMapVersionProbe.class.getName());
            return ((Supplier<Long>) cls.getDeclaredConstructor().newInstance()).get();
        }
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
