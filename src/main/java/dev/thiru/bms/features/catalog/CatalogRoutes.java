package dev.thiru.bms.features.catalog;

import dev.thiru.bms.features.catalog.CatalogSql.MovieFilter;
import dev.thiru.bms.features.catalog.CatalogSql.Sort;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.kernel.catalog.Certificate;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.web.ApiException;
import dev.thiru.bms.web.CatalogCache;
import dev.thiru.bms.web.Paging;
import dev.thiru.bms.web.Query;
import in.jolt.Page;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.RouteModule;
import in.jolt.Router;
import java.time.Clock;
import java.util.Locale;
import java.util.function.Function;

/**
 * Public catalog and seat map (SPEC 7, catalog and seats). Cities, the movie list and show listings
 * are served from the shared {@link CatalogCache}, which admin and owner writes clear.
 */
public final class CatalogRoutes implements RouteModule {

    private static final String PUBLIC_CACHE = "public, max-age=60";

    private final CatalogSql catalog;
    private final CatalogCache cache;

    public CatalogRoutes(Db db, Clock clock, BookingPolicy policy, CatalogCache cache) {
        this.catalog = new CatalogSql(db, clock, policy);
        this.cache = cache;
    }

    @Override
    public void register(Router r) {
        r.get("/api/cities", (req, res) -> cached(res, cache.get(req, catalog::cities)));
        r.get("/api/movies", (req, res) -> cached(res, cache.get(req, () -> movies(req))));
        r.get("/api/movies/:id", (req, res) -> cached(res, catalog.movie(Query.id(req, "id"))));
        r.get(
                "/api/movies/:id/shows",
                (req, res) -> cached(res, cache.get(req, () -> showTimes(req))));
        r.get(
                "/api/cinemas",
                (req, res) -> {
                    Page page = Paging.request(req);
                    cached(
                            res,
                            Paging.result(
                                    catalog.cinemas(
                                            Query.text(req, "city"), page.page(), page.size()),
                                    Function.identity()));
                });
        r.get("/api/cinemas/:id", (req, res) -> cached(res, catalog.cinema(Query.id(req, "id"))));
        r.get("/api/shows/:id", (req, res) -> cached(res, catalog.show(Query.id(req, "id"))));
        r.get(
                "/api/shows/:id/seats",
                (req, res) -> {
                    // Seat state changes every few seconds: revalidate on every use.
                    res.json(CatalogDtos.seatMap(catalog.seatMap(Query.id(req, "id")))).etag();
                    res.header("Cache-Control", "no-cache");
                });
    }

    private Object movies(Request req) {
        Page page = Paging.request(req);
        MovieFilter filter =
                new MovieFilter(
                        Query.text(req, "city"),
                        Query.date(req, "date"),
                        Query.text(req, "q"),
                        Query.list(req, "genre"),
                        Query.list(req, "language"),
                        Query.list(
                                req,
                                "certificate",
                                s -> Certificate.valueOf(s.toUpperCase(Locale.ROOT))),
                        Query.list(req, "format", CatalogRoutes::format),
                        sort(Query.text(req, "sort")));
        return Paging.result(catalog.movies(filter, page.page(), page.size()), Function.identity());
    }

    private Object showTimes(Request req) {
        String city = Query.text(req, "city");
        if (city == null) {
            throw ApiException.validation("city", "is required");
        }
        return catalog.showTimes(
                Query.id(req, "id"),
                city,
                Query.date(req, "date"),
                Query.list(req, "language"),
                Query.list(req, "format", CatalogRoutes::format));
    }

    private static ShowFormat format(String s) {
        return ShowFormat.parse(s.toUpperCase(Locale.ROOT));
    }

    private static Sort sort(String raw) {
        if (raw == null) {
            return Sort.RELEASE;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "release" -> Sort.RELEASE;
            case "title" -> Sort.TITLE;
            default -> throw ApiException.validation("sort", "must be release or title");
        };
    }

    /** Public catalog reads: weak ETag (304 on a match) and a one-minute shared cache. */
    private static void cached(Response res, Object body) {
        res.json(body).etag();
        res.header("Cache-Control", PUBLIC_CACHE);
    }
}
