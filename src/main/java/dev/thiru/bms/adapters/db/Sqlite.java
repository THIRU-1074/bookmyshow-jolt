package dev.thiru.bms.adapters.db;

import in.jolt.db.Db;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opens SQLite the way SPEC section 5 requires: WAL, a 5 s busy timeout and foreign keys on. The
 * connection settings ride on the JDBC URL, which sqlite-jdbc applies to each new connection;
 * {@code BEGIN IMMEDIATE} is chosen per transaction with {@code Db.inTransaction(TxMode.IMMEDIATE,
 * ...)}, which waits up to {@link #BUSY_TIMEOUT} for the write lock.
 *
 * <p>In-memory URLs are not supported: each pooled connection would see its own empty database.
 */
public final class Sqlite {

    private static final Map<String, String> PRAGMAS = pragmas();

    /** How long an IMMEDIATE transaction waits for the write lock (SPEC 5.2). */
    public static final Duration BUSY_TIMEOUT = Duration.ofSeconds(5);

    private Sqlite() {}

    private static Map<String, String> pragmas() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("journal_mode", "WAL");
        m.put("busy_timeout", "5000");
        m.put("foreign_keys", "true");
        // Safe with WAL and much cheaper than the FULL default.
        m.put("synchronous", "NORMAL");
        return m;
    }

    /** A file database URL with all pragmas applied. */
    public static String jdbcUrl(Path file) {
        return withPragmas("jdbc:sqlite:" + file.toAbsolutePath().toString().replace('\\', '/'));
    }

    /** Appends the required settings to {@code url}, keeping any the caller already set. */
    public static String withPragmas(String url) {
        StringBuilder out = new StringBuilder(url);
        char sep = url.indexOf('?') >= 0 ? '&' : '?';
        for (Map.Entry<String, String> p : PRAGMAS.entrySet()) {
            if (!hasParam(url, p.getKey())) {
                out.append(sep).append(p.getKey()).append('=').append(p.getValue());
                sep = '&';
            }
        }
        return out.toString();
    }

    private static boolean hasParam(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0) {
            return false;
        }
        for (String part : url.substring(q + 1).split("&")) {
            if (part.equals(key) || part.startsWith(key + "=")) {
                return true;
            }
        }
        return false;
    }

    /** Opens a jolt-db pool; pragmas are added to {@code jdbcUrl} if missing. */
    public static Db open(String jdbcUrl) {
        return Db.open(withPragmas(jdbcUrl)).busyTimeout(BUSY_TIMEOUT);
    }

    /** Opens a jolt-db pool of {@code size} connections waiting up to {@code timeout} for one. */
    public static Db open(String jdbcUrl, int size, Duration timeout) {
        return Db.open(withPragmas(jdbcUrl), size, timeout).busyTimeout(BUSY_TIMEOUT);
    }

    /** A plain JDBC connection with the same settings, for work jolt-db cannot do (scripts). */
    public static Connection connect(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(withPragmas(jdbcUrl));
    }
}
