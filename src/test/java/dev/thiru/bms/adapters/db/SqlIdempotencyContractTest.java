package dev.thiru.bms.adapters.db;

import dev.thiru.bms.contract.IdempotencyStoreContract;
import dev.thiru.bms.testing.MutableClock;
import in.jolt.IdempotencyStore;
import in.jolt.db.Db;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** The production idempotency_key table, held to the Idempotency-Key contract. */
class SqlIdempotencyContractTest extends IdempotencyStoreContract {

    @TempDir Path dir;
    private Db db;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    @Override
    protected IdempotencyStore newStore() {
        String url = Sqlite.jdbcUrl(dir.resolve("contract.db"));
        SchemaRunner.migrate(url);
        db = Sqlite.open(url, 4, Duration.ofSeconds(10));
        return new SqlIdempotency(db, clock);
    }

    @AfterEach
    void closeDb() {
        db.close();
    }

    @Override
    protected Duration lease() {
        return SqlIdempotency.LEASE;
    }

    @Override
    protected void advance(Duration d) {
        clock.advance(d);
    }
}
