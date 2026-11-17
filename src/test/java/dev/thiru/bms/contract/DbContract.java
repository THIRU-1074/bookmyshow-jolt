package dev.thiru.bms.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.ports.Db;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The transaction seam the feature slices run their SQL through (docs/restructure/PLAN.md): commit
 * on return, roll back on a throw with the work's own exception, writers serialised (SPEC 5.2),
 * unique clashes as {@link Db.ConstraintViolation}, and {@code afterCommit} callbacks that run only
 * after a commit, in order, without being able to undo it. Subclasses hand over an empty database.
 */
public abstract class DbContract {

    private static final Db.Row<String> NAME = rs -> rs.getString("name");
    private static final Db.Row<Long> N = rs -> rs.getLong("n");

    private Db db;

    protected abstract Db newDb();

    @BeforeEach
    protected void createTable() {
        db = newDb();
        db.inTransaction(
                tx ->
                        tx.update(
                                "CREATE TABLE item (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                        + " name TEXT NOT NULL UNIQUE, n INTEGER NOT NULL)"));
    }

    private List<String> names() {
        return db.inTransaction(tx -> tx.query("SELECT name FROM item ORDER BY id", NAME));
    }

    private int insert(Db.Tx tx, String name, long n) {
        return tx.update("INSERT INTO item (name, n) VALUES (?, ?)", name, n);
    }

    @Test
    protected void workCommitsWhenItReturnsAndItsValueIsReturned() {
        String out = db.inTransaction(tx -> insert(tx, "a", 1) + "!");
        assertEquals("1!", out);
        assertEquals(List.of("a"), names());
    }

    @Test
    protected void aThrowingWorkRollsBackAndItsExceptionPropagatesUnchanged() {
        IllegalStateException boom = new IllegalStateException("boom");
        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                db.inTransaction(
                                        tx -> {
                                            insert(tx, "a", 1);
                                            throw boom;
                                        }));
        assertSame(boom, thrown);
        assertEquals(List.of(), names());
    }

    @Test
    protected void rowsMapThroughJdkMappersInQueryOrder() {
        db.inTransaction(tx -> insert(tx, "b", 2) + insert(tx, "a", 1) + insert(tx, "c", 3));
        assertEquals(
                List.of("a", "b", "c"),
                db.inTransaction(tx -> tx.query("SELECT name FROM item ORDER BY n", NAME)));
        assertEquals(
                Optional.of(2L),
                db.inTransaction(tx -> tx.queryOne("SELECT n FROM item WHERE name = ?", N, "b")));
        assertEquals(
                Optional.empty(),
                db.inTransaction(
                        tx -> tx.queryOne("SELECT name FROM item WHERE name = ?", NAME, "zz")));
    }

    @Test
    protected void insertReturnsTheGeneratedKey() {
        String sql = "INSERT INTO item (name, n) VALUES (?, ?)";
        long first = db.inTransaction(tx -> tx.insert(sql, "a", 1));
        long second = db.inTransaction(tx -> tx.insert(sql, "b", 2));
        assertTrue(second > first, second + " after " + first);
        assertEquals(
                Optional.of("b"),
                db.inTransaction(
                        tx -> tx.queryOne("SELECT name FROM item WHERE id = ?", NAME, second)));
    }

    @Test
    protected void batchAndUpdateReportTheRowsTheyChanged() {
        int[] counts =
                db.inTransaction(
                        tx ->
                                tx.batch(
                                        "INSERT INTO item (name, n) VALUES (?, ?)",
                                        List.of(
                                                new Object[] {"a", 1},
                                                new Object[] {"b", 1},
                                                new Object[] {"c", 2})));
        assertArrayEquals(new int[] {1, 1, 1}, counts);
        int updated = db.inTransaction(tx -> tx.update("UPDATE item SET n = 9 WHERE n = ?", 1));
        assertEquals(2, updated);
        int deleted = db.inTransaction(tx -> tx.update("DELETE FROM item WHERE n = ?", 7));
        assertEquals(0, deleted);
    }

