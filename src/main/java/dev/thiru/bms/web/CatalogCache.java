package dev.thiru.bms.web;

import in.jolt.AppMetrics;
import in.jolt.Middleware;
import in.jolt.Request;
import in.jolt.TtlCache;
import java.time.Clock;
import java.time.Duration;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Server-side cache for the public catalog reads (cities, movies, show listings) on Jolt's {@link
 * TtlCache}, keyed by path and query. Entries live as long as the {@code Cache-Control: public,
 * max-age=60} those responses already allow browsers and proxies, so the cache adds no staleness
 * the API did not already permit. Admin and owner catalog writes clear it at once.
 *
 * <p>Failed loads (404, 422) are not cached. Seat holds and bookings do not clear it: a listing's
 * availability badge may lag by up to the same minute, while the seat map itself is never cached.
 */
public final class CatalogCache {

    static final Duration TTL = Duration.ofSeconds(60);

    /** Distinct query strings kept; the least recently used goes first. */
    static final int MAX_ENTRIES = 1000;

    private final TtlCache<String, Object> cache;

    CatalogCache(Clock clock) {
        this.cache = TtlCache.create(MAX_ENTRIES, TTL, clock);
    }

    /** The cached body for this request, loading it once on a miss. */
    public Object get(Request req, Supplier<Object> load) {
        return cache.get(key(req), k -> load.get());
    }

    /**
     * Path plus every query value, names sorted and values in order, each length-prefixed so no two
     * different queries share a key. Jolt does not expose the raw query string.
     */
    // TODO(jolt#87): key on req.rawQuery() once it is public.
    static String key(Request req) {
        StringBuilder key = new StringBuilder(req.path());
        for (String name : new TreeSet<>(req.query().keySet())) {
            key.append('|').append(name.length()).append(':').append(name);
            for (String value : req.queryAll(name)) {
                key.append(',').append(value.length()).append(':').append(value);
            }
        }
        return key.toString();
    }

    /**
     * Route middleware for {@code /api/admin} and {@code /api/owner}: a write that succeeded clears
     * the cache after its transaction committed. A failed write throws past it and changes nothing.
     */
    Middleware clearAfterWrites() {
        return (req, res, next) -> {
            next.run();
            if (!"GET".equals(req.method())
                    && !"HEAD".equals(req.method())
                    && res.statusCode() < 400) {
                cache.clear();
            }
        };
    }

    /** {@code bms_catalog_cache_hits_total}, {@code _misses_total}, {@code _evictions_total}. */
    void metrics(AppMetrics metrics) {
        cache.metrics(metrics, "bms_catalog_cache");
    }
}
