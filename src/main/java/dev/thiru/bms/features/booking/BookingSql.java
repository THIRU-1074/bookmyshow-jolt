package dev.thiru.bms.features.booking;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingSeat;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.booking.HoldStatus;
import dev.thiru.bms.kernel.booking.SeatHold;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.Db.Tx;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The booking slice's SQL: holds, seat locks, bookings, payments and the show reads that price a
 * hold. Every method runs inside the caller's transaction. The {@code seat_lock} primary key {@code
 * (show_id, seat_id)} is the only real guard against double booking (SPEC 5.1); the conflict query
 * exists to name the seats in the error, not to decide who wins.
 *
 * <p>JSON columns are read without a JSON library: {@code layout_json} and {@code prices_json} with
 * SQLite's JSON functions, {@code seats_json} (a flat array of seat ids this slice writes) by hand.
 */
final class BookingSql {

    private BookingSql() {}

    /** The show as a booking sees it: where and what, in the city's local time. */
    record ShowInfo(
            long id,
            String movieTitle,
            String cinemaName,
            String screenName,
            Instant startsAt,
            LocalDate localDate,
            LocalTime localTime) {}

    // ---- shows ----

    static Optional<Show> show(Tx tx, long showId) {
        Optional<Show> bare =
                tx.queryOne(
                        "SELECT id, movie_id, screen_id, starts_at, ends_at, language, format,"
                                + " status FROM show WHERE id = ?",
                        rs ->
                                new Show(
                                        rs.getLong("id"),
                                        rs.getLong("movie_id"),
                                        rs.getLong("screen_id"),
                                        Db.instant(rs, "starts_at"),
                                        Db.instant(rs, "ends_at"),
                                        rs.getString("language"),
                                        ShowFormat.parse(rs.getString("format")),
                                        Map.of(),
                                        ShowStatus.valueOf(rs.getString("status"))),
                        showId);
        return bare.map(
                s ->
                        new Show(
                                s.id(),
                                s.movieId(),
                                s.screenId(),
                                s.startsAt(),
                                s.endsAt(),
                                s.language(),
                                s.format(),
                                prices(tx, s.id()),
                                s.status()));
    }

    private static Map<String, Long> prices(Tx tx, long showId) {
        Map<String, Long> prices = new HashMap<>();
        tx.query(
                "SELECT p.key, p.value FROM show s, json_each(s.prices_json) p WHERE s.id = ?",
                rs -> prices.put(rs.getString(1), rs.getLong(2)),
                showId);
        return prices;
    }

    static Screen screen(Tx tx, long screenId) {
        return tx.queryOne(
                        "SELECT id, cinema_id, name FROM screen WHERE id = ?",
                        rs ->
                                new Screen(
                                        rs.getLong("id"),
                                        rs.getLong("cinema_id"),
                                        rs.getString("name"),
                                        layout(tx, screenId)),
                        screenId)
                .orElseThrow();
    }

    /** The screen's {@code layout_json}, read element by element with SQLite's JSON functions. */
    private static SeatLayout layout(Tx tx, long screenId) {
        List<SeatLayout.Category> categories =
                tx.query(
                        "SELECT json_extract(c.value, '$.code') AS code,"
                                + " json_extract(c.value, '$.name') AS name"
                                + " FROM screen sc, json_each(sc.layout_json, '$.categories') c"
                                + " WHERE sc.id = ? ORDER BY c.key",
                        rs -> new SeatLayout.Category(rs.getString("code"), rs.getString("name")),
                        screenId);
        List<SeatLayout.Row> rows =
                tx.query(
                        "SELECT json_extract(r.value, '$.row') AS row,"
                                + " json_extract(r.value, '$.seats') AS seats,"
                                + " json_extract(r.value, '$.category') AS category,"
                                + " json_extract(r.value, '$.aisleAfter') AS aisle_after,"
                                + " json_extract(r.value, '$.blocked') AS blocked"
                                + " FROM screen sc, json_each(sc.layout_json, '$.rows') r"
                                + " WHERE sc.id = ? ORDER BY r.key",
                        rs ->
                                new SeatLayout.Row(
                                        rs.getString("row"),
                                        rs.getInt("seats"),
                                        rs.getString("category"),
                                        ints(rs.getString("aisle_after")),
                                        ints(rs.getString("blocked"))),
                        screenId);
        return new SeatLayout(categories, rows);
    }

