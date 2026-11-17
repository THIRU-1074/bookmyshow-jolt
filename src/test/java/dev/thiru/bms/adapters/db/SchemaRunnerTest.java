package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.db.Db;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaRunnerTest {

    private static final String FIXTURES = "db/test-migrations";

    @TempDir Path dir;

    @Test
    void appliesMigrationsInVersionOrderAndRecordsThem() {
        String url = Sqlite.jdbcUrl(dir.resolve("a.db"));
        assertEquals(List.of(1, 2, 10), SchemaRunner.migrate(url, FIXTURES));
        try (Db db = Sqlite.open(url)) {
            assertEquals(
                    List.of(1, 2, 10),
                    db.query(
                            "SELECT version FROM schema_history ORDER BY version",
                            r -> r.getInt(1)));
            // V2 inserts a string containing a semicolon and a quote; V10 sorts after V2
            // numerically.
            assertEquals(
                    List.of("a;b", "it's", "ten"),
                    db.query("SELECT v FROM probe ORDER BY id", r -> r.getString(1)));
        }
    }

    @Test
    void secondRunAppliesNothing() {
        String url = Sqlite.jdbcUrl(dir.resolve("b.db"));
        SchemaRunner.migrate(url, FIXTURES);
        assertEquals(List.of(), SchemaRunner.migrate(url, FIXTURES));
    }

    @Test
    void aFailingMigrationRollsBackAndStopsTheRun() {
        String url = Sqlite.jdbcUrl(dir.resolve("c.db"));
        assertThrows(
                IllegalStateException.class, () -> SchemaRunner.migrate(url, "db/bad-migrations"));
        try (Db db = Sqlite.open(url)) {
            assertEquals(
                    List.of(1), db.query("SELECT version FROM schema_history", r -> r.getInt(1)));
            assertEquals(
                    0,
                    db.queryOne(
                                    "SELECT count(*) FROM sqlite_master WHERE name = 'half_done'",
                                    r -> r.getInt(1))
                            .orElseThrow());
        }
    }

    @Test
    void aMigrationEditedAfterItWasAppliedIsRejected() throws Exception {
        String url = Sqlite.jdbcUrl(dir.resolve("d.db"));
        SchemaRunner.migrate(url, FIXTURES);
        try (Db db = Sqlite.open(url)) {
            db.update("UPDATE schema_history SET checksum = 'tampered' WHERE version = 1");
        }
        assertThrows(IllegalStateException.class, () -> SchemaRunner.migrate(url, FIXTURES));
        Files.deleteIfExists(dir.resolve("d.db"));
    }

    @Test
    void splitterRespectsQuotesAndComments() {
        assertEquals(
                List.of("SELECT 'a;b'", "SELECT 2"),
                SchemaRunner.split("-- c; x\nSELECT 'a;b'; /* y; */ SELECT 2;\n"));
    }
}
