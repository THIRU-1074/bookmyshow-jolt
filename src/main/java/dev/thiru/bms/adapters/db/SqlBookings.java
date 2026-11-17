package dev.thiru.bms.adapters.db;

import dev.thiru.bms.domain.port.BookingRepositories;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingSeat;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.booking.HoldStatus;
import dev.thiru.bms.kernel.booking.SeatHold;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import in.jolt.db.OptimisticLockException;
import in.jolt.db.RowMapper;
import in.jolt.db.Sql;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Repositories of the transactional aggregates: users, holds, bookings, payments, events. */
final class SqlBookings {

    private SqlBookings() {}

    private static final String HOLD_COLS =
            "id, show_id, user_id, seats_json, created_at, expires_at, status";

    private static final RowMapper<SeatHold> HOLD =
            rs ->
                    new SeatHold(
                            rs.getString("id"),
                            rs.getLong("show_id"),
                            rs.getLong("user_id"),
                            Json.array(Json.parse(rs.getString("seats_json"))).stream()
                                    .map(o -> SeatId.parse((String) o))
                                    .toList(),
                            Sqls.instant(rs, "created_at"),
                            Sqls.instant(rs, "expires_at"),
                            HoldStatus.valueOf(rs.getString("status")));

    private static final String BOOKING_COLS =
            "id, code, user_id, show_id, hold_id, amount, currency, status, expires_at,"
                    + " refund_amount, checkout_count, version, created_at, updated_at";

    private static final String PAYMENT_COLS =
            "id, booking_id, amount, currency, status, gateway_ref, refunded_amount, created_at,"
                    + " updated_at";

    private static final RowMapper<Payment> PAYMENT =
            rs ->
                    new Payment(
                            rs.getString("id"),
                            rs.getString("booking_id"),
                            rs.getLong("amount"),
                            rs.getString("currency"),
                            PaymentStatus.valueOf(rs.getString("status")),
                            rs.getString("gateway_ref"),
                            rs.getLong("refunded_amount"),
                            Sqls.instant(rs, "created_at"),
                            Sqls.instant(rs, "updated_at"));

    static final String USER_COLS = "id, email, password_hash, name, roles, created_at";

    static final RowMapper<User> USER =
            rs ->
                    new User(
                            rs.getLong("id"),
                            rs.getString("email"),
                            rs.getString("password_hash"),
                            rs.getString("name"),
                            Arrays.stream(rs.getString("roles").split(","))
                                    .map(Role::valueOf)
                                    .collect(Collectors.toSet()),
                            Sqls.instant(rs, "created_at"));

    static final class Users implements BookingRepositories.Users {
        private final Sql sql;

        Users(Sql sql) {
            this.sql = sql;
        }

        @Override
        public Optional<User> findById(long id) {
            return sql.queryOne("SELECT " + USER_COLS + " FROM app_user WHERE id = ?", USER, id);
        }

        @Override
        public Optional<User> findByEmail(String email) {
            return sql.queryOne(
                    "SELECT " + USER_COLS + " FROM app_user WHERE email = ?",
                    USER,
                    email.toLowerCase(java.util.Locale.ROOT));
        }

        @Override
        public User insert(User user) {
            String roles =
                    user.roles().stream().map(Role::name).sorted().collect(Collectors.joining(","));
            long id;
            try {
                id =
                        sql.insert(
                                        "INSERT INTO app_user (id, email, password_hash, name,"
                                                + " roles, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                                        user.id() > 0 ? (Object) user.id() : null,
                                        user.email(),
                                        user.passwordHash(),
                                        user.name(),
                                        roles,
                                        Sqls.ms(user.createdAt()))
                                .orElseThrow();
            } catch (RuntimeException e) {
                if (Sqls.isConstraint(e)) {
                    throw DomainException.validation("email", "is already registered");
                }
                throw e;
            }
            return new User(
                    id,
                    user.email(),
                    user.passwordHash(),
                    user.name(),
                    user.roles(),
                    user.createdAt());
        }
    }

    static final class Holds implements BookingRepositories.Holds {
        private final Sql sql;

        Holds(Sql sql) {
            this.sql = sql;
        }

        @Override
        public void insert(SeatHold h) {
            sql.update(
                    "INSERT INTO seat_hold (" + HOLD_COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                    h.id(),
                    h.showId(),
                    h.userId(),
                    Json.write(h.seats().stream().map(SeatId::toString).toList()),
                    Sqls.ms(h.createdAt()),
                    Sqls.ms(h.expiresAt()),
                    h.status().name());
        }

