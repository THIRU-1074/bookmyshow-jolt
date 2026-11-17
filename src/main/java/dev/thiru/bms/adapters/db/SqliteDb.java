package dev.thiru.bms.adapters.db;

import dev.thiru.bms.ports.Db;
import in.jolt.db.Sql;
import in.jolt.db.TxMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link Db} on Jolt's {@code Db.inTransaction(TxMode.IMMEDIATE, ...)}: a writer takes the SQLite
 * lock up front instead of failing later on lock upgrade (SPEC 5.2). After-commit actions are
 * collected per attempt and run once the commit has returned.
 */
public final class SqliteDb implements Db {

    private static final System.Logger LOG = System.getLogger("bms.db");

    private final in.jolt.db.Db db;

    public SqliteDb(in.jolt.db.Db db) {
        this.db = db;
    }

    @Override
    public <T> T inTransaction(Function<Tx, T> work) {
        AtomicReference<SqlTx> last = new AtomicReference<>();
        T result =
                db.inTransaction(
                        TxMode.IMMEDIATE,
                        sql -> {
                            // A fresh Tx per attempt, so a retried attempt never inherits the
                            // after-commit actions of one that rolled back.
                            SqlTx tx = new SqlTx(sql);
                            last.set(tx);
                            try {
                                return work.apply(tx);
                            } finally {
                                tx.open = false;
                            }
                        });
        SqlTx committed = last.get();
        if (committed != null) {
            committed.runAfterCommit();
        }
        return result;
    }

    private static final class SqlTx implements Tx {
        private final Sql sql;
        private final List<Runnable> afterCommit = new ArrayList<>();
        private volatile boolean open = true;

        SqlTx(Sql sql) {
            this.sql = sql;
        }

        private Sql sql() {
            if (!open) {
                throw new IllegalStateException("transaction has ended");
            }
            return sql;
        }

        @Override
        public <T> List<T> query(String q, Row<T> row, Object... args) {
            return sql().query(q, row::map, args);
        }

        @Override
        public <T> Optional<T> queryOne(String q, Row<T> row, Object... args) {
            return sql().queryOne(q, row::map, args);
        }

        @Override
        public int update(String q, Object... args) {
            Sql s = sql();
            return constrained(() -> s.update(q, args));
        }

        @Override
        public long insert(String q, Object... args) {
            Sql s = sql();
            return constrained(() -> s.insert(q, args))
                    .orElseThrow(() -> new IllegalStateException("no generated key: " + q));
        }

        @Override
        public int[] batch(String q, List<Object[]> rows) {
            Sql s = sql();
            return constrained(() -> s.batch(q, rows));
        }

        @Override
        public void afterCommit(Runnable action) {
            sql();
            afterCommit.add(action);
        }

        void runAfterCommit() {
            for (Runnable action : afterCommit) {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    // Class only: messages can carry user data. The commit stands.
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "after-commit action failed: " + e.getClass().getName());
                }
            }
        }

        private static <T> T constrained(Supplier<T> statement) {
            try {
                return statement.get();
            } catch (RuntimeException e) {
                if (Sqls.isConstraint(e)) {
                    throw new ConstraintViolation("constraint violated", e);
                }
                throw e;
            }
        }
    }
}
