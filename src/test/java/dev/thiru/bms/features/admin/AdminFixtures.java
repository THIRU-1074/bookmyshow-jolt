package dev.thiru.bms.features.admin;

import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.ports.Db;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds, bookings and payments written straight into a V2 database, in the states the booking and
 * payments slices leave them, so the admin tests need neither slice. Seats are priced from the
 * show's {@code prices_json} by their layout category.
 */
final class AdminFixtures {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Duration HOLD_TTL = Duration.ofMinutes(10);

    private final Db db;
    private final Clock clock;
    private final AtomicInteger next = new AtomicInteger();

    AdminFixtures(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    static LocalDate seedDate(Db db) {
        return db.inTransaction(
                        tx ->
                                tx.queryOne(
                                        "SELECT min(starts_at) AS first FROM show",
                                        rs -> Db.instant(rs, "first")))
                .orElseThrow()
                .atZone(IST)
                .toLocalDate();
    }

    /** The first show on {@code screenId} starting on or after {@code day} days past the seed. */
    long show(long screenId, int day) {
        Instant from = seedDate(db).plusDays(day).atStartOfDay(IST).toInstant();
        return db.inTransaction(
                tx ->
                        tx.queryOne(
                                        "SELECT id FROM show WHERE screen_id = ? AND starts_at >= ?"
                                                + " ORDER BY starts_at, id LIMIT 1",
                                        rs -> rs.getLong(1),
                                        screenId,
                                        Db.millis(from))
                                .orElseThrow());
    }

    long user(String email) {
        return db.inTransaction(
                tx ->
                        tx.insert(
                                "INSERT INTO app_user (email, password_hash, name, roles,"
                                        + " created_at) VALUES (?, 'x', 'Test', 'AUDIENCE', ?)",
                                email,
                                Db.millis(clock.instant())));
    }

    /** An ACTIVE hold with HELD locks; returns its id. */
    String hold(long user, long show, String... seats) {
        return db.inTransaction(tx -> hold(tx, user, show, "ACTIVE", seats));
    }

    /** A HELD booking on a consumed hold; returns the booking id. */
    String held(long user, long show, String... seats) {
        return booking(user, show, "HELD", seats);
    }

    /** A PAYMENT_PENDING booking with an INITIATED payment. */
    String pending(long user, long show, String... seats) {
        return booking(user, show, "PAYMENT_PENDING", seats);
    }

    /** A CONFIRMED booking with a SUCCEEDED payment and BOOKED locks. */
    String confirmed(long user, long show, String... seats) {
        return booking(user, show, "CONFIRMED", seats);
    }

    private String booking(long user, long show, String status, String... seats) {
        return db.inTransaction(
                tx -> {
                    Instant now = clock.instant();
                    String hold = hold(tx, user, show, "CONSUMED", seats);
                    String id = "b-" + next.incrementAndGet();
                    long screen =
                            tx.queryOne(
                                            "SELECT screen_id FROM show WHERE id = ?",
                                            rs -> rs.getLong(1),
                                            show)
                                    .orElseThrow();
                    SeatLayout layout = AdminSql.layout(tx, screen);
                    long amount = 0;
                    for (String seat : seats) {
                        String category = layout.categoryOf(SeatId.parse(seat));
                        long price =
                                tx.queryOne(
                                                "SELECT json_extract(prices_json, ?) FROM show"
                                                        + " WHERE id = ?",
                                                rs -> rs.getLong(1),
                                                "$." + category,
                                                show)
                                        .orElseThrow();
                        amount += price;
                        if (seat.equals(seats[0])) {
                            tx.update(
                                    "INSERT INTO booking (id, code, user_id, show_id, hold_id,"
                                            + " amount, currency, status, expires_at,"
                                            + " refund_amount, checkout_count, version,"
                                            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 1,"
                                            + " 'INR', ?, ?, 0, ?, 0, ?, ?)",
                                    id,
                                    "CODE" + id,
                                    user,
                                    show,
                                    hold,
                                    status,
                                    Db.millis(now.plus(HOLD_TTL)),
                                    "HELD".equals(status) ? 0 : 1,
                                    Db.millis(now),
                                    Db.millis(now));
                        }
                        tx.update(
                                "INSERT INTO booking_seat (booking_id, seat_id, category, price)"
                                        + " VALUES (?, ?, ?, ?)",
                                id,
                                seat,
                                category,
                                price);
                    }
                    tx.update("UPDATE booking SET amount = ? WHERE id = ?", amount, id);
                    tx.update("UPDATE seat_lock SET booking_id = ? WHERE hold_id = ?", id, hold);
                    if (!"HELD".equals(status)) {
                        String payment = "p-" + next.incrementAndGet();
                        tx.update(
                                "INSERT INTO payment (id, booking_id, amount, currency, status,"
                                        + " gateway_ref, refunded_amount, created_at, updated_at)"
                                        + " VALUES (?, ?, ?, 'INR', ?, ?, 0, ?, ?)",
                                payment,
                                id,
                                amount,
                                "CONFIRMED".equals(status) ? "SUCCEEDED" : "INITIATED",
                                "gw_" + payment,
                                Db.millis(now),
                                Db.millis(now));
                    }
                    if ("CONFIRMED".equals(status)) {
                        tx.update(
                                "UPDATE seat_lock SET state = 'BOOKED', expires_at = NULL"
                                        + " WHERE hold_id = ?",
                                hold);
                    }
                    return id;
                });
    }

    private String hold(Db.Tx tx, long user, long show, String status, String... seats) {
        Instant now = clock.instant();
        String id = "h-" + next.incrementAndGet();
        tx.update(
                "INSERT INTO seat_hold (id, show_id, user_id, seats_json, created_at, expires_at,"
                        + " status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id,
                show,
                user,
                "[\"" + String.join("\",\"", seats) + "\"]",
                Db.millis(now),
                Db.millis(now.plus(HOLD_TTL)),
                status);
        for (String seat : seats) {
            tx.update(
                    "INSERT INTO seat_lock (show_id, seat_id, hold_id, booking_id, state,"
                            + " expires_at) VALUES (?, ?, ?, NULL, 'HELD', ?)",
                    show,
                    seat,
                    id,
                    Db.millis(now.plus(HOLD_TTL)));
        }
        return id;
    }

    // ---- reads ----

    String status(String table, Object id) {
        return db.inTransaction(
                tx ->
                        tx.queryOne(
                                        "SELECT status FROM " + table + " WHERE id = ?",
                                        rs -> rs.getString(1),
                                        id)
                                .orElseThrow());
    }

    /** {@code status:refunded_amount:gateway_ref} of the booking's payments, oldest first. */
    List<String> payments(String bookingId) {
        return db.inTransaction(
                tx ->
                        tx.query(
                                "SELECT status, refunded_amount, gateway_ref FROM payment"
                                        + " WHERE booking_id = ? ORDER BY rowid",
                                rs -> rs.getString(1) + ":" + rs.getLong(2) + ":" + rs.getString(3),
                                bookingId));
    }

    long amount(String bookingId) {
        return db.inTransaction(
                tx ->
                        tx.queryOne(
                                        "SELECT amount FROM booking WHERE id = ?",
                                        rs -> rs.getLong(1),
                                        bookingId)
                                .orElseThrow());
    }

    String refundAndVersion(String bookingId) {
        return db.inTransaction(
                tx ->
                        tx.queryOne(
                                        "SELECT refund_amount || ':' || version FROM booking"
                                                + " WHERE id = ?",
                                        rs -> rs.getString(1),
                                        bookingId)
                                .orElseThrow());
    }

    int count(String sql, Object... args) {
        return db.inTransaction(tx -> tx.queryOne(sql, rs -> rs.getInt(1), args).orElseThrow());
    }

    void link(long userId, long cinemaId) {
        db.inTransaction(
                tx ->
                        tx.update(
                                "INSERT INTO cinema_owner (cinema_id, user_id) VALUES (?, ?)",
                                cinemaId,
                                userId));
    }
}
