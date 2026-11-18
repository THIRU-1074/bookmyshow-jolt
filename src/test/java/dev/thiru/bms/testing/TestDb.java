package dev.thiru.bms.testing;

import dev.thiru.bms.adapters.db.SchemaRunner;
import dev.thiru.bms.adapters.db.Sqlite;
import dev.thiru.bms.adapters.db.SqliteDb;
import dev.thiru.bms.ports.Db;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
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
 *
 * <p>The migrations run once per JVM into a template file; each {@link #open} copies it, so a test
 * pays for a file copy, not for the V2 seed. The seeded shows start on the day the template was
 * made (see {@link SliceKit#seedDate()}).
 */
public final class TestDb implements AutoCloseable {

    private static final String FILE = "bms.db";
    private static Path template;

    private final in.jolt.db.Db jolt;
    private final Db db;

    private TestDb(in.jolt.db.Db jolt) {
        this.jolt = jolt;
        this.db = new SqliteDb(jolt);
    }

    /** Copies the migrated (schema and seed) template to {@code dir/bms.db} and opens it. */
    public static TestDb open(Path dir) {
        Path file = dir.resolve(FILE);
        try {
            Path from = template().resolve(FILE);
            Files.copy(from, file);
            Path wal = from.resolveSibling(FILE + "-wal");
            if (Files.exists(wal)) {
                Files.copy(wal, dir.resolve(FILE + "-wal"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Pool larger than concurrency tests need, so waiting happens on the SQLite lock.
        return new TestDb(Sqlite.open(Sqlite.jdbcUrl(file), 16, Duration.ofSeconds(30)));
    }

    private static synchronized Path template() throws IOException {
        if (template == null) {
            Path dir = Files.createTempDirectory("bms-testdb");
            Path file = dir.resolve(FILE);
            // Closing the last connection normally checkpoints the WAL into the file; open copies a
            // leftover WAL too.
            SchemaRunner.migrate(Sqlite.jdbcUrl(file));
            file.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            template = dir;
        }
        return template;
    }

    public Db db() {
        return db;
    }

    /** The pooled Jolt database under {@link #db()}, for adapters that take Jolt's type. */
    public in.jolt.db.Db joltDb() {
        return jolt;
    }

    @Override
    public void close() {
        jolt.close();
    }
}
