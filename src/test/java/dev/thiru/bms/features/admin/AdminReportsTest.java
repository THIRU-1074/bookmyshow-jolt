package dev.thiru.bms.features.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.account.CinemaScope;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.MutableClock;
import dev.thiru.bms.testing.RecordingEvents;
import dev.thiru.bms.testing.RecordingGateway;
import dev.thiru.bms.testing.TestDb;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reports on the V2 seed for a show on screen 1 (Audi 1, cinema 1, LARGE: 198 bookable seats, 119
 * CLASSIC and 79 PRIME).
 */
class AdminReportsTest {

    static final CinemaScope ALL = CinemaScope.everything();

    @TempDir Path dir;
    TestDb test;
    Db db;
    MutableClock clock;
    AdminFixtures f;
    AdminReports reports;
    long show;
    String confirmed;

    @BeforeEach
    void open() {
        test = TestDb.open(dir);
        db = test.db();
        clock =
                new MutableClock(
                        AdminFixtures.seedDate(db)
                                .atTime(LocalTime.of(8, 0))
                                .atZone(AdminFixtures.IST)
                                .toInstant());
        f = new AdminFixtures(db, clock);
        reports = new AdminReports(db, clock);
        show = f.show(1, 1);
        confirmed = f.confirmed(f.user("a@x.in"), show, "A2", "G1");
        f.held(f.user("b@x.in"), show, "A5");
        f.hold(f.user("c@x.in"), show, "B1");
    }

    @AfterEach
    void close() {
        test.close();
    }

    long price(String category) {
        return db.inTransaction(
                tx ->
                        tx.queryOne(
                                        "SELECT json_extract(prices_json, ?) FROM show WHERE id = ?",
                                        rs -> rs.getLong(1),
                                        "$." + category,
                                        show)
                                .orElseThrow());
    }

    @Test
    void aReportCountsConfirmedSeatsRevenueAndOccupancyPerCategory() {
        AdminReports.ShowReportDetail d = reports.show(ALL, show);
        assertEquals(show, d.show().showId());
        assertEquals(ShowStatus.SCHEDULED, d.show().status());
        assertEquals("Koramangala Cineplex", d.show().cinemaName());
        assertEquals(198, d.show().capacity());
        assertEquals(2, d.show().booked());
        assertEquals(1.0, d.show().occupancyPercent());
        assertEquals(price("CLASSIC") + price("PRIME"), d.show().revenue());
        assertEquals(0, d.show().refunded());
        assertEquals(
                List.of(
                        new AdminReports.CategoryStat("CLASSIC", 119, 1, price("CLASSIC")),
                        new AdminReports.CategoryStat("PRIME", 79, 1, price("PRIME"))),
                d.byCategory());
        assertEquals(
                "Show not found",
                assertThrows(DomainException.class, () -> reports.show(ALL, 999_999)).getMessage());
    }

    @Test
    void refundsReduceRevenue() {
        new CancelShow(db, new RecordingGateway(), new RecordingEvents(), clock).execute(ALL, show);
        AdminReports.ShowReport r = reports.show(ALL, show).show();
        assertEquals(ShowStatus.CANCELLED, r.status());
        assertEquals(0, r.booked());
        assertEquals(f.amount(confirmed), r.refunded());
        assertEquals(0, r.revenue());
    }

    @Test
    void theListIsFilteredByCityWindowAndOwnerAndPaged() {
        Instant from = clock.instant().plus(Duration.ofDays(1));
        Instant to = from.plus(Duration.ofDays(1));
        var day = reports.shows(ALL, "bengaluru", from, to, 1, 5);
        long bengaluruShows =
                f.count(
                        "SELECT count(*) FROM show s JOIN screen sc ON sc.id = s.screen_id"
                                + " JOIN cinema c ON c.id = sc.cinema_id WHERE c.city_id ="
                                + " 'bengaluru' AND s.starts_at >= ? AND s.starts_at < ?",
                        Db.millis(from),
                        Db.millis(to));
        assertEquals(bengaluruShows, day.total());
        assertEquals(5, day.items().size());
        List<Instant> starts = day.items().stream().map(AdminReports.ShowReport::startsAt).toList();
        assertEquals(starts.stream().sorted().toList(), starts);
        assertEquals(0, reports.shows(ALL, "nowhere", from, to, 1, 5).total());

        long owner = f.user("o@x.in");
        f.link(owner, 2);
        var mine = reports.shows(CinemaScope.ownedBy(owner), null, from, to, 1, 100);
        assertEquals(
                List.of("Whitefield Galaxy"),
                mine.items().stream().map(AdminReports.ShowReport::cinemaName).distinct().toList());
        assertEquals(
                0,
                reports.shows(
                                CinemaScope.ownedBy(999),
                                null,
                                Instant.EPOCH,
                                AdminRoutes.UNBOUNDED_END,
                                1,
                                5)
                        .total());
        assertEquals(
                "Show not found",
                assertThrows(
                                DomainException.class,
                                () -> reports.show(CinemaScope.ownedBy(owner), show))
                        .getMessage());
        f.link(owner, 1);
        assertEquals(2, reports.show(CinemaScope.ownedBy(owner), show).show().booked());
    }

    @Test
    void statsCountActiveHoldsAndBookingsConfirmedTodayInUtc() {
        assertEquals(new AdminReports.Stats(1, 1), reports.stats());
        clock.advance(Duration.ofMinutes(11));
        assertEquals(0, reports.stats().activeHolds(), "an expired hold is not active");
        clock.advance(Duration.ofDays(1));
        assertEquals(0, reports.stats().bookingsConfirmedToday());
    }
}