    /** A JSON array of integers as SQLite renders it, such as {@code [4,12]}. */
    private static List<Integer> ints(String json) {
        List<Integer> out = new ArrayList<>();
        for (String n : elements(json)) {
            out.add(Integer.parseInt(n));
        }
        return out;
    }

    static ShowInfo showInfo(Tx tx, long showId) {
        return tx.queryOne(
                        "SELECT s.id, s.starts_at, m.title, c.name AS cinema, sc.name AS screen,"
                                + " ci.time_zone FROM show s JOIN screen sc ON sc.id = s.screen_id"
                                + " JOIN cinema c ON c.id = sc.cinema_id"
                                + " JOIN city ci ON ci.id = c.city_id"
                                + " JOIN movie m ON m.id = s.movie_id WHERE s.id = ?",
                        rs -> {
                            Instant startsAt = Db.instant(rs, "starts_at");
                            var local = startsAt.atZone(ZoneId.of(rs.getString("time_zone")));
                            return new ShowInfo(
                                    rs.getLong("id"),
                                    rs.getString("title"),
                                    rs.getString("cinema"),
                                    rs.getString("screen"),
                                    startsAt,
                                    local.toLocalDate(),
                                    local.toLocalTime());
                        },
                        showId)
                .orElseThrow();
    }

    // ---- holds ----

    private static final String HOLD_COLS =
            "id, show_id, user_id, seats_json, created_at, expires_at, status";

    private static final Db.Row<SeatHold> HOLD =
            rs ->
                    new SeatHold(
                            rs.getString("id"),
                            rs.getLong("show_id"),
                            rs.getLong("user_id"),
                            elements(rs.getString("seats_json")).stream()
                                    .map(SeatId::parse)
                                    .toList(),
                            Db.instant(rs, "created_at"),
                            Db.instant(rs, "expires_at"),
                            HoldStatus.valueOf(rs.getString("status")));

    static void insertHold(Tx tx, SeatHold h) {
        tx.update(
                "INSERT INTO seat_hold (" + HOLD_COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                h.id(),
                h.showId(),
                h.userId(),
                seatsJson(h.seats()),
                Db.millis(h.createdAt()),
                Db.millis(h.expiresAt()),
                h.status().name());
    }

    static void updateHold(Tx tx, SeatHold h) {
        int n =
                tx.update(
                        "UPDATE seat_hold SET expires_at = ?, status = ? WHERE id = ?",
                        Db.millis(h.expiresAt()),
                        h.status().name(),
                        h.id());
        if (n != 1) {
            throw DomainException.notFound("Hold");
        }
    }

    static Optional<SeatHold> hold(Tx tx, String id) {
        return tx.queryOne("SELECT " + HOLD_COLS + " FROM seat_hold WHERE id = ?", HOLD, id);
    }

    static Optional<SeatHold> activeHold(Tx tx, long userId, long showId) {
        return tx.queryOne(
                "SELECT "
                        + HOLD_COLS
                        + " FROM seat_hold WHERE user_id = ? AND show_id = ?"
                        + " AND status = 'ACTIVE' ORDER BY created_at DESC LIMIT 1",
                HOLD,
                userId,
                showId);
    }

    static List<SeatHold> expiredActiveHolds(Tx tx, Instant now, int limit) {
        return tx.query(
                "SELECT "
                        + HOLD_COLS
                        + " FROM seat_hold WHERE status = 'ACTIVE' AND expires_at <= ?"
                        + " ORDER BY expires_at, id LIMIT ?",
                HOLD,
                Db.millis(now),
                limit);
    }

