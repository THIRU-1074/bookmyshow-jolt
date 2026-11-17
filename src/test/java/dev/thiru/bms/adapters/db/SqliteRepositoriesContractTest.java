package dev.thiru.bms.adapters.db;

import dev.thiru.bms.contract.RepositoriesContract;
import dev.thiru.bms.domain.port.UnitOfWork;
import in.jolt.db.Db;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** The production unit of work on a migrated, seeded SQLite file, held to the same contract. */
class SqliteRepositoriesContractTest extends RepositoriesContract {

    @TempDir Path dir;
    private Db db;

    @Override
    protected UnitOfWork newUnitOfWork() {
        String url = Sqlite.jdbcUrl(dir.resolve("contract.db"));
        SchemaRunner.migrate(url);
        db = Sqlite.open(url, 4, Duration.ofSeconds(10));
        return new SqliteUnitOfWork(db);
    }

    @AfterEach
    void closeDb() {
        db.close();
    }
}
