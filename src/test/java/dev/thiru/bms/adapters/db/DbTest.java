package dev.thiru.bms.adapters.db;

import dev.thiru.bms.domain.Role;
import dev.thiru.bms.domain.User;
import dev.thiru.bms.domain.port.Repositories;
import in.jolt.db.Db;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** Base for contract tests: a temp-file SQLite migrated with V1 (schema) and V2 (seed). */
abstract class DbTest {

    static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    @TempDir Path dir;
    Db db;
    SqliteUnitOfWork uow;

    @BeforeEach
    void openDb() {
        String url = Sqlite.jdbcUrl(dir.resolve("bms.db"));
        SchemaRunner.migrate(url);
        // Pool larger than the concurrency tests need so waiting happens on the SQLite lock.
        db = Sqlite.open(url, 16, java.time.Duration.ofSeconds(30));
        uow = new SqliteUnitOfWork(db);
    }

    @AfterEach
    void closeDb() {
        db.close();
    }

    <T> T tx(Function<Repositories, T> work) {
        return uow.inTransaction(work);
    }

    boolean flag(Function<Repositories, Boolean> work) {
        return uow.inTransaction(work);
    }

    long newUser(String email) {
        return tx(r ->
                        r.users()
                                .insert(
                                        new User(
                                                0,
                                                email,
                                                "hash",
                                                "Test User",
                                                Set.of(Role.AUDIENCE),
                                                NOW)))
                .id();
    }

    /** First show of the seed that has not started, with a fixed id so tests are stable. */
    long anyShowId() {
        return db.queryOne("SELECT min(id) FROM show", r -> r.getLong(1)).orElseThrow();
    }
}
