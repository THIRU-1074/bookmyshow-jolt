package dev.thiru.bms.adapters.db;

import dev.thiru.bms.domain.port.SeatLockRepository;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.SeatLock;
import in.jolt.db.RowMapper;
import in.jolt.db.Sql;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code seat_lock} access. The primary key {@code (show_id, seat_id)} is the only real guard
 * against double booking (SPEC 5.1); the conflict query below exists to name the seats in the
 * error, not to decide who wins.
 */
final class SqlSeatLocks implements SeatLockRepository {

    private static final String COLS = "show_id, seat_id, hold_id, booking_id, state, expires_at";

    private static final RowMapper<SeatLock> LOCK =
            rs -> {
                long expires = rs.getLong("expires_at");
                // wasNull() describes the last column read, so ask before reading others.
                boolean noExpiry = rs.wasNull();
                return new SeatLock(
                        rs.getLong("show_id"),
                        SeatId.parse(rs.getString("seat_id")),
                        rs.getString("hold_id"),
                        rs.getString("booking_id"),
                        SeatLock.State.valueOf(rs.getString("state")),
                        noExpiry ? null : Instant.ofEpochMilli(expires));
            };

    private final Sql sql;

    SqlSeatLocks(Sql sql) {
        this.sql = sql;
    }

    @Override
    public void lock(long showId, String holdId, List<SeatId> seats, Instant expiresAt) {
        // Under BEGIN IMMEDIATE nobody can slip in between this check and the inserts, so the
        // friendly path is exact; the primary key below still backs it up.
        List<SeatId> taken = conflicts(showId, holdId, seats);
        if (!taken.isEmpty()) {
            throw DomainException.seatUnavailable(taken);
        }
        try {
            sql.batch(
                    "INSERT INTO seat_lock (show_id, seat_id, hold_id, booking_id, state,"
                            + " expires_at) VALUES (?, ?, ?, NULL, 'HELD', ?)",
                    seats.stream()
                            .map(
                                    seat ->
                                            new Object[] {
                                                showId, seat.toString(), holdId, Sqls.ms(expiresAt)
                                            })
                            .toList());
        } catch (RuntimeException e) {
            if (Sqls.isConstraint(e)) {
                List<SeatId> clash = conflicts(showId, holdId, seats);
                if (!clash.isEmpty()) {
                    throw DomainException.seatUnavailable(clash);
                }
            }
            throw e;
        }
    }

    /** Requested seats that already have a row belonging to some other hold. */
    private List<SeatId> conflicts(long showId, String holdId, List<SeatId> seats) {
        if (seats.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(showId);
        params.add(holdId);
        seats.forEach(s -> params.add(s.toString()));
        return sql.query(
                "SELECT seat_id FROM seat_lock WHERE show_id = ? AND hold_id <> ?"
                        + " AND seat_id IN ("
                        + Sqls.marks(seats.size())
                        + ")",
                rs -> SeatId.parse(rs.getString(1)),
                params.toArray());
    }

    @Override
    public List<SeatId> deleteExpiredHeld(long showId, Collection<SeatId> seats, Instant now) {
        if (seats.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(showId);
        params.add(Sqls.ms(now));
        seats.forEach(s -> params.add(s.toString()));
        String where =
                " FROM seat_lock WHERE show_id = ? AND state = 'HELD' AND expires_at <= ?"
                        + " AND seat_id IN ("
                        + Sqls.marks(seats.size())
                        + ")";
        List<SeatId> freed =
                sql.query(
                        "SELECT seat_id" + where,
                        rs -> SeatId.parse(rs.getString(1)),
                        params.toArray());
        if (!freed.isEmpty()) {
            sql.update("DELETE" + where, params.toArray());
        }
        return sortedSeats(freed);
    }

    @Override
    public List<SeatId> deleteByHold(String holdId) {
        List<SeatId> freed =
                sql.query(
                        "SELECT seat_id FROM seat_lock WHERE hold_id = ?",
                        rs -> SeatId.parse(rs.getString(1)),
                        holdId);
        if (!freed.isEmpty()) {
            sql.update("DELETE FROM seat_lock WHERE hold_id = ?", holdId);
        }
        return sortedSeats(freed);
    }

    @Override
    public void attachBooking(String holdId, String bookingId) {
        sql.update("UPDATE seat_lock SET booking_id = ? WHERE hold_id = ?", bookingId, holdId);
    }

    @Override
    public void updateExpiry(String holdId, Instant expiresAt) {
        sql.update(
                "UPDATE seat_lock SET expires_at = ? WHERE hold_id = ? AND state = 'HELD'",
                Sqls.ms(expiresAt),
                holdId);
    }

    @Override
    public void markBooked(String holdId) {
        sql.update(
                "UPDATE seat_lock SET state = 'BOOKED', expires_at = NULL WHERE hold_id = ?",
                holdId);
    }

    @Override
    public List<SeatLock> findByHold(String holdId) {
        return sortedLocks(
                sql.query("SELECT " + COLS + " FROM seat_lock WHERE hold_id = ?", LOCK, holdId));
    }

    @Override
    public List<SeatLock> findByShow(long showId) {
        return sortedLocks(
                sql.query("SELECT " + COLS + " FROM seat_lock WHERE show_id = ?", LOCK, showId));
    }

    @Override
    public Map<Long, Integer> countLive(Collection<Long> showIds, Instant now) {
        if (showIds.isEmpty()) {
            return Map.of();
        }
        List<Object> params = new ArrayList<>(showIds);
        params.add(Sqls.ms(now));
        Map<Long, Integer> out = new HashMap<>();
        sql.query(
                "SELECT show_id, count(*) FROM seat_lock WHERE show_id IN ("
                        + Sqls.marks(showIds.size())
                        + ") AND (state = 'BOOKED' OR expires_at > ?) GROUP BY show_id",
                rs -> {
                    out.put(rs.getLong(1), rs.getInt(2));
                    return null;
                },
                params.toArray());
        return out;
    }

    private static List<SeatId> sortedSeats(List<SeatId> seats) {
        return seats.stream().sorted().toList();
    }

    private static List<SeatLock> sortedLocks(List<SeatLock> locks) {
        return locks.stream().sorted(java.util.Comparator.comparing(SeatLock::seat)).toList();
    }
}
