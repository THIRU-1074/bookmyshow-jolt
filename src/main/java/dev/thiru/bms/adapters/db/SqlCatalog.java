package dev.thiru.bms.adapters.db;

import dev.thiru.bms.domain.Certificate;
import dev.thiru.bms.domain.Cinema;
import dev.thiru.bms.domain.City;
import dev.thiru.bms.domain.Movie;
import dev.thiru.bms.domain.Page;
import dev.thiru.bms.domain.Screen;
import dev.thiru.bms.domain.SeatLayout;
import dev.thiru.bms.domain.Show;
import dev.thiru.bms.domain.ShowFormat;
import dev.thiru.bms.domain.ShowStatus;
import dev.thiru.bms.domain.port.CatalogRepositories;
import in.jolt.db.RowMapper;
import in.jolt.db.Sql;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Catalog repositories. Pages are 1-based, matching {@link Page} and the REST API. */
final class SqlCatalog {

    private SqlCatalog() {}

    private static final RowMapper<City> CITY =
            rs -> City.of(rs.getString("id"), rs.getString("name"), rs.getString("time_zone"));

    private static final RowMapper<Cinema> CINEMA =
            rs ->
                    new Cinema(
                            rs.getLong("id"),
                            rs.getString("city_id"),
                            rs.getString("name"),
                            rs.getString("address"));

    private static final String CINEMA_COLS = "id, city_id, name, address";

    private static final RowMapper<Screen> SCREEN =
            rs ->
                    new Screen(
                            rs.getLong("id"),
                            rs.getLong("cinema_id"),
                            rs.getString("name"),
                            layout(rs.getString("layout_json")));

    private static final String SCREEN_COLS = "id, cinema_id, name, layout_json";

    private static final String SHOW_COLS =
            "id, movie_id, screen_id, starts_at, ends_at, language, format, prices_json, status";

    private static final RowMapper<Show> SHOW =
            rs -> {
                Map<String, Long> prices = new TreeMap<>();
                Json.object(Json.parse(rs.getString("prices_json")))
                        .forEach((k, v) -> prices.put(k, ((Number) v).longValue()));
                return new Show(
                        rs.getLong("id"),
                        rs.getLong("movie_id"),
                        rs.getLong("screen_id"),
                        Sqls.instant(rs, "starts_at"),
                        Sqls.instant(rs, "ends_at"),
                        rs.getString("language"),
                        ShowFormat.parse(rs.getString("format")),
                        prices,
                        ShowStatus.valueOf(rs.getString("status")));
            };

    static SeatLayout layout(String json) {
        Map<String, Object> root = Json.object(Json.parse(json));
        List<SeatLayout.Category> categories = new ArrayList<>();
        for (Object o : Json.array(root.get("categories"))) {
            Map<String, Object> c = Json.object(o);
            categories.add(new SeatLayout.Category((String) c.get("code"), (String) c.get("name")));
        }
        List<SeatLayout.Row> rows = new ArrayList<>();
        for (Object o : Json.array(root.get("rows"))) {
            Map<String, Object> r = Json.object(o);
            rows.add(
                    new SeatLayout.Row(
                            (String) r.get("row"),
                            ((Number) r.get("seats")).intValue(),
                            (String) r.get("category"),
                            ints(r.get("aisleAfter")),
                            ints(r.get("blocked"))));
        }
        return new SeatLayout(categories, rows);
    }

    private static List<Integer> ints(Object o) {
        if (o == null) {
            return List.of();
        }
        return Json.array(o).stream().map(n -> ((Number) n).intValue()).toList();
    }

    private static long offset(int page, int size) {
        return (long) Math.max(page - 1, 0) * size;
    }

    static final class Cities implements CatalogRepositories.Cities {
        private final Sql sql;

        Cities(Sql sql) {
            this.sql = sql;
        }

        @Override
        public List<City> findAll() {
            return sql.query("SELECT id, name, time_zone FROM city ORDER BY name", CITY);
        }

