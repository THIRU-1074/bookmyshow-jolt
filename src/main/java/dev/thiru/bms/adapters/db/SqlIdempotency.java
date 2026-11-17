package dev.thiru.bms.adapters.db;

import in.jolt.IdempotencyStore;
import in.jolt.db.Db;
import in.jolt.db.TxMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Jolt's {@link IdempotencyStore} SPI on table {@code idempotency_key} (migration V5), so stored
 * responses survive a restart. {@link #begin} runs in one IMMEDIATE transaction, so two concurrent
 * requests with the same key cannot both start. Rows older than 24 hours are purged by the sweep.
 */
public final class SqlIdempotency implements IdempotencyStore {

    /**
     * Not replayed: a replay must echo its own request id, not the first request's. RequestId sets
     * it before the handler runs, so Jolt's snapshot of the response would otherwise keep it.
     */
    private static final String REQUEST_ID = "X-Request-Id";

    private record Row(String fingerprint, Integer status, String headers, byte[] body) {}

    private final Db db;
    private final Clock clock;

    public SqlIdempotency(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Override
    public Claim begin(Key key, String fingerprint) {
        return db.inTransaction(
                TxMode.IMMEDIATE,
                tx -> {
                    Optional<Row> row =
                            tx.queryOne(
                                    "SELECT fingerprint, status, headers, body"
                                            + " FROM idempotency_key WHERE scope = ? AND key = ?",
                                    rs -> {
                                        // NULL status marks a running request; getObject keeps NULL
                                        // apart from a stored status.
                                        Object status = rs.getObject("status");
                                        return new Row(
                                                rs.getString("fingerprint"),
                                                status == null
                                                        ? null
                                                        : ((Number) status).intValue(),
                                                rs.getString("headers"),
                                                rs.getBytes("body"));
                                    },
                                    key.scope(),
                                    key.id());
                    if (row.isEmpty()) {
                        tx.update(
                                "INSERT INTO idempotency_key (scope, key, fingerprint, created_at)"
                                        + " VALUES (?, ?, ?, ?)",
                                key.scope(),
                                key.id(),
                                fingerprint,
                                Sqls.ms(clock.instant()));
                        return Claim.started();
                    }
                    Row r = row.get();
                    if (!r.fingerprint().equals(fingerprint)) {
                        return Claim.mismatch();
                    }
                    if (r.status() == null) {
                        return Claim.inFlight();
                    }
                    byte[] body = r.body() == null ? new byte[0] : r.body();
                    return Claim.completed(new Stored(r.status(), headers(r.headers()), body));
                });
    }

    @Override
    public void complete(Key key, Stored response) {
        List<Object> pairs = new ArrayList<>();
        for (Map.Entry<String, String> h : response.headers()) {
            if (!h.getKey().equalsIgnoreCase(REQUEST_ID)) {
                pairs.add(List.of(h.getKey(), h.getValue()));
            }
        }
        db.update(
                "UPDATE idempotency_key SET status = ?, headers = ?, body = ?"
                        + " WHERE scope = ? AND key = ?",
                response.status(),
                Json.write(pairs),
                response.body() == null ? new byte[0] : response.body(),
                key.scope(),
                key.id());
    }

    @Override
    public void release(Key key) {
        // Only an unfinished claim: a stored response must keep replaying.
        db.update(
                "DELETE FROM idempotency_key WHERE scope = ? AND key = ? AND status IS NULL",
                key.scope(),
                key.id());
    }

    /** Forgets keys first seen before {@code cutoff}; the sweep calls this. */
    public int purgeBefore(Instant cutoff) {
        return db.update("DELETE FROM idempotency_key WHERE created_at < ?", Sqls.ms(cutoff));
    }

    private static List<Map.Entry<String, String>> headers(String json) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        if (json == null) {
            return out;
        }
        for (Object pair : Json.array(Json.parse(json))) {
            List<Object> p = Json.array(pair);
            out.add(Map.entry(String.valueOf(p.get(0)), String.valueOf(p.get(1))));
        }
        return List.copyOf(out);
    }
}