        @Override
        public void update(SeatHold h) {
            int n =
                    sql.update(
                            "UPDATE seat_hold SET expires_at = ?, status = ? WHERE id = ?",
                            Sqls.ms(h.expiresAt()),
                            h.status().name(),
                            h.id());
            if (n != 1) {
                throw DomainException.notFound("Hold");
            }
        }

        @Override
        public Optional<SeatHold> findById(String id) {
            return sql.queryOne("SELECT " + HOLD_COLS + " FROM seat_hold WHERE id = ?", HOLD, id);
        }

        @Override
        public Optional<SeatHold> findActiveByUserAndShow(long userId, long showId) {
            return sql.queryOne(
                    "SELECT "
                            + HOLD_COLS
                            + " FROM seat_hold WHERE user_id = ? AND show_id = ?"
                            + " AND status = 'ACTIVE' ORDER BY created_at DESC LIMIT 1",
                    HOLD,
                    userId,
                    showId);
        }

        @Override
        public List<SeatHold> findActiveByShow(long showId) {
            return sql.query(
                    "SELECT "
                            + HOLD_COLS
                            + " FROM seat_hold WHERE show_id = ? AND status = 'ACTIVE'"
                            + " ORDER BY created_at, id",
                    HOLD,
                    showId);
        }

        @Override
        public List<SeatHold> findExpiredActive(Instant now, int limit) {
            return sql.query(
                    "SELECT "
                            + HOLD_COLS
                            + " FROM seat_hold WHERE status = 'ACTIVE' AND expires_at <= ?"
                            + " ORDER BY expires_at, id LIMIT ?",
                    HOLD,
                    Sqls.ms(now),
                    limit);
        }

        @Override
        public long countActive(Instant now) {
            return sql.queryOne(
                            "SELECT count(*) FROM seat_hold WHERE status = 'ACTIVE'"
                                    + " AND expires_at > ?",
                            rs -> rs.getLong(1),
                            Sqls.ms(now))
                    .orElse(0L);
        }
    }

    static final class Bookings implements BookingRepositories.Bookings {
        private final Sql sql;

        Bookings(Sql sql) {
            this.sql = sql;
        }

        /** Rows without their seats; seats come from one batched query per result set. */
        private static final RowMapper<Booking> ROW =
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
                                Sqls.instant(rs, "expires_at"),
                                rs.getLong("refund_amount"),
                                rs.getInt("checkout_count"),
                                rs.getLong("version"),
                                Sqls.instant(rs, "created_at"),
                                Sqls.instant(rs, "updated_at"));