    /** As the legacy writer stores it: a compact JSON array of seat ids. */
    static String seatsJson(List<SeatId> seats) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < seats.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(seats.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * The elements of a flat JSON array of numbers or of strings without commas or quotes inside,
     * such as {@code ["A2","B1"]} or {@code [4,12]}; null is empty.
     */
    static List<String> elements(String json) {
        if (json == null) {
            return List.of();
        }
        String body = json.strip();
        body = body.substring(1, body.length() - 1).strip();
        List<String> out = new ArrayList<>();
        if (body.isEmpty()) {
            return out;
        }
        for (String part : body.split(",")) {
            String e = part.strip();
            if (e.startsWith("\"")) {
                e = e.substring(1, e.length() - 1);
            }
            out.add(e);
        }
        return out;
    }

    // ---- seat locks ----

    /** Locks every seat for the hold or throws SEAT_UNAVAILABLE naming the taken seats. */
    static void lock(Tx tx, long showId, String holdId, List<SeatId> seats, Instant expiresAt) {
        // Under BEGIN IMMEDIATE nobody can slip in between this check and the inserts, so the
        // friendly path is exact; the primary key below still backs it up.
        List<SeatId> taken = conflicts(tx, showId, holdId, seats);
        if (!taken.isEmpty()) {
            throw DomainException.seatUnavailable(taken);
        }
        try {
            tx.batch(
                    "INSERT INTO seat_lock (show_id, seat_id, hold_id, booking_id, state,"
                            + " expires_at) VALUES (?, ?, ?, NULL, 'HELD', ?)",
                    seats.stream()
                            .map(
                                    seat ->
                                            new Object[] {
                                                showId,
                                                seat.toString(),
                                                holdId,
                                                Db.millis(expiresAt)
                                            })
                            .toList());
        } catch (Db.ConstraintViolation e) {
            List<SeatId> clash = conflicts(tx, showId, holdId, seats);
            if (!clash.isEmpty()) {
                throw DomainException.seatUnavailable(clash);
            }
            throw e;
        }
    }

    /** Requested seats that already have a row belonging to some other hold. */
    private static List<SeatId> conflicts(Tx tx, long showId, String holdId, List<SeatId> seats) {
        if (seats.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(showId);
        params.add(holdId);
        seats.forEach(s -> params.add(s.toString()));
        return tx.query(
                "SELECT seat_id FROM seat_lock WHERE show_id = ? AND hold_id <> ?"
                        + " AND seat_id IN ("
                        + Db.marks(seats.size())
                        + ")",
                rs -> SeatId.parse(rs.getString(1)),
                params.toArray());
    }

    /** Deletes dead HELD locks on {@code seats}; returns the freed seats, sorted. */
    static List<SeatId> deleteExpiredHeld(
            Tx tx, long showId, Collection<SeatId> seats, Instant now) {
        if (seats.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(showId);
        params.add(Db.millis(now));
        seats.forEach(s -> params.add(s.toString()));
        String where =
                " FROM seat_lock WHERE show_id = ? AND state = 'HELD' AND expires_at <= ?"
                        + " AND seat_id IN ("
                        + Db.marks(seats.size())
                        + ")";
        List<SeatId> freed =
                tx.query(
                        "SELECT seat_id" + where,
                        rs -> SeatId.parse(rs.getString(1)),
                        params.toArray());
        if (!freed.isEmpty()) {
            tx.update("DELETE" + where, params.toArray());
        }
        return freed.stream().sorted().toList();
    }

    /** Deletes the hold's locks; returns the freed seats, sorted. */
    static List<SeatId> deleteLocks(Tx tx, String holdId) {
        List<SeatId> freed =
                tx.query(
                        "SELECT seat_id FROM seat_lock WHERE hold_id = ?",
                        rs -> SeatId.parse(rs.getString(1)),
                        holdId);
        if (!freed.isEmpty()) {
            tx.update("DELETE FROM seat_lock WHERE hold_id = ?", holdId);
        }
        return freed.stream().sorted().toList();
    }

    static void attachBooking(Tx tx, String holdId, String bookingId) {
        tx.update("UPDATE seat_lock SET booking_id = ? WHERE hold_id = ?", bookingId, holdId);
    }

    static void extendLocks(Tx tx, String holdId, Instant expiresAt) {
        tx.update(
                "UPDATE seat_lock SET expires_at = ? WHERE hold_id = ? AND state = 'HELD'",
                Db.millis(expiresAt),
                holdId);
    }

    // ---- bookings ----

    private static final String BOOKING_COLS =
            "id, code, user_id, show_id, hold_id, amount, currency, status, expires_at,"
                    + " refund_amount, checkout_count, version, created_at, updated_at";

    /** Rows without their seats; seats come from one batched query per result set. */
    private static final Db.Row<Booking> BOOKING =
            rs ->
                    new Booking(
                            rs.getString("id"),
                            rs.getString("code"),
                            rs.getLong("user_id"),
                            rs.getLong("show_id"),
                            rs.getString("hold_id"),
                            List.of(),
                            rs.getLong("amount"),
                            rs.getString("currency"),
                            BookingStatus.valueOf(rs.getString("status")),
                            Db.instant(rs, "expires_at"),
                            rs.getLong("refund_amount"),
                            rs.getInt("checkout_count"),
                            rs.getLong("version"),
                            Db.instant(rs, "created_at"),
                            Db.instant(rs, "updated_at"));

    static void insertBooking(Tx tx, Booking b) {
        tx.update(
                "INSERT INTO booking ("
                        + BOOKING_COLS
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                b.id(),
                b.code(),
                b.userId(),
                b.showId(),
                b.holdId(),
                b.amount(),
                b.currency(),
                b.status().name(),
                Db.millis(b.expiresAt()),
                b.refundAmount(),
                b.checkoutCount(),
                b.version(),
                Db.millis(b.createdAt()),
                Db.millis(b.updatedAt()));
        tx.batch(
                "INSERT INTO booking_seat (booking_id, seat_id, category, price)"
                        + " VALUES (?, ?, ?, ?)",
                b.seats().stream()
                        .map(
                                s ->
                                        new Object[] {
                                            b.id(), s.seat().toString(), s.category(), s.price()
                                        })
                        .toList());
    }

    /** Writes a transition if nobody moved the row since it was read; VERSION_CONFLICT if not. */
    static Booking updateBooking(Tx tx, Booking b) {
        int n =
                tx.update(
                        "UPDATE booking SET status = ?, expires_at = ?, refund_amount = ?,"
                                + " checkout_count = ?, updated_at = ?, version = version + 1"
                                + " WHERE id = ? AND version = ?",
                        b.status().name(),
                        Db.millis(b.expiresAt()),
                        b.refundAmount(),
                        b.checkoutCount(),
                        Db.millis(b.updatedAt()),
                        b.id(),
                        b.version());
        if (n != 1) {
            throw DomainException.versionConflict();
        }
        return b.withVersion(b.version() + 1);
    }

    static Optional<Booking> booking(Tx tx, String id) {
        return withSeats(
                        tx,
                        tx.query(
                                "SELECT " + BOOKING_COLS + " FROM booking WHERE id = ?",
                                BOOKING,
                                id))
                .stream()
                .findFirst();
    }

    static boolean codeExists(Tx tx, String code) {
        return tx.queryOne("SELECT 1 FROM booking WHERE code = ?", rs -> 1, code).isPresent();
    }

    /** The user's bookings, newest first. */
    static Page<Booking> page(Tx tx, long userId, Set<BookingStatus> statuses, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE user_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (!statuses.isEmpty()) {
            where.append(" AND status IN (").append(Db.marks(statuses.size())).append(')');
            statuses.forEach(s -> params.add(s.name()));
        }
        long total =
                tx.queryOne(
                                "SELECT count(*) FROM booking" + where,
                                rs -> rs.getLong(1),
                                params.toArray())
                        .orElse(0L);
        params.add(size);
        params.add((long) Math.max(page - 1, 0) * size);
        List<Booking> items =
                tx.query(
                        "SELECT "
                                + BOOKING_COLS
                                + " FROM booking"
                                + where
                                + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                        BOOKING,
                        params.toArray());
        return new Page<>(withSeats(tx, items), page, size, total);
    }

    static List<Booking> openExpiredBookings(Tx tx, Instant now, int limit) {
        return withSeats(
                tx,
                tx.query(
                        "SELECT "
                                + BOOKING_COLS
                                + " FROM booking WHERE status IN ('HELD', 'PAYMENT_PENDING')"
                                + " AND expires_at <= ? ORDER BY expires_at, id LIMIT ?",
                        BOOKING,
                        Db.millis(now),
                        limit));
    }

    private static List<Booking> withSeats(Tx tx, List<Booking> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Object[] ids = rows.stream().map(Booking::id).toArray();
        Map<String, List<BookingSeat>> seats = new LinkedHashMap<>();
        // rowid order is insertion order, which keeps the seats as the buyer chose them.
        tx.query(
                "SELECT booking_id, seat_id, category, price FROM booking_seat"
                        + " WHERE booking_id IN ("
                        + Db.marks(ids.length)
                        + ") ORDER BY rowid",
                rs ->
                        seats.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                                .add(
                                        new BookingSeat(
                                                SeatId.parse(rs.getString(2)),
                                                rs.getString(3),
                                                rs.getLong(4))),
                ids);
        return rows.stream()
                .map(
                        b ->
                                new Booking(
                                        b.id(),
                                        b.code(),
                                        b.userId(),
                                        b.showId(),
                                        b.holdId(),
                                        seats.getOrDefault(b.id(), List.of()),
                                        b.amount(),
                                        b.currency(),
                                        b.status(),
                                        b.expiresAt(),
                                        b.refundAmount(),
                                        b.checkoutCount(),
                                        b.version(),
                                        b.createdAt(),
                                        b.updatedAt()))
                .toList();
    }

    // ---- payments ----

    private static final String PAYMENT_COLS =
            "id, booking_id, amount, currency, status, gateway_ref, refunded_amount, created_at,"
                    + " updated_at";

    static void insertPayment(Tx tx, Payment p) {
        tx.update(
                "INSERT INTO payment (" + PAYMENT_COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.id(),
                p.bookingId(),
                p.amount(),
                p.currency(),
                p.status().name(),
                p.gatewayRef(),
                p.refundedAmount(),
                Db.millis(p.createdAt()),
                Db.millis(p.updatedAt()));
    }

    static void updatePayment(Tx tx, Payment p) {
        int n =
                tx.update(
                        "UPDATE payment SET status = ?, gateway_ref = ?, refunded_amount = ?,"
                                + " updated_at = ? WHERE id = ?",
                        p.status().name(),
                        p.gatewayRef(),
                        p.refundedAmount(),
                        Db.millis(p.updatedAt()),
                        p.id());
        if (n != 1) {
            throw DomainException.notFound("Payment");
        }
    }

    static Optional<Payment> latestPayment(Tx tx, String bookingId) {
        return tx.queryOne(
                "SELECT "
                        + PAYMENT_COLS
                        + " FROM payment WHERE booking_id = ?"
                        + " ORDER BY created_at DESC, rowid DESC LIMIT 1",
                rs ->
                        new Payment(
                                rs.getString("id"),
                                rs.getString("booking_id"),
                                rs.getLong("amount"),
                                rs.getString("currency"),
                                PaymentStatus.valueOf(rs.getString("status")),
                                rs.getString("gateway_ref"),
                                rs.getLong("refunded_amount"),
                                Db.instant(rs, "created_at"),
                                Db.instant(rs, "updated_at")),
                bookingId);
    }
}
