package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.db.Db;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * The wave 4 flaky failure: when the migration fails and the cleanup fails too, the error must
     * name the migration's own failure, with the cleanup failures attached, not replaced by them.
     */
    @Test
    void aFailedCleanupKeepsTheMigrationFailureAndIsAttachedAsSuppressed() throws Exception {
        String url = Sqlite.jdbcUrl(dir.resolve("e.db"));
        SQLException commit = new SQLException("commit refused");
        SQLException rollback = new SQLException("cannot rollback - no transaction is active");
        SQLException autoCommit = new SQLException("cannot commit - no transaction is active");
        List<String> calls = new ArrayList<>();
        try (Connection real = Sqlite.connect(url)) {
            Connection failing =
                    (Connection)
                            Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    (proxy, method, args) -> {
                                        String call =
                                                method.getName()
                                                        + (args == null
                                                                ? ""
                                                                : Arrays.toString(args));
                                        calls.add(call);
                                        switch (call) {
                                            case "commit" -> throw commit;
                                            case "rollback" -> throw rollback;
                                            case "setAutoCommit[true]" -> throw autoCommit;
                                            default -> {
                                                try {
                                                    return method.invoke(real, args);
                                                } catch (InvocationTargetException e) {
                                                    throw e.getCause();
                                                }
                                            }
                                        }
                                    });

            IllegalStateException e =
                    assertThrows(
                            IllegalStateException.class,
                            () -> SchemaRunner.migrate(failing, FIXTURES));

            assertEquals("migration V1 failed: commit refused", e.getMessage());
            assertSame(commit, e.getCause());
            assertEquals(List.of(rollback, autoCommit), List.of(e.getSuppressed()));
            assertEquals(
                    List.of("setAutoCommit[false]", "commit", "rollback", "setAutoCommit[true]"),
                    calls.stream()
                            .filter(c -> !c.startsWith("create"))
                            .filter(c -> !c.startsWith("prepare"))
                            .toList());
        }
    }

    @Test
    void splitterRespectsQuotesAndComments() {
        assertEquals(
                List.of("SELECT 'a;b'", "SELECT 2"),
                SchemaRunner.split("-- c; x\nSELECT 'a;b'; /* y; */ SELECT 2;\n"));
    }
}
