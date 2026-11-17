package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.db.Db;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteTest {

    @TempDir Path dir;

    @Test
    void urlCarriesEveryRequiredPragma() {
        String url = Sqlite.jdbcUrl(dir.resolve("a.db"));
        assertTrue(url.startsWith("jdbc:sqlite:"), url);
        assertTrue(url.contains("journal_mode=WAL"), url);
        assertTrue(url.contains("busy_timeout=5000"), url);
        assertTrue(url.contains("foreign_keys=true"), url);
        // IMMEDIATE is chosen per transaction (TxMode), not for every connection.
        assertFalse(url.contains("transaction_mode"), url);
    }

    @Test
    void explicitSettingsInTheUrlAreNotOverridden() {
        String url = Sqlite.withPragmas("jdbc:sqlite:x.db?busy_timeout=100");
        assertTrue(url.contains("busy_timeout=100"), url);
        assertEquals(1, url.split("busy_timeout", -1).length - 1, url);
    }

    @Test
    void everyPooledConnectionAppliesThePragmas() {
        try (Db db = Sqlite.open(Sqlite.jdbcUrl(dir.resolve("b.db")))) {
            for (int i = 0; i < 3; i++) {
                assertEquals(1, db.queryOne("PRAGMA foreign_keys", r -> r.getInt(1)).orElseThrow());
                assertEquals(
                        "wal",
                        db.queryOne("PRAGMA journal_mode", r -> r.getString(1)).orElseThrow());
                assertEquals(
                        5000, db.queryOne("PRAGMA busy_timeout", r -> r.getInt(1)).orElseThrow());
            }
        }
    }

    @Test
    void sliceTransactionsBeginImmediate() throws Exception {
        String url = Sqlite.jdbcUrl(dir.resolve("c.db"));
        try (Db db = Sqlite.open(url);
                Connection other = Sqlite.connect(url)) {
            db.update("CREATE TABLE t (id INTEGER PRIMARY KEY) STRICT");
            new SqliteDb(db)
                    .inTransaction(
                            tx -> {
                                // With IMMEDIATE the write lock is taken at BEGIN, so before any
                                // statement a second writer is blocked and fails once its timeout
                                // expires.
                                try (Statement st = other.createStatement()) {
                                    st.execute("PRAGMA busy_timeout = 50");
                                    st.executeUpdate("INSERT INTO t VALUES (1)");
                                    throw new AssertionError("second writer should be blocked");
                                } catch (SQLException expected) {
                                    assertTrue(
                                            expected.getMessage().contains("BUSY"),
                                            expected.getMessage());
                                }
                                return null;
                            });
        }
    }
}
