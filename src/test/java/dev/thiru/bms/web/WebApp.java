package dev.thiru.bms.web;

import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.SliceKit;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * The whole web layer on {@link SliceKit}: the production pipeline with every route {@link
 * WebModules#routes} registers, on the kit's ports, the V2 seed and the mock gateway on (as in the
 * packaged app). Tests that need the full route table, not one slice, boot it here.
 */
final class WebApp {

    /** Whitefield Galaxy: screens 4 (SMALL) and 5 (LARGE). */
    static final long CINEMA_ID = 2;

    /** Screen 2 of cinema 2, LARGE: rows A to J of 20, A1 and J20 blocked. */
    static final long SCREEN_ID = 5;

    private WebApp() {}

    static SliceKit boot() {
        return boot(() -> true);
    }

    /** {@code dbUp} answers the health checks. */
    static SliceKit boot(BooleanSupplier dbUp) {
        return SliceKit.builder()
                .mockGateway(true)
                .mount(k -> WebModules.routes(k.pipeline(), deps(k, dbUp)))
                .boot();
    }

    private static WebDeps deps(SliceKit k, BooleanSupplier dbUp) {
        return new WebDeps(
                k.testDb().legacyUnitOfWork(),
                k.db(),
                k.clock(),
                k.policy(),
                k.ids(),
                k.hasher(),
                k.refreshTokens(),
                k.idempotency(),
                k.gateway(),
                k.events(),
                k.callbacks(),
                k.live(),
                dbUp,
                new WebDeps.Settings(
                        SliceKit.SECRET,
                        SliceKit.WEBHOOK_SECRET,
                        true,
                        Path.of("src/main/resources/views"),
                        Duration.ofSeconds(15)));
    }

    /** The first show on {@link #SCREEN_ID} one day after the seed date. */
    static long show(SliceKit kit) {
        Instant from = kit.seedDate().plusDays(1).atStartOfDay(SliceKit.IST).toInstant();
        return kit.db()
                .inTransaction(
                        tx ->
                                tx.queryOne(
                                                "SELECT id FROM show WHERE screen_id = ?"
                                                        + " AND starts_at >= ?"
                                                        + " ORDER BY starts_at LIMIT 1",
                                                rs -> rs.getLong(1),
                                                SCREEN_ID,
                                                Db.millis(from))
                                        .orElseThrow());
    }

    /** The movie {@code showId} screens. */
    static long movieOf(SliceKit kit, long showId) {
        return kit.db()
                .inTransaction(
                        tx ->
                                tx.queryOne(
                                                "SELECT movie_id FROM show WHERE id = ?",
                                                rs -> rs.getLong(1),
                                                showId)
                                        .orElseThrow());
    }
}
