package dev.thiru.bms.adapters.db;

import dev.thiru.bms.contract.DbContract;
import dev.thiru.bms.ports.Db;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** The production transaction seam on a temp-file SQLite, held to the {@code ports.Db} contract. */
class SqliteDbContractTest extends DbContract {

    @TempDir Path dir;
    private in.jolt.db.Db jolt;

    @Override
    protected Db newDb() {
        // Pool larger than the writers so they queue on the SQLite lock, as in production.
        jolt = Sqlite.open(Sqlite.jdbcUrl(dir.resolve("db.db")), 8, Duration.ofSeconds(30));
        return new SqliteDb(jolt);
    }

    @AfterEach
    void closeDb() {
        jolt.close();
    }
}
