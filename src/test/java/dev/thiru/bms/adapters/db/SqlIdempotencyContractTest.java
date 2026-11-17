package dev.thiru.bms.adapters.db;

import dev.thiru.bms.contract.IdempotencyStoreContract;
import in.jolt.IdempotencyStore;
import in.jolt.db.Db;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** The production idempotency_key table, held to the Idempotency-Key contract. */
class SqlIdempotencyContractTest extends IdempotencyStoreContract {

    @TempDir Path dir;
    private Db db;

    @Override
    protected IdempotencyStore newStore() {
        String url = Sqlite.jdbcUrl(dir.resolve("contract.db"));
        SchemaRunner.migrate(url);
        db = Sqlite.open(url, 4, Duration.ofSeconds(10));
        return new SqlIdempotency(db, Clock.systemUTC());
    }

    @AfterEach
    void closeDb() {
        db.close();
    }
}
