package dev.thiru.bms.web;

import dev.thiru.bms.application.CatalogQueries;
import dev.thiru.bms.domain.port.CatalogRepositories.MovieQuery;
import dev.thiru.bms.kernel.catalog.Certificate;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import in.jolt.Page;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.RouteModule;
import in.jolt.Router;
import java.util.Locale;

/**
 * Public catalog and seat map (SPEC 7, catalog and seats). Cities, the movie list and show listings
 * are served from {@link CatalogCache}.
 */
final class CatalogModule implements RouteModule {

    private static final String PUBLIC_CACHE = "public, max-age=60";

    private final CatalogQueries catalog;
    private final CatalogCache cache;

    CatalogModule(CatalogQueries catalog, CatalogCache cache) {
        this.catalog = catalog;
        this.cache = cache;
    }

    @Override
    public void register(Router r) {
        r.get(
                "/api/cities",
                (req, res) ->
                        cached(
                                res,
                                cache.get(
                                        req,
                                        () ->
                                                catalog.cities().stream()
                                                        .map(Dtos.CityDto::of)
                                                        .toList())));
        r.get("/api/movies", (req, res) -> cached(res, cache.get(req, () -> movies(req))));
        r.get(
                "/api/movies/:id",
                (req, res) -> cached(res, Dtos.MovieDetail.of(catalog.movie(Query.id(req, "id")))));
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
                                    Dtos.CinemaDto::of));
                });
        r.get(
                "/api/cinemas/:id",
                (req, res) ->
                        cached(res, Dtos.CinemaDetailDto.of(catalog.cinema(Query.id(req, "id")))));
        r.get(
                "/api/shows/:id",
                (req, res) ->
                        cached(res, Dtos.ShowDetailDto.of(catalog.show(Query.id(req, "id")))));
        r.get(
                "/api/shows/:id/seats",
                (req, res) -> {
                    // Seat state changes every few seconds: revalidate on every use.
                    res.json(Dtos.SeatMapDto.of(catalog.seatMap(Query.id(req, "id")))).etag();
                    res.header("Cache-Control", "no-cache");
                });
    }

    private Object movies(Request req) {
        Page page = Paging.request(req);
        CatalogQueries.MovieFilter filter =
                new CatalogQueries.MovieFilter(
                        Query.text(req, "city"),
                        Query.date(req, "date"),
                        Query.text(req, "q"),
                        Query.list(req, "genre"),
                        Query.list(req, "language"),
                        Query.list(
                                req,
                                "certificate",
                                s -> Certificate.valueOf(s.toUpperCase(Locale.ROOT))),
                        Query.list(req, "format", CatalogModule::format),
                        sort(Query.text(req, "sort")));
        return Paging.result(
                catalog.movies(filter, page.page(), page.size()), Dtos.MovieSummary::of);
    }

    private Object showTimes(Request req) {
        String city = Query.text(req, "city");
        if (city == null) {
            throw ApiException.validation("city", "is required");
        }
        return Dtos.ShowTimesDto.of(
                catalog.showTimes(
                        Query.id(req, "id"),
                        city,
                        Query.date(req, "date"),
                        Query.list(req, "language"),
                        Query.list(req, "format", CatalogModule::format)));
    }

    private static ShowFormat format(String s) {
        return ShowFormat.parse(s.toUpperCase(Locale.ROOT));
    }

    private static MovieQuery.Sort sort(String raw) {
        if (raw == null) {
            return MovieQuery.Sort.RELEASE;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "release" -> MovieQuery.Sort.RELEASE;
            case "title" -> MovieQuery.Sort.TITLE;
            default -> throw ApiException.validation("sort", "must be release or title");
        };
    }

    /** Public catalog reads: weak ETag (304 on a match) and a one-minute shared cache. */
    private static void cached(Response res, Object body) {
        res.json(body).etag();
        res.header("Cache-Control", PUBLIC_CACHE);
    }
}