        @Override
        public Optional<City> findById(String id) {
            return sql.queryOne("SELECT id, name, time_zone FROM city WHERE id = ?", CITY, id);
        }
    }

    static final class Cinemas implements CatalogRepositories.Cinemas {
        private final Sql sql;

        Cinemas(Sql sql) {
            this.sql = sql;
        }

        @Override
        public Optional<Cinema> findById(long id) {
            return sql.queryOne("SELECT " + CINEMA_COLS + " FROM cinema WHERE id = ?", CINEMA, id);
        }

        @Override
        public Page<Cinema> page(String cityId, int page, int size) {
            // A null city lists every cinema; the optional filter keeps one statement.
            long total =
                    sql.queryOne(
                                    "SELECT count(*) FROM cinema WHERE (?1 IS NULL OR city_id = ?1)",
                                    rs -> rs.getLong(1),
                                    cityId)
                            .orElse(0L);
            List<Cinema> items =
                    sql.query(
                            "SELECT "
                                    + CINEMA_COLS
                                    + " FROM cinema WHERE (?1 IS NULL OR city_id = ?1)"
                                    + " ORDER BY name, id LIMIT ?2 OFFSET ?3",
                            CINEMA,
                            cityId,
                            size,
                            offset(page, size));
            return new Page<>(items, page, size, total);
        }
    }

    static final class Screens implements CatalogRepositories.Screens {
        private final Sql sql;

        Screens(Sql sql) {
            this.sql = sql;
        }

        @Override
        public Optional<Screen> findById(long id) {
            return sql.queryOne("SELECT " + SCREEN_COLS + " FROM screen WHERE id = ?", SCREEN, id);
        }

        @Override
        public List<Screen> findByCinema(long cinemaId) {
            return sql.query(
                    "SELECT " + SCREEN_COLS + " FROM screen WHERE cinema_id = ? ORDER BY name, id",
                    SCREEN,
                    cinemaId);
        }
    }

    static final class Movies implements CatalogRepositories.Movies {
        private final Sql sql;

        Movies(Sql sql) {
            this.sql = sql;
        }

        private record Raw(
                long id,
                String title,
                Certificate certificate,
                int duration,
                LocalDate release,
                String poster,
                String description) {}

        private static final RowMapper<Raw> RAW =
                rs ->
                        new Raw(
                                rs.getLong("id"),
                                rs.getString("title"),
                                Certificate.valueOf(rs.getString("certificate")),
                                rs.getInt("duration_minutes"),
                                LocalDate.parse(rs.getString("release_date")),
                                rs.getString("poster_url"),
                                rs.getString("description"));

        @Override
        public Optional<Movie> findById(long id) {
            List<Raw> rows =
                    sql.query(
                            "SELECT id, title, certificate, duration_minutes, release_date,"
                                    + " poster_url, description FROM movie WHERE id = ?",
                            RAW,
                            id);
            return load(rows).stream().findFirst();
        }