        @Override
        public void insert(Booking b) {
            sql.update(
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
                    Sqls.ms(b.expiresAt()),
                    b.refundAmount(),
                    b.checkoutCount(),
                    b.version(),
                    Sqls.ms(b.createdAt()),
                    Sqls.ms(b.updatedAt()));
            sql.batch(
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

        @Override
        public Booking update(Booking b) {
            try {
                sql.updateExpecting(
                        "UPDATE booking SET status = ?, expires_at = ?, refund_amount = ?,"
                                + " checkout_count = ?, updated_at = ?, version = version + 1"
                                + " WHERE id = ? AND version = ?",
                        1,
                        b.status().name(),
                        Sqls.ms(b.expiresAt()),
                        b.refundAmount(),
                        b.checkoutCount(),
                        Sqls.ms(b.updatedAt()),
                        b.id(),
                        b.version());
            } catch (OptimisticLockException stale) {
                // The use case re-reads and retries once, then answers 409 VERSION_CONFLICT.
                throw DomainException.versionConflict();
            }
            return b.withVersion(b.version() + 1);
        }

        @Override
        public Optional<Booking> findById(String id) {
            return withSeats(
                            sql.query(
                                    "SELECT " + BOOKING_COLS + " FROM booking WHERE id = ?",
                                    ROW,
                                    id))
                    .stream()
                    .findFirst();
        }

        @Override
        public boolean existsByCode(String code) {
            return sql.queryOne("SELECT 1 FROM booking WHERE code = ?", rs -> 1, code).isPresent();
        }

        @Override
        public Page<Booking> page(long userId, Set<BookingStatus> statuses, int page, int size) {
            StringBuilder where = new StringBuilder(" WHERE user_id = ?");
            List<Object> params = new ArrayList<>();
            params.add(userId);
            if (!statuses.isEmpty()) {
                where.append(" AND status IN (").append(Sqls.marks(statuses.size())).append(')');
                statuses.forEach(s -> params.add(s.name()));
            }
            long total =
                    sql.queryOne(
                                    "SELECT count(*) FROM booking" + where,
                                    rs -> rs.getLong(1),
                                    params.toArray())
                            .orElse(0L);
            params.add(size);
            params.add((long) Math.max(page - 1, 0) * size);
            List<Booking> items =
                    sql.query(
                            "SELECT "
                                    + BOOKING_COLS
                                    + " FROM booking"
                                    + where
                                    + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                            ROW,
                            params.toArray());
            return new Page<>(withSeats(items), page, size, total);
        }

        @Override
        public List<Booking> findOpenExpired(Instant now, int limit) {
            return withSeats(
                    sql.query(
                            "SELECT "
                                    + BOOKING_COLS
                                    + " FROM booking WHERE status IN ('HELD', 'PAYMENT_PENDING')"
                                    + " AND expires_at <= ? ORDER BY expires_at, id LIMIT ?",
                            ROW,
                            Sqls.ms(now),
                            limit));
        }

        @Override
        public List<Booking> findLiveByShow(long showId) {
            return withSeats(
                    sql.query(
                            "SELECT "
                                    + BOOKING_COLS
                                    + " FROM booking WHERE show_id = ? AND status IN ('CONFIRMED',"
                                    + " 'HELD', 'PAYMENT_PENDING') ORDER BY created_at, id",
                            ROW,
                            showId));
        }

        private List<Booking> withSeats(List<Booking> rows) {
            if (rows.isEmpty()) {
                return rows;
            }
            Object[] ids = rows.stream().map(Booking::id).toArray();
            Map<String, List<BookingSeat>> seats = new HashMap<>();
            // rowid order is insertion order, which keeps the seats as the buyer chose them.
            sql.query(
                    "SELECT booking_id, seat_id, category, price FROM booking_seat"
                            + " WHERE booking_id IN ("
                            + Sqls.marks(ids.length)
                            + ") ORDER BY rowid",
                    rs -> {
                        seats.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                                .add(
                                        new BookingSeat(
                                                SeatId.parse(rs.getString(2)),
                                                rs.getString(3),
                                                rs.getLong(4)));
                        return null;
                    },
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
    }

    static final class Payments implements BookingRepositories.Payments {
        private final Sql sql;

        Payments(Sql sql) {
            this.sql = sql;
        }

        @Override
        public void insert(Payment p) {
            sql.update(
                    "INSERT INTO payment (" + PAYMENT_COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    p.id(),
                    p.bookingId(),
                    p.amount(),
                    p.currency(),
                    p.status().name(),
                    p.gatewayRef(),
                    p.refundedAmount(),
                    Sqls.ms(p.createdAt()),
                    Sqls.ms(p.updatedAt()));
        }

        @Override
        public void update(Payment p) {
            int n =
                    sql.update(
                            "UPDATE payment SET status = ?, gateway_ref = ?, refunded_amount = ?,"
                                    + " updated_at = ? WHERE id = ?",
                            p.status().name(),
                            p.gatewayRef(),
                            p.refundedAmount(),
                            Sqls.ms(p.updatedAt()),
                            p.id());
            if (n != 1) {
                throw DomainException.notFound("Payment");
            }
        }

        @Override
        public Optional<Payment> findById(String id) {
            return sql.queryOne(
                    "SELECT " + PAYMENT_COLS + " FROM payment WHERE id = ?", PAYMENT, id);
        }

        @Override
        public Optional<Payment> findLatestByBooking(String bookingId) {
            return sql.queryOne(
                    "SELECT "
                            + PAYMENT_COLS
                            + " FROM payment WHERE booking_id = ?"
                            + " ORDER BY created_at DESC, rowid DESC LIMIT 1",
                    PAYMENT,
                    bookingId);
        }
    }

    static final class PaymentEvents implements BookingRepositories.PaymentEvents {
        private final Sql sql;

        PaymentEvents(Sql sql) {
            this.sql = sql;
        }

        @Override
        public boolean recordIfNew(
                String eventId, String paymentId, String type, Instant receivedAt) {
            return sql.update(
                            "INSERT OR IGNORE INTO payment_event (event_id, payment_id, type,"
                                    + " received_at) VALUES (?, ?, ?, ?)",
                            eventId,
                            paymentId,
                            type,
                            Sqls.ms(receivedAt))
                    == 1;
        }
    }
}