    @Test
    protected void aUniqueClashIsAConstraintViolationAndTheTransactionGoesOn() {
        List<String> seen =
                db.inTransaction(
                        tx -> {
                            insert(tx, "a", 1);
                            assertThrows(Db.ConstraintViolation.class, () -> insert(tx, "a", 2));
                            assertThrows(
                                    Db.ConstraintViolation.class,
                                    () ->
                                            tx.batch(
                                                    "INSERT INTO item (name, n) VALUES (?, ?)",
                                                    List.<Object[]>of(new Object[] {"a", 3})));
                            // As the seat-lock conflict lookup does after a failed insert.
                            return tx.query("SELECT name FROM item", NAME);
                        });
        assertEquals(List.of("a"), seen);
        assertEquals(List.of("a"), names());
    }

    @Test
    protected void afterCommitRunsOnceAfterTheCommitInRegistrationOrder() {
        List<String> calls = new CopyOnWriteArrayList<>();
        db.inTransaction(
                tx -> {
                    insert(tx, "a", 1);
                    // Runs after commit, so a new transaction already sees the row.
                    tx.afterCommit(() -> calls.add("first " + names()));
                    tx.afterCommit(() -> calls.add("second"));
                    assertEquals(List.of(), calls);
                    return null;
                });
        assertEquals(List.of("first [a]", "second"), calls);
    }

    @Test
    protected void afterCommitNeverRunsWhenTheWorkThrows() {
        List<String> calls = new ArrayList<>();
        assertThrows(
                IllegalStateException.class,
                () ->
                        db.inTransaction(
                                tx -> {
                                    tx.afterCommit(() -> calls.add("published"));
                                    throw new IllegalStateException("rolled back");
                                }));
        assertEquals(List.of(), calls);
    }

    @Test
    protected void aFailingCallbackNeitherFailsTheCommitNorSkipsTheOthers() {
        List<String> calls = new ArrayList<>();
        String out =
                db.inTransaction(
                        tx -> {
                            insert(tx, "a", 1);
                            tx.afterCommit(
                                    () -> {
                                        throw new IllegalStateException("subscriber down");
                                    });
                            tx.afterCommit(() -> calls.add("still runs"));
                            return "done";
                        });
        assertEquals("done", out);
        assertEquals(List.of("still runs"), calls);
        assertEquals(List.of("a"), names());
    }

    @Test
    protected void aTxCannotBeUsedAfterItsTransactionEnds() {
        AtomicReference<Db.Tx> leaked = new AtomicReference<>();
        db.inTransaction(
                tx -> {
                    leaked.set(tx);
                    return null;
                });
        Db.Tx tx = leaked.get();
        assertThrows(IllegalStateException.class, () -> tx.query("SELECT name FROM item", NAME));
        assertThrows(IllegalStateException.class, () -> tx.update("DELETE FROM item"));
        assertThrows(IllegalStateException.class, () -> tx.afterCommit(() -> {}));
    }

    @Test
    protected void writersAreSerialisedSoNoReadModifyWriteIsLost() throws Exception {
        db.inTransaction(tx -> insert(tx, "counter", 0));
        String read = "SELECT n FROM item WHERE name = 'counter'";
        String write = "UPDATE item SET n = ? WHERE name = 'counter'";
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                done.add(
                        pool.submit(
                                () -> {
                                    for (int i = 0; i < 25; i++) {
                                        db.inTransaction(
                                                tx ->
                                                        tx.update(
                                                                write,
                                                                tx.queryOne(read, N).orElseThrow()
                                                                        + 1));
                                    }
                                }));
            }
            for (Future<?> f : done) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(Optional.of(100L), db.inTransaction(tx -> tx.queryOne(read, N)));
    }

    @Test
    protected void instantsAreStoredAsEpochMillis() {
        Instant at = Instant.parse("2026-11-19T10:00:00.123Z");
        assertEquals(at.toEpochMilli(), Db.millis(at));
        Instant back =
                db.inTransaction(
                        tx -> {
                            insert(tx, "a", Db.millis(at));
                            return tx.queryOne("SELECT n FROM item", rs -> Db.instant(rs, "n"))
                                    .orElseThrow();
                        });
        assertEquals(at, back);
        assertEquals("?,?,?", Db.marks(3));
        assertEquals("?", Db.marks(1));
    }
}