        @Override
        public Page<Movie> search(CatalogRepositories.MovieQuery q, int page, int size) {
            StringBuilder where = new StringBuilder(" WHERE 1 = 1");
            List<Object> params = new ArrayList<>();
            if (q.q() != null && !q.q().isBlank()) {
                where.append(" AND lower(m.title) LIKE ? ESCAPE '\\'");
                params.add(
                        "%"
                                + q.q().toLowerCase(Locale.ROOT)
                                        .replace("\\", "\\\\")
                                        .replace("%", "\\%")
                                        .replace("_", "\\_")
                                + "%");
            }
            if (!q.genres().isEmpty()) {
                where.append(
                        " AND EXISTS (SELECT 1 FROM movie_genre g WHERE g.movie_id = m.id"
                                + " AND g.genre IN (");
                where.append(Sqls.marks(q.genres().size())).append("))");
                params.addAll(q.genres());
            }
            if (!q.languages().isEmpty()) {
                where.append(
                        " AND EXISTS (SELECT 1 FROM movie_language l WHERE l.movie_id = m.id"
                                + " AND l.language IN (");
                where.append(Sqls.marks(q.languages().size())).append("))");
                params.addAll(q.languages());
            }
            if (!q.certificates().isEmpty()) {
                where.append(" AND m.certificate IN (")
                        .append(Sqls.marks(q.certificates().size()))
                        .append(')');
                for (Certificate c : q.certificates()) {
                    params.add(c.name());
                }
            }
            boolean byCity = q.cityId() != null;
            if (byCity || !q.formats().isEmpty()) {
                where.append(
                        " AND EXISTS (SELECT 1 FROM show s JOIN screen sc ON sc.id = s.screen_id"
                                + " JOIN cinema c ON c.id = sc.cinema_id"
                                + " WHERE s.movie_id = m.id AND s.status = 'SCHEDULED'");
                if (byCity) {
                    where.append(" AND c.city_id = ?");
                    params.add(q.cityId());
                    if (q.from() != null) {
                        where.append(" AND s.starts_at >= ?");
                        params.add(Sqls.ms(q.from()));
                    }
                    if (q.to() != null) {
                        where.append(" AND s.starts_at < ?");
                        params.add(Sqls.ms(q.to()));
                    }
                }
                if (!q.formats().isEmpty()) {
                    where.append(" AND s.format IN (")
                            .append(Sqls.marks(q.formats().size()))
                            .append(')');
                    for (ShowFormat f : q.formats()) {
                        params.add(f.label());
                    }
                }
                where.append(')');
            }
            long total =
                    sql.queryOne(
                                    "SELECT count(*) FROM movie m" + where,
                                    rs -> rs.getLong(1),
                                    params.toArray())
                            .orElse(0L);
            String order =
                    q.sort() == CatalogRepositories.MovieQuery.Sort.TITLE
                            ? " ORDER BY lower(m.title), m.id"
                            : " ORDER BY m.release_date DESC, lower(m.title), m.id";
            params.add(size);
            params.add(offset(page, size));
            List<Raw> rows =
                    sql.query(
                            "SELECT m.id, m.title, m.certificate, m.duration_minutes,"
                                    + " m.release_date, m.poster_url, m.description FROM movie m"
                                    + where
                                    + order
                                    + " LIMIT ? OFFSET ?",
                            RAW,
                            params.toArray());
            return new Page<>(load(rows), page, size, total);
        }

        /** Attaches languages and genres with one query each for the whole batch. */
        private List<Movie> load(List<Raw> rows) {
            if (rows.isEmpty()) {
                return List.of();
            }
            Object[] ids = rows.stream().map(Raw::id).toArray();
            String in = Sqls.marks(ids.length);
            Map<Long, List<String>> languages = tags("movie_language", "language", in, ids);
            Map<Long, List<String>> genres = tags("movie_genre", "genre", in, ids);
            List<Movie> out = new ArrayList<>();
            for (Raw r : rows) {
                out.add(
                        new Movie(
                                r.id(),
                                r.title(),
                                languages.getOrDefault(r.id(), List.of()),
                                genres.getOrDefault(r.id(), List.of()),
                                r.certificate(),
                                r.duration(),
                                r.release(),
                                r.poster(),
                                r.description()));
            }
            return out;
        }

        private Map<Long, List<String>> tags(String table, String col, String in, Object[] ids) {
            Map<Long, List<String>> out = new LinkedHashMap<>();
            sql.query(
                    "SELECT movie_id, "
                            + col
                            + " FROM "
                            + table
                            + " WHERE movie_id IN ("
                            + in
                            + ") ORDER BY movie_id, "
                            + col,
                    rs -> {
                        out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                                .add(rs.getString(2));
                        return null;
                    },
                    ids);
            return out;
        }
    }

    static final class Shows implements CatalogRepositories.Shows {
        private final Sql sql;

        Shows(Sql sql) {
            this.sql = sql;
        }

        @Override
        public Optional<Show> findById(long id) {
            return sql.queryOne("SELECT " + SHOW_COLS + " FROM show WHERE id = ?", SHOW, id);
        }

