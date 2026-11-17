package dev.thiru.bms.features.admin;

import dev.thiru.bms.kernel.account.CinemaScope;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.kernel.catalog.Certificate;
import dev.thiru.bms.kernel.catalog.Cinema;
import dev.thiru.bms.kernel.catalog.City;
import dev.thiru.bms.kernel.catalog.Movie;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import dev.thiru.bms.ports.Db;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The reads the admin use cases share, the cinema link lookup behind {@link CinemaScope}, and the
 * JSON columns they write. Every method runs on the caller's open transaction.
 */
final class AdminSql {

    private AdminSql() {}

    static boolean exists(Db.Tx tx, String sql, Object... args) {
        return tx.queryOne(sql, rs -> rs.getInt(1), args).isPresent();
    }

    // ---- scope ----

    /** Throws NOT_FOUND naming {@code what} when the cinema is outside {@code scope}. */
    static void require(Db.Tx tx, CinemaScope scope, long cinemaId, String what) {
        scope.require((user, cinema) -> owns(tx, user, cinema), cinemaId, what);
    }

    static boolean owns(Db.Tx tx, long userId, long cinemaId) {
        return exists(
                tx,
                "SELECT 1 FROM cinema_owner WHERE user_id = ? AND cinema_id = ?",
                userId,
                cinemaId);
    }

    /** Ids of the cinemas linked to the user, ascending. */
    static List<Long> cinemaIdsOf(Db.Tx tx, long userId) {
        return tx.query(
                "SELECT cinema_id FROM cinema_owner WHERE user_id = ? ORDER BY cinema_id",
                rs -> rs.getLong(1),
                userId);
    }

    // ---- catalog reads ----

    static Optional<City> city(Db.Tx tx, String id) {
        return tx.queryOne(
                "SELECT id, name, time_zone FROM city WHERE id = ?",
                rs ->
                        new City(
                                rs.getString("id"),
                                rs.getString("name"),
                                ZoneId.of(rs.getString("time_zone"))),
                id);
    }

    private static final String CINEMA_COLS = "id, city_id, name, address";

    private static final Db.Row<Cinema> CINEMA =
            rs ->
                    new Cinema(
                            rs.getLong("id"),
                            rs.getString("city_id"),
                            rs.getString("name"),
                            rs.getString("address"));

    static Optional<Cinema> cinema(Db.Tx tx, long id) {
        return tx.queryOne("SELECT " + CINEMA_COLS + " FROM cinema WHERE id = ?", CINEMA, id);
    }

    /** Cinemas linked to the owner of {@code scope} by name; every cinema for an admin. */
    static List<Cinema> cinemas(Db.Tx tx, CinemaScope scope) {
        return tx.query(
                "SELECT "
                        + CINEMA_COLS
                        + " FROM cinema WHERE ?1 IS NULL OR id IN"
                        + " (SELECT cinema_id FROM cinema_owner WHERE user_id = ?1)"
                        + " ORDER BY name, id",
                CINEMA,
                scope.ownerId());
    }

    static Optional<Screen> screen(Db.Tx tx, long id) {
        return tx.queryOne("SELECT id, cinema_id, name FROM screen WHERE id = ?", SCREEN_ROW, id)
                .map(row -> row.screen(tx));
    }

    /** The cinema's screens by name, layouts included. */
    static List<Screen> screensOf(Db.Tx tx, long cinemaId) {
        return tx
                .query(
                        "SELECT id, cinema_id, name FROM screen WHERE cinema_id = ?"
                                + " ORDER BY name, id",
                        SCREEN_ROW,
                        cinemaId)
                .stream()
                .map(row -> row.screen(tx))
                .toList();
    }

    private record ScreenRow(long id, long cinemaId, String name) {
        Screen screen(Db.Tx tx) {
            return new Screen(id, cinemaId, name, layout(tx, id));
        }
    }

    private static final Db.Row<ScreenRow> SCREEN_ROW =
            rs -> new ScreenRow(rs.getLong("id"), rs.getLong("cinema_id"), rs.getString("name"));

    /** The screen's {@code layout_json}, read element by element. */
    static SeatLayout layout(Db.Tx tx, long screenId) {
        List<SeatLayout.Category> categories =
                tx.query(
                        "SELECT json_extract(c.value, '$.code') AS code,"
                                + " json_extract(c.value, '$.name') AS name"
                                + " FROM screen sc, json_each(sc.layout_json, '$.categories') c"
                                + " WHERE sc.id = ? ORDER BY c.key",
                        rs -> new SeatLayout.Category(rs.getString("code"), rs.getString("name")),
                        screenId);
        List<SeatLayout.Row> rows =
                tx.query(
                        "SELECT json_extract(r.value, '$.row') AS row,"
                                + " json_extract(r.value, '$.seats') AS seats,"
                                + " json_extract(r.value, '$.category') AS category,"
                                + " json_extract(r.value, '$.aisleAfter') AS aisle_after,"
                                + " json_extract(r.value, '$.blocked') AS blocked"
                                + " FROM screen sc, json_each(sc.layout_json, '$.rows') r"
                                + " WHERE sc.id = ? ORDER BY r.key",
                        rs ->
                                new SeatLayout.Row(
                                        rs.getString("row"),
                                        rs.getInt("seats"),
                                        rs.getString("category"),
                                        ints(rs.getString("aisle_after")),
                                        ints(rs.getString("blocked"))),
                        screenId);
        return new SeatLayout(categories, rows);
    }

