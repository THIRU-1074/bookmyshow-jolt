package dev.thiru.bms.adapters.db;

import in.jolt.db.Db;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** Base for adapter tests: a temp-file SQLite migrated with every migration (schema and seed). */
abstract class DbTest {

    static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    @TempDir Path dir;
    Db db;

    @BeforeEach
    void openDb() {
        String url = Sqlite.jdbcUrl(dir.resolve("bms.db"));
        SchemaRunner.migrate(url);
        // Pool larger than the concurrency tests need so waiting happens on the SQLite lock.
        db = Sqlite.open(url, 16, Duration.ofSeconds(30));
    }

    @AfterEach
    void closeDb() {
        db.close();
    }
}