        @Override
        public Show save(Show show) {
            String prices = Json.write(new TreeMap<>(show.prices()));
            if (show.id() > 0) {
                int n =
                        sql.update(
                                "UPDATE show SET movie_id = ?, screen_id = ?, starts_at = ?,"
                                        + " ends_at = ?, language = ?, format = ?, prices_json = ?,"
                                        + " status = ? WHERE id = ?",
                                show.movieId(),
                                show.screenId(),
                                Sqls.ms(show.startsAt()),
                                Sqls.ms(show.endsAt()),
                                show.language(),
                                show.format().label(),
                                prices,
                                show.status().name(),
                                show.id());
                if (n == 1) {
                    return show;
                }
            }
            // Id 0 means "new": SQLite assigns the key; an explicit id is its own rowid.
            long id =
                    sql.insert(
                                    "INSERT INTO show (id, movie_id, screen_id, starts_at,"
                                            + " ends_at, language, format, prices_json, status)"
                                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    show.id() > 0 ? (Object) show.id() : null,
                                    show.movieId(),
                                    show.screenId(),
                                    Sqls.ms(show.startsAt()),
                                    Sqls.ms(show.endsAt()),
                                    show.language(),
                                    show.format().label(),
                                    prices,
                                    show.status().name())
                            .orElseThrow();
            return new Show(
                    id,
                    show.movieId(),
                    show.screenId(),
                    show.startsAt(),
                    show.endsAt(),
                    show.language(),
                    show.format(),
                    show.prices(),
                    show.status());
        }

        @Override
        public List<CatalogRepositories.ShowListing> findListings(
                long movieId,
                String cityId,
                Instant from,
                Instant to,
                Set<String> languages,
                Set<ShowFormat> formats) {
            StringBuilder q =
                    new StringBuilder(
                            "SELECT s.id, s.movie_id, s.screen_id, s.starts_at, s.ends_at,"
                                    + " s.language, s.format, s.prices_json, s.status,"
                                    + " c.id AS c_id, c.city_id AS c_city_id, c.name AS c_name,"
                                    + " c.address AS c_address, sc.id AS sc_id,"
                                    + " sc.cinema_id AS sc_cinema_id, sc.name AS sc_name,"
                                    + " sc.layout_json AS sc_layout"
                                    + " FROM show s JOIN screen sc ON sc.id = s.screen_id"
                                    + " JOIN cinema c ON c.id = sc.cinema_id"
                                    + " WHERE s.movie_id = ? AND s.status = 'SCHEDULED'"
                                    + " AND c.city_id = ? AND s.starts_at >= ? AND s.starts_at < ?");
            List<Object> params = new ArrayList<>();
            params.add(movieId);
            params.add(cityId);
            params.add(Sqls.ms(from));
            params.add(Sqls.ms(to));
            if (!languages.isEmpty()) {
                q.append(" AND s.language IN (").append(Sqls.marks(languages.size())).append(')');
                params.addAll(languages);
            }
            if (!formats.isEmpty()) {
                q.append(" AND s.format IN (").append(Sqls.marks(formats.size())).append(')');
                for (ShowFormat f : formats) {
                    params.add(f.label());
                }
            }
            q.append(" ORDER BY c.id, s.starts_at, s.id");
            return sql.query(
                    q.toString(),
                    rs ->
                            new CatalogRepositories.ShowListing(
                                    SHOW.map(rs),
                                    new Cinema(
                                            rs.getLong("c_id"),
                                            rs.getString("c_city_id"),
                                            rs.getString("c_name"),
                                            rs.getString("c_address")),
                                    new Screen(
                                            rs.getLong("sc_id"),
                                            rs.getLong("sc_cinema_id"),
                                            rs.getString("sc_name"),
                                            layout(rs.getString("sc_layout")))),
                    params.toArray());
        }

        @Override
        public List<Show> findByScreen(long screenId) {
            return sql.query(
                    "SELECT " + SHOW_COLS + " FROM show WHERE screen_id = ? ORDER BY starts_at, id",
                    SHOW,
                    screenId);
        }
    }
}
