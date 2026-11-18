package dev.thiru.bms.features.admin;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.account.CinemaScope;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import dev.thiru.bms.ports.Db;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Sales and occupancy reports (admin, or a theatre owner for their cinemas) and counters. */
final class AdminReports {

    /** {@code revenue} is what was paid minus what was refunded, in paise. */
    record ShowReport(
            long showId,
            String movieTitle,
            String cinemaName,
            Instant startsAt,
            ShowStatus status,
            int capacity,
            int booked,
            double occupancyPercent,
            long revenue,
            long refunded) {}

    record CategoryStat(String category, int capacity, int booked, long revenue) {}

    record ShowReportDetail(ShowReport show, List<CategoryStat> byCategory) {}

    record Stats(long activeHolds, long bookingsConfirmedToday) {}

    private final Db db;
    private final Clock clock;

    AdminReports(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /**
     * Booked seats count CONFIRMED bookings; paid and refunded sum payments that succeeded,
     * including those refunded later.
     */
    private static final String STAT =
            "SELECT s.id, m.title, c.name AS cinema, s.starts_at, s.status, sc.capacity,"
                    + " (SELECT count(*) FROM booking_seat bs JOIN booking b"
                    + " ON b.id = bs.booking_id"
                    + " WHERE b.show_id = s.id AND b.status = 'CONFIRMED') AS booked,"
                    + " (SELECT coalesce(sum(p.amount), 0) FROM payment p JOIN booking b"
                    + " ON b.id = p.booking_id WHERE b.show_id = s.id"
                    + " AND p.status IN ('SUCCEEDED', 'REFUNDED')) AS paid,"
                    + " (SELECT coalesce(sum(p.refunded_amount), 0) FROM payment p"
                    + " JOIN booking b ON b.id = p.booking_id WHERE b.show_id = s.id"
                    + " AND p.status IN ('SUCCEEDED', 'REFUNDED')) AS refunded"
                    + " FROM show s JOIN movie m ON m.id = s.movie_id"
                    + " JOIN screen sc ON sc.id = s.screen_id"
                    + " JOIN cinema c ON c.id = sc.cinema_id";

    /** City (all when null), owner links (all when null) and the [from, to) start window. */
    private static final String WINDOW =
            " WHERE (?1 IS NULL OR c.city_id = ?1)"
                    + " AND (?2 IS NULL OR c.id IN"
                    + " (SELECT cinema_id FROM cinema_owner WHERE user_id = ?2))"
                    + " AND s.starts_at >= ?3 AND s.starts_at < ?4";

    /** One decimal place of occupancy, computed in integers. */
    private static final Db.Row<ShowReport> ROW =
            rs -> {
                int capacity = rs.getInt("capacity");
                int booked = rs.getInt("booked");
                long paid = rs.getLong("paid");
                long refunded = rs.getLong("refunded");
                double occupancy =
                        capacity == 0 ? 0 : Math.round(booked * 1000.0 / capacity) / 10.0;
                return new ShowReport(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("cinema"),
                        Db.instant(rs, "starts_at"),
                        ShowStatus.valueOf(rs.getString("status")),
                        capacity,
                        booked,
                        occupancy,
                        paid - refunded,
                        refunded);
            };

    /** Shows of the city (all cities when null) starting in [from, to), by start time. */
    Page<ShowReport> shows(
            CinemaScope scope, String cityId, Instant from, Instant to, int page, int size) {
        return db.inTransaction(
                tx -> {
                    long total =
                            tx.queryOne(
                                            "SELECT count(*) FROM show s"
                                                    + " JOIN screen sc ON sc.id = s.screen_id"
                                                    + " JOIN cinema c ON c.id = sc.cinema_id"
                                                    + WINDOW,
                                            rs -> rs.getLong(1),
                                            cityId,
                                            scope.ownerId(),
                                            Db.millis(from),
                                            Db.millis(to))
                                    .orElse(0L);
                    List<ShowReport> items =
                            tx.query(
                                    STAT
                                            + WINDOW
                                            + " ORDER BY s.starts_at, s.id LIMIT ?5 OFFSET ?6",
                                    ROW,
                                    cityId,
                                    scope.ownerId(),
                                    Db.millis(from),
                                    Db.millis(to),
                                    size,
                                    (long) Math.max(page - 1, 0) * size);
                    return new Page<>(items, page, size, total);
                });
    }

    ShowReportDetail show(CinemaScope scope, long showId) {
        return db.inTransaction(
                tx -> {
                    Show show =
                            AdminSql.show(tx, showId)
                                    .orElseThrow(() -> DomainException.notFound("Show"));
                    AdminSql.require(tx, scope, AdminSql.cinemaOfShow(tx, show), "Show");
                    ShowReport report =
                            tx.queryOne(STAT + " WHERE s.id = ?", ROW, showId)
                                    .orElseThrow(() -> DomainException.notFound("Show"));
                    return new ShowReportDetail(report, categories(tx, show));
                });
    }

    private record Sold(int booked, long revenue) {}

    /** Per layout category, in layout order: bookable seats, confirmed seats and their prices. */
    private static List<CategoryStat> categories(Db.Tx tx, Show show) {
        SeatLayout layout = AdminSql.layout(tx, show.screenId());
        Map<String, Integer> capacity = new HashMap<>();
        for (SeatId seat : layout.bookableSeats()) {
            capacity.merge(layout.categoryOf(seat), 1, Integer::sum);
        }
        Map<String, Sold> sold = new HashMap<>();
        tx.query(
                "SELECT bs.category, count(*), coalesce(sum(bs.price), 0)"
                        + " FROM booking_seat bs JOIN booking b ON b.id = bs.booking_id"
                        + " WHERE b.show_id = ? AND b.status = 'CONFIRMED'"
                        + " GROUP BY bs.category",
                rs -> sold.put(rs.getString(1), new Sold(rs.getInt(2), rs.getLong(3))),
                show.id());
        List<CategoryStat> out = new ArrayList<>();
        for (SeatLayout.Category c : layout.categories()) {
            Sold s = sold.getOrDefault(c.code(), new Sold(0, 0));
            out.add(
                    new CategoryStat(
                            c.code(), capacity.getOrDefault(c.code(), 0), s.booked(), s.revenue()));
        }
        return out;
    }

    /** "Today" is the UTC day; the spec does not name a zone for this counter. */
    Stats stats() {
        Instant now = clock.instant();
        Instant dayStart =
                now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return db.inTransaction(
                tx ->
                        new Stats(
                                count(
                                        tx,
                                        "SELECT count(*) FROM seat_hold WHERE status = 'ACTIVE'"
                                                + " AND expires_at > ?",
                                        now),
                                count(
                                        tx,
                                        "SELECT count(*) FROM booking WHERE status = 'CONFIRMED'"
                                                + " AND updated_at >= ?",
                                        dayStart)));
    }

    private static long count(Db.Tx tx, String sql, Instant at) {
        return tx.queryOne(sql, rs -> rs.getLong(1), Db.millis(at)).orElse(0L);
    }
}
