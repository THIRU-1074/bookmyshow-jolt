package dev.thiru.bms.testing;

import dev.thiru.bms.adapters.db.SchemaRunner;
import dev.thiru.bms.adapters.db.Sqlite;
import dev.thiru.bms.adapters.db.SqliteDb;
import dev.thiru.bms.ports.Db;
import java.nio.file.Path;
import java.time.Duration;

/**
 * A migrated, seeded temp-file SQLite behind {@link Db}, for feature slice tests (PLAN.md: slice
 * tests run on SQLite, about 50 ms each). Use with JUnit's {@code @TempDir}:
 *
 * <pre>{@code
 * @TempDir Path dir;
 * TestDb test;
 * @BeforeEach void open() { test = TestDb.open(dir); }
 * @AfterEach void close() { test.close(); }
 * }</pre>
 */
public final class TestDb implements AutoCloseable {

    private final in.jolt.db.Db jolt;
    private final Db db;

    private TestDb(in.jolt.db.Db jolt) {
        this.jolt = jolt;
        this.db = new SqliteDb(jolt);
    }

    /** Applies every migration (schema and seed) to {@code dir/bms.db} and opens it. */
    public static TestDb open(Path dir) {
        String url = Sqlite.jdbcUrl(dir.resolve("bms.db"));
        SchemaRunner.migrate(url);
        // Pool larger than concurrency tests need, so waiting happens on the SQLite lock.
        return new TestDb(Sqlite.open(url, 16, Duration.ofSeconds(30)));
    }

    public Db db() {
        return db;
    }

    @Override
    public void close() {
        jolt.close();
    }
}
