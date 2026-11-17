package dev.thiru.bms.adapters.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/** Small helpers shared by the repositories. */
final class Sqls {

    private Sqls() {}

    /** {@code ?,?,?} with {@code n} markers, for IN lists. */
    static String marks(int n) {
        return "?" + ",?".repeat(Math.max(0, n - 1));
    }

    static long ms(Instant t) {
        return t.toEpochMilli();
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return Instant.ofEpochMilli(rs.getLong(column));
    }

    /** True when {@code t} or a cause is a SQLite constraint violation (SQLITE_CONSTRAINT = 19). */
    static boolean isConstraint(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException e
                    && (e.getErrorCode() == 19
                            || (e.getMessage() != null
                                    && e.getMessage().contains("SQLITE_CONSTRAINT")))) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }
}