    /** A JSON array of integers as SQLite renders it, such as {@code [4,12]}. */
    static List<Integer> ints(String json) {
        if (json == null) {
            return List.of();
        }
        String body = json.strip();
        body = body.substring(1, body.length() - 1).strip();
        if (body.isEmpty()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (String n : body.split(",")) {
            out.add(Integer.parseInt(n.strip()));
        }
        return out;
    }

    static Optional<Movie> movie(Db.Tx tx, long id) {
        List<String> languages = words(tx, "movie_language", "language", id);
        List<String> genres = words(tx, "movie_genre", "genre", id);
        return tx.queryOne(
                "SELECT id, title, certificate, duration_minutes, release_date, poster_url,"
                        + " description FROM movie WHERE id = ?",
                rs ->
                        new Movie(
                                rs.getLong("id"),
                                rs.getString("title"),
                                languages,
                                genres,
                                Certificate.valueOf(rs.getString("certificate")),
                                rs.getInt("duration_minutes"),
                                LocalDate.parse(rs.getString("release_date")),
                                rs.getString("poster_url"),
                                rs.getString("description")),
                id);
    }

    private static List<String> words(Db.Tx tx, String table, String column, long movieId) {
        return tx.query(
                "SELECT " + column + " FROM " + table + " WHERE movie_id = ? ORDER BY " + column,
                rs -> rs.getString(1),
                movieId);
    }

    private static final String SHOW_COLS =
            "id, movie_id, screen_id, starts_at, ends_at, language, format, status";

    /**
     * A show without its prices: the admin rules read only its slot, screen and status, and every
     * write states the prices it saves.
     */
    private static final Db.Row<Show> SHOW =
            rs ->
                    new Show(
                            rs.getLong("id"),
                            rs.getLong("movie_id"),
                            rs.getLong("screen_id"),
                            Db.instant(rs, "starts_at"),
                            Db.instant(rs, "ends_at"),
                            rs.getString("language"),
                            ShowFormat.parse(rs.getString("format")),
                            Map.of(),
                            ShowStatus.valueOf(rs.getString("status")));

    static Optional<Show> show(Db.Tx tx, long id) {
        return tx.queryOne("SELECT " + SHOW_COLS + " FROM show WHERE id = ?", SHOW, id);
    }

    static List<Show> showsOnScreen(Db.Tx tx, long screenId) {
        return tx.query(
                "SELECT " + SHOW_COLS + " FROM show WHERE screen_id = ? ORDER BY starts_at, id",
                SHOW,
                screenId);
    }

    /** The cinema of the show's screen. */
    static long cinemaOfShow(Db.Tx tx, Show show) {
        return tx.queryOne(
                        "SELECT cinema_id FROM screen WHERE id = ?",
                        rs -> rs.getLong(1),
                        show.screenId())
                .orElseThrow();
    }

    // ---- users ----

    static final String USER_COLS = "id, email, password_hash, name, roles, created_at";

    static final Db.Row<User> USER =
            rs ->
                    new User(
                            rs.getLong("id"),
                            rs.getString("email"),
                            rs.getString("password_hash"),
                            rs.getString("name"),
                            Arrays.stream(rs.getString("roles").split(","))
                                    .map(Role::valueOf)
                                    .collect(Collectors.toSet()),
                            Db.instant(rs, "created_at"));

    static Optional<User> user(Db.Tx tx, long id) {
        return tx.queryOne("SELECT " + USER_COLS + " FROM app_user WHERE id = ?", USER, id);
    }

    // ---- JSON columns, byte for byte as the legacy adapter writes them ----

    /** The SPEC 3 layout shape that {@link #layout} reads back. */
    static String layoutJson(SeatLayout layout) {
        StringBuilder sb = new StringBuilder("{\"categories\":[");
        for (int i = 0; i < layout.categories().size(); i++) {
            SeatLayout.Category c = layout.categories().get(i);
            sb.append(i == 0 ? "" : ",").append("{\"code\":");
            quote(sb, c.code()).append(",\"name\":");
            quote(sb, c.name()).append('}');
        }
        sb.append("],\"rows\":[");
        for (int i = 0; i < layout.rows().size(); i++) {
            SeatLayout.Row r = layout.rows().get(i);
            sb.append(i == 0 ? "" : ",").append("{\"row\":");
            quote(sb, r.row()).append(",\"seats\":").append(r.seats()).append(",\"category\":");
            quote(sb, r.category()).append(",\"aisleAfter\":");
            ints(sb, r.aisleAfter()).append(",\"blocked\":");
            ints(sb, r.blocked()).append('}');
        }
        return sb.append("]}").toString();
    }

    /** Prices by category code, sorted. */
    static String pricesJson(Map<String, Long> prices) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> e : new TreeMap<>(prices).entrySet()) {
            sb.append(first ? "" : ",");
            first = false;
            quote(sb, e.getKey()).append(':').append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private static StringBuilder ints(StringBuilder sb, List<Integer> values) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(values.get(i));
        }
        return sb.append(']');
    }

    private static StringBuilder quote(StringBuilder sb, String str) {
        sb.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"');
    }
}
