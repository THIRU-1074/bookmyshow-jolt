package dev.thiru.bms.features.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.SliceKit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The cache stampede guarantee (SYSTEM_DESIGN 8) through the slice's real wiring: {@code
 * CatalogRoutes} on the pipeline's {@code CatalogCache}. 200 concurrent misses for one key must run
 * the loader once. The database handed to the routes counts the loads and holds each one open, so
 * the other requests arrive while the first is still loading.
 */
class CatalogStampedeTest {

    private static final int CLIENTS = 200;

    private final AtomicInteger loads = new AtomicInteger();

    @RegisterExtension
    final SliceKit kit =
            SliceKit.boot(
                    k ->
                            List.of(
                                    new CatalogRoutes(
                                            slowCounting(k.db()),
                                            k.clock(),
                                            k.policy(),
                                            k.pipeline().catalogCache())));

    private Db slowCounting(Db real) {
        return new Db() {
            @Override
            public <T> T inTransaction(Function<Tx, T> work) {
                loads.incrementAndGet();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return real.inTransaction(work);
            }
        };
    }

    @Test
    void twoHundredConcurrentMissesForOneKeyRunTheLoaderOnce() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CLIENTS; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    return kit.http()
                                            .get("/api/cities")
                                            .expect(200)
                                            .text()
                                            .length();
                                }));
            }
            go.countDown();
            pool.shutdown();
            assertEquals(true, pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        int length = results.get(0).get();
        for (Future<Integer> f : results) {
            assertEquals(length, f.get(), "every client got the same body");
        }
        assertEquals(1, loads.get(), "one database load for " + CLIENTS + " concurrent misses");

        kit.http().get("/api/cities").expect(200);
        assertEquals(1, loads.get(), "and the next read is a hit");
    }
}
