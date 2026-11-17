package dev.thiru.bms.features.live;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.kernel.booking.SeatLock;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.kernel.catalog.SeatMap;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import dev.thiru.bms.ports.Db;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The live stream's reads: a show's status and start, and the seat map snapshot. The snapshot SQL
 * repeats the catalog's on purpose (slices never import each other); the assembly is shared in
 * {@link SeatMap}.
 */
final class LiveSql {

    record ShowState(ShowStatus status, Instant startsAt) {}

    private final Db db;
    private final Clock clock;
    private final BookingPolicy policy;

    LiveSql(Db db, Clock clock, BookingPolicy policy) {
        this.db = db;
        this.clock = clock;
        this.policy = policy;
    }

    ShowState show(long showId) {
        return db.inTransaction(
                tx ->
                        tx.queryOne(
                                        "SELECT status, starts_at FROM show WHERE id = ?",
                                        rs ->
                                                new ShowState(
                                                        ShowStatus.valueOf(rs.getString("status")),
                                                        Db.instant(rs, "starts_at")),
                                        showId)
                                .orElseThrow(() -> DomainException.notFound("Show")));
    }

    SeatMap snapshot(long showId) {
        return db.inTransaction(
                tx -> {
                    long screenId =
                            tx.queryOne(
                                            "SELECT screen_id FROM show WHERE id = ?",
                                            rs -> rs.getLong(1),
                                            showId)
                                    .orElseThrow(() -> DomainException.notFound("Show"));
                    List<SeatLock> locks =
                            tx.query(
                                    "SELECT show_id, seat_id, hold_id, booking_id, state,"
                                            + " expires_at FROM seat_lock WHERE show_id = ?",
                                    rs -> {
                                        long expires = rs.getLong("expires_at");
                                        // wasNull() describes the last column read.
                                        boolean noExpiry = rs.wasNull();
                                        return new SeatLock(
                                                rs.getLong("show_id"),
                                                SeatId.parse(rs.getString("seat_id")),
                                                rs.getString("hold_id"),
                                                rs.getString("booking_id"),
                                                SeatLock.State.valueOf(rs.getString("state")),
                                                noExpiry ? null : Instant.ofEpochMilli(expires));
                                    },
                                    showId);
                    Map<String, Long> prices = new HashMap<>();
                    tx.query(
                            "SELECT p.key, p.value FROM show s, json_each(s.prices_json) p"
                                    + " WHERE s.id = ?",
                            rs -> prices.put(rs.getString(1), rs.getLong(2)),
                            showId);
                    return SeatMap.assemble(
                            showId,
                            layout(tx, screenId),
                            prices,
                            locks,
                            clock.instant(),
                            policy.holdTtl());
                });
    }

    /** The screen's {@code layout_json}, read element by element with SQLite's JSON functions. */
    private static SeatLayout layout(Db.Tx tx, long screenId) {
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
        if (json == null) {
            return List.of();
        }
        String body = json.strip();
        body = body.substring(1, body.length() - 1).strip();
        List<Integer> out = new ArrayList<>();
        if (!body.isEmpty()) {
            for (String n : body.split(",")) {
                out.add(Integer.parseInt(n.strip()));
            }
        }
        return out;
    }
}
