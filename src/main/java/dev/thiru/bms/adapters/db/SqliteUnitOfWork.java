package dev.thiru.bms.adapters.db;

import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.UnitOfWork;
import in.jolt.db.Db;
import java.util.function.Function;

/**
 * One SQLite transaction per call. Connections come from {@link Sqlite}, whose URL makes every
 * transaction {@code BEGIN IMMEDIATE}, so a writer takes the lock up front instead of failing later
 * on lock upgrade. Jolt's {@code Db.inTransaction} commits when the work returns and rolls back
 * when it throws.
 */
public final class SqliteUnitOfWork implements UnitOfWork {

    private final Db db;

    public SqliteUnitOfWork(Db db) {
        this.db = db;
    }

    @Override
    public <T> T inTransaction(Function<Repositories, T> work) {
        return db.inTransaction(sql -> work.apply(new SqlRepositories(sql)));
    }
}
