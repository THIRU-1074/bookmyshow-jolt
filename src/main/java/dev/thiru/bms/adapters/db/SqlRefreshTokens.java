package dev.thiru.bms.adapters.db;

import dev.thiru.bms.domain.port.RefreshTokenStore;
import in.jolt.db.Db;
import in.jolt.db.RowMapper;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Refresh-token hashes in table {@code refresh_token}, so sessions survive a restart. Rotation is
 * one IMMEDIATE transaction: the conditional {@code used = 0} update is what makes a replayed token
 * lose the race.
 */
public final class SqlRefreshTokens implements RefreshTokenStore {

    private static final String COLS = "hash, subject, family, expires_at, used";

    private static final RowMapper<Entry> ENTRY =
            rs ->
                    new Entry(
                            rs.getString("hash"),
                            rs.getString("subject"),
                            rs.getString("family"),
                            Sqls.instant(rs, "expires_at"),
                            rs.getInt("used") != 0);

    private final Db db;

    public SqlRefreshTokens(Db db) {
        this.db = db;
    }

    @Override
    public void save(Entry entry) {
        insert(db, entry);
    }

    private static void insert(in.jolt.db.Sql sql, Entry e) {
        sql.update(
                "INSERT OR REPLACE INTO refresh_token (" + COLS + ") VALUES (?, ?, ?, ?, ?)",
                e.hash(),
                e.subject(),
                e.family(),
                Sqls.ms(e.expires()),
                e.used() ? 1 : 0);
    }

    @Override
    public Optional<Entry> find(String hash) {
        return db.queryOne("SELECT " + COLS + " FROM refresh_token WHERE hash = ?", ENTRY, hash);
    }

    @Override
    public boolean rotate(String oldHash, Entry next) {
        return db.inTransaction(
                tx -> {
                    int marked =
                            tx.update(
                                    "UPDATE refresh_token SET used = 1 WHERE hash = ? AND used = 0",
                                    oldHash);
                    if (marked != 1) {
                        return false;
                    }
                    insert(tx, next);
                    return true;
                });
    }

    @Override
    public void revokeFamily(String family) {
        db.update("DELETE FROM refresh_token WHERE family = ?", family);
    }

    @Override
    public Set<String> revokeSubject(String subject) {
        return db.inTransaction(
                tx -> {
                    Set<String> families =
                            new LinkedHashSet<>(
                                    tx.query(
                                            "SELECT DISTINCT family FROM refresh_token"
                                                    + " WHERE subject = ?",
                                            rs -> rs.getString(1),
                                            subject));
                    tx.update("DELETE FROM refresh_token WHERE subject = ?", subject);
                    return families;
                });
    }

    /** Deletes tokens past their expiry; the sweeper calls this. Returns the rows removed. */
    public int purgeExpired(Instant now) {
        return db.update("DELETE FROM refresh_token WHERE expires_at <= ?", Sqls.ms(now));
    }
}
