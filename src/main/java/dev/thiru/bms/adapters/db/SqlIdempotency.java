package dev.thiru.bms.adapters.db;

import dev.thiru.bms.domain.port.IdempotencyStore;
import in.jolt.db.Db;
import java.time.Instant;
import java.util.Optional;

/**
 * Idempotency-Key responses in table {@code idempotency_key} (migration V3). {@link #begin} runs in
 * one IMMEDIATE transaction, so two concurrent requests with the same key cannot both see it as
 * fresh.
 *
 * <p>TODO(jolt#54): delete when Jolt ships {@code Idempotency.keys()}.
 */
public final class SqlIdempotency implements IdempotencyStore {

    private record Row(String fingerprint, Integer status, String contentType, byte[] body) {}

    private final Db db;

    public SqlIdempotency(Db db) {
        this.db = db;
    }

    @Override
    public Claim begin(long userId, String key, String fingerprint, Instant now) {
        return db.inTransaction(
                tx -> {
                    Optional<Row> row =
                            tx.queryOne(
                                    "SELECT fingerprint, status, content_type, body"
                                            + " FROM idempotency_key WHERE user_id = ? AND key = ?",
                                    rs -> {
                                        // NULL status marks a running request; getObject keeps NULL
                                        // apart from a stored status.
                                        Object status = rs.getObject("status");
                                        return new Row(
                                                rs.getString("fingerprint"),
                                                status == null
                                                        ? null
                                                        : ((Number) status).intValue(),
                                                rs.getString("content_type"),
                                                rs.getBytes("body"));
                                    },
                                    userId,
                                    key);
                    if (row.isEmpty()) {
                        tx.update(
                                "INSERT INTO idempotency_key (user_id, key, fingerprint,"
                                        + " created_at) VALUES (?, ?, ?, ?)",
                                userId,
                                key,
                                fingerprint,
                                Sqls.ms(now));
                        return new Claim(Kind.FRESH, null);
                    }
                    Row r = row.get();
                    if (!r.fingerprint().equals(fingerprint)) {
                        return new Claim(Kind.MISMATCH, null);
                    }
                    if (r.status() == null) {
                        return new Claim(Kind.IN_FLIGHT, null);
                    }
                    byte[] body = r.body() == null ? new byte[0] : r.body();
                    return new Claim(Kind.REPLAY, new Stored(r.status(), r.contentType(), body));
                });
    }

    @Override
    public void complete(long userId, String key, Stored response) {
        db.update(
                "UPDATE idempotency_key SET status = ?, content_type = ?, body = ?"
                        + " WHERE user_id = ? AND key = ?",
                response.status(),
                response.contentType(),
                response.body() == null ? new byte[0] : response.body(),
                userId,
                key);
    }

    @Override
    public void release(long userId, String key) {
        // Only an unfinished claim: a stored success must keep replaying.
        db.update(
                "DELETE FROM idempotency_key WHERE user_id = ? AND key = ? AND status IS NULL",
                userId,
                key);
    }

    /** Forgets keys first seen before {@code cutoff}; the sweeper calls this. */
    public int purgeBefore(Instant cutoff) {
        return db.update("DELETE FROM idempotency_key WHERE created_at < ?", Sqls.ms(cutoff));
    }
}
