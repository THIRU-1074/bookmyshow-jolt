package dev.thiru.bms.ports;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The transaction seam for feature slices: one writer transaction per call (SPEC 5.2), committed
 * when the work returns and rolled back when it throws, with the work's own exception. Replaces
 * {@code UnitOfWork} + {@code Repositories}: a slice runs its own SQL through {@link Tx}.
 *
 * <p>{@link Tx#afterCommit} replaces {@code EventBuffer}: work queues side effects such as seat
 * events on the transaction, and they run only once it has committed.
 */
public interface Db {

    <T> T inTransaction(Function<Tx, T> work);

    /** Maps the current row; JDK types only, so slices never see the database library. */
    @FunctionalInterface
    interface Row<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** Bound to one transaction; any use after it ends throws {@link IllegalStateException}. */
    interface Tx {

        <T> List<T> query(String sql, Row<T> row, Object... args);

        <T> Optional<T> queryOne(String sql, Row<T> row, Object... args);

        /** Rows changed. A unique or check clash is a {@link ConstraintViolation}. */
        int update(String sql, Object... args);

        /** Inserts one row and returns its generated key. */
        long insert(String sql, Object... args);

        /** One statement per argument row; rows changed by each. */
        int[] batch(String sql, List<Object[]> rows);

        /**
         * Runs {@code action} after this transaction commits, in registration order, never on
         * rollback. A failing action is logged and skipped: it cannot undo the commit or stop the
         * actions after it.
         */
        void afterCommit(Runnable action);
    }

    /**
     * A statement broke a constraint (UNIQUE, CHECK, FOREIGN KEY). Only that statement failed; the
     * transaction can go on, for example to look up what clashed.
     */
    final class ConstraintViolation extends RuntimeException {
        public ConstraintViolation(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Instants are stored as epoch milliseconds. */
    static long millis(Instant t) {
        return t.toEpochMilli();
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return Instant.ofEpochMilli(rs.getLong(column));
    }

    /** {@code ?,?,?} with {@code n} markers, for IN lists. */
    static String marks(int n) {
        return "?" + ",?".repeat(Math.max(0, n - 1));
    }
}
