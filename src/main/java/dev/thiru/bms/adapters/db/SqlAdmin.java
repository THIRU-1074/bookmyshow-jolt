package dev.thiru.bms.adapters.db;

import dev.thiru.bms.domain.Cinema;
import dev.thiru.bms.domain.City;
import dev.thiru.bms.domain.Movie;
import dev.thiru.bms.domain.Page;
import dev.thiru.bms.domain.Role;
import dev.thiru.bms.domain.Screen;
import dev.thiru.bms.domain.SeatId;
import dev.thiru.bms.domain.SeatLayout;
import dev.thiru.bms.domain.Show;
import dev.thiru.bms.domain.ShowStatus;
import dev.thiru.bms.domain.User;
import dev.thiru.bms.domain.port.AdminRepositories;
import in.jolt.db.RowMapper;
import in.jolt.db.Sql;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin catalog writes, sales reports, cinema owner links and role changes over the connection of
 * one open transaction.
 */
final class SqlAdmin {

    private SqlAdmin() {}

    /** Serialises a layout in the SPEC 3 shape that {@link SqlCatalog#layout} reads back. */
    static String layoutJson(SeatLayout layout) {
        List<Object> categories = new ArrayList<>();
        for (SeatLayout.Category c : layout.categories()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.code());
            m.put("name", c.name());
            categories.add(m);
        }
        List<Object> rows = new ArrayList<>();
        for (SeatLayout.Row r : layout.rows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("row", r.row());
            m.put("seats", r.seats());
            m.put("category", r.category());
            m.put("aisleAfter", r.aisleAfter());
            m.put("blocked", r.blocked());
            rows.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("categories", categories);
        root.put("rows", rows);
        return Json.write(root);
    }

    private static long lastId(Sql sql) {
        return sql.queryOne("SELECT last_insert_rowid()", rs -> rs.getLong(1)).orElseThrow();
    }

    private static boolean exists(Sql sql, String query, Object... args) {
        return sql.queryOne(query, rs -> rs.getInt(1), args).isPresent();
    }

    static final class CatalogWrites implements AdminRepositories.CatalogWrites {
        private final Sql sql;

        CatalogWrites(Sql sql) {
            this.sql = sql;
        }

        @Override
        public City saveCity(City city) {
            sql.update(
                    "INSERT INTO city (id, name, time_zone) VALUES (?, ?, ?) ON CONFLICT (id)"
                            + " DO UPDATE SET name = excluded.name,"
                            + " time_zone = excluded.time_zone",
                    city.id(),
                    city.name(),
                    city.timeZone().getId());
            return city;
        }

        @Override
        public Cinema saveCinema(Cinema c) {
            if (c.id() > 0) {
                sql.update(
                        "UPDATE cinema SET city_id = ?, name = ?, address = ? WHERE id = ?",
                        c.cityId(),
                        c.name(),
                        c.address(),
                        c.id());
                return c;
            }
            sql.update(
                    "INSERT INTO cinema (city_id, name, address) VALUES (?, ?, ?)",
                    c.cityId(),
                    c.name(),
                    c.address());
            return new Cinema(lastId(sql), c.cityId(), c.name(), c.address());
        }

        @Override
        public boolean cinemaNameTaken(String cityId, String name, long exceptId) {
            return exists(
                    sql,
                    "SELECT 1 FROM cinema WHERE city_id = ? AND lower(name) = lower(?) AND id <> ?",
                    cityId,
                    name,
                    exceptId);
        }

        @Override
        public void deleteCinema(long id) {
            sql.update("DELETE FROM cinema WHERE id = ?", id);
        }

        @Override
        public boolean cinemaHasShows(long id) {
            return exists(
                    sql,
                    "SELECT 1 FROM show s JOIN screen sc ON sc.id = s.screen_id"
                            + " WHERE sc.cinema_id = ? LIMIT 1",
                    id);
        }

        @Override
        public Screen saveScreen(Screen s) {
            String layout = layoutJson(s.layout());
            if (s.id() > 0) {
                sql.update(
                        "UPDATE screen SET cinema_id = ?, name = ?, layout_json = ?, capacity = ?"
                                + " WHERE id = ?",
                        s.cinemaId(),
                        s.name(),
                        layout,
                        s.capacity(),
                        s.id());
                return s;
            }
            sql.update(
                    "INSERT INTO screen (cinema_id, name, layout_json, capacity)"
                            + " VALUES (?, ?, ?, ?)",
                    s.cinemaId(),
                    s.name(),
                    layout,
                    s.capacity());
            return new Screen(lastId(sql), s.cinemaId(), s.name(), s.layout());
        }

        @Override
        public boolean screenNameTaken(long cinemaId, String name, long exceptId) {
            return exists(
                    sql,
                    "SELECT 1 FROM screen WHERE cinema_id = ? AND lower(name) = lower(?)"
                            + " AND id <> ?",
                    cinemaId,
                    name,
                    exceptId);
        }

        @Override
        public void deleteScreen(long id) {
            sql.update("DELETE FROM screen WHERE id = ?", id);
        }

        @Override
        public boolean screenHasShows(long id) {
            return exists(sql, "SELECT 1 FROM show WHERE screen_id = ? LIMIT 1", id);
        }

        @Override
        public boolean screenHasFutureShows(long id, Instant now) {
            return exists(
                    sql,
                    "SELECT 1 FROM show WHERE screen_id = ? AND status = 'SCHEDULED'"
                            + " AND starts_at > ? LIMIT 1",
                    id,
                    Sqls.ms(now));
        }

        @Override
        public Movie saveMovie(Movie m) {
            long id;
            if (m.id() > 0) {
                sql.update(
                        "UPDATE movie SET title = ?, certificate = ?, duration_minutes = ?,"
                                + " release_date = ?, poster_url = ?, description = ? WHERE id = ?",
                        m.title(),
                        m.certificate().name(),
                        m.durationMinutes(),
                        m.releaseDate().toString(),
                        m.posterUrl(),
                        m.description(),
                        m.id());
                id = m.id();
                sql.update("DELETE FROM movie_language WHERE movie_id = ?", id);
                sql.update("DELETE FROM movie_genre WHERE movie_id = ?", id);
            } else {
                sql.update(
                        "INSERT INTO movie (title, certificate, duration_minutes, release_date,"
                                + " poster_url, description) VALUES (?, ?, ?, ?, ?, ?)",
                        m.title(),
                        m.certificate().name(),
                        m.durationMinutes(),
                        m.releaseDate().toString(),
                        m.posterUrl(),
                        m.description());
                id = lastId(sql);
            }
            for (String language : m.languages()) {
                sql.update(
                        "INSERT INTO movie_language (movie_id, language) VALUES (?, ?)",
                        id,
                        language);
            }
            for (String genre : m.genres()) {
                sql.update("INSERT INTO movie_genre (movie_id, genre) VALUES (?, ?)", id, genre);
            }
            return new Movie(
                    id,
                    m.title(),
                    m.languages(),
                    m.genres(),
                    m.certificate(),
                    m.durationMinutes(),
                    m.releaseDate(),
                    m.posterUrl(),
                    m.description());
        }

        @Override
        public void deleteMovie(long id) {
            // Languages and genres go with it (ON DELETE CASCADE).
            sql.update("DELETE FROM movie WHERE id = ?", id);
        }

        @Override
        public boolean movieHasShows(long id) {
            return exists(sql, "SELECT 1 FROM show WHERE movie_id = ? LIMIT 1", id);
        }

        @Override
        public Show insertShow(Show show) {
            return new SqlCatalog.Shows(sql).save(show);
        }
    }

    static final class Reports implements AdminRepositories.Reports {
        private final Sql sql;

        Reports(Sql sql) {
            this.sql = sql;
        }

        /**
         * Booked seats count CONFIRMED bookings; paid and refunded sum payments that succeeded,
         * including those refunded later.
         */
        private static final String STAT =
                "SELECT s.id, m.title, c.name AS cinema, s.starts_at, s.status, sc.capacity,"
                        + " (SELECT count(*) FROM booking_seat bs JOIN booking b"
                        + " ON b.id = bs.booking_id"
                        + " WHERE b.show_id = s.id AND b.status = 'CONFIRMED') AS booked,"
                        + " (SELECT coalesce(sum(p.amount), 0) FROM payment p JOIN booking b"
                        + " ON b.id = p.booking_id WHERE b.show_id = s.id"
                        + " AND p.status IN ('SUCCEEDED', 'REFUNDED')) AS paid,"
                        + " (SELECT coalesce(sum(p.refunded_amount), 0) FROM payment p"
                        + " JOIN booking b ON b.id = p.booking_id WHERE b.show_id = s.id"
                        + " AND p.status IN ('SUCCEEDED', 'REFUNDED')) AS refunded"
                        + " FROM show s JOIN movie m ON m.id = s.movie_id"
                        + " JOIN screen sc ON sc.id = s.screen_id"
                        + " JOIN cinema c ON c.id = sc.cinema_id";

        private static final RowMapper<AdminRepositories.ShowStat> ROW =
                rs ->
                        new AdminRepositories.ShowStat(
                                rs.getLong("id"),
                                rs.getString("title"),
                                rs.getString("cinema"),
                                Sqls.instant(rs, "starts_at"),
                                ShowStatus.valueOf(rs.getString("status")),
                                rs.getInt("capacity"),
                                rs.getInt("booked"),
                                rs.getLong("paid"),
                                rs.getLong("refunded"));

        private static final String WINDOW =
                " WHERE (?1 IS NULL OR c.city_id = ?1)"
                        + " AND (?2 IS NULL OR c.id IN"
                        + " (SELECT cinema_id FROM cinema_owner WHERE user_id = ?2))"
                        + " AND s.starts_at >= ?3 AND s.starts_at < ?4";

        @Override
        public Page<AdminRepositories.ShowStat> showStats(
                String cityId, Long ownerId, Instant from, Instant to, int page, int size) {
            long total =
                    sql.queryOne(
                                    "SELECT count(*) FROM show s"
                                            + " JOIN screen sc ON sc.id = s.screen_id"
                                            + " JOIN cinema c ON c.id = sc.cinema_id"
                                            + WINDOW,
                                    rs -> rs.getLong(1),
                                    cityId,
                                    ownerId,
                                    Sqls.ms(from),
                                    Sqls.ms(to))
                            .orElse(0L);
            List<AdminRepositories.ShowStat> items =
                    sql.query(
                            STAT + WINDOW + " ORDER BY s.starts_at, s.id LIMIT ?5 OFFSET ?6",
                            ROW,
                            cityId,
                            ownerId,
                            Sqls.ms(from),
                            Sqls.ms(to),
                            size,
                            (long) Math.max(page - 1, 0) * size);
            return new Page<>(items, page, size, total);
        }

        @Override
        public Optional<AdminRepositories.ShowStat> showStat(long showId) {
            return sql.queryOne(STAT + " WHERE s.id = ?", ROW, showId);
        }

        private record Sold(int booked, long revenue) {}

        @Override
        public List<AdminRepositories.CategoryStat> categoryStats(long showId) {
            Optional<Show> show = new SqlCatalog.Shows(sql).findById(showId);
            if (show.isEmpty()) {
                return List.of();
            }
            SeatLayout layout =
                    new SqlCatalog.Screens(sql)
                            .findById(show.get().screenId())
                            .orElseThrow()
                            .layout();
            Map<String, Integer> capacity = new HashMap<>();
            for (SeatId seat : layout.bookableSeats()) {
                capacity.merge(layout.categoryOf(seat), 1, Integer::sum);
            }
            Map<String, Sold> sold = new HashMap<>();
            sql.query(
                    "SELECT bs.category, count(*), coalesce(sum(bs.price), 0)"
                            + " FROM booking_seat bs JOIN booking b ON b.id = bs.booking_id"
                            + " WHERE b.show_id = ? AND b.status = 'CONFIRMED'"
                            + " GROUP BY bs.category",
                    rs -> sold.put(rs.getString(1), new Sold(rs.getInt(2), rs.getLong(3))),
                    showId);
            List<AdminRepositories.CategoryStat> out = new ArrayList<>();
            for (SeatLayout.Category c : layout.categories()) {
                Sold s = sold.getOrDefault(c.code(), new Sold(0, 0));
                out.add(
                        new AdminRepositories.CategoryStat(
                                c.code(),
                                capacity.getOrDefault(c.code(), 0),
                                s.booked(),
                                s.revenue()));
            }
            return out;
        }

        @Override
        public long countConfirmedSince(Instant since) {
            return sql.queryOne(
                            "SELECT count(*) FROM booking WHERE status = 'CONFIRMED'"
                                    + " AND updated_at >= ?",
                            rs -> rs.getLong(1),
                            Sqls.ms(since))
                    .orElse(0L);
        }
    }

    static final class CinemaOwners implements AdminRepositories.CinemaOwners {
        private final Sql sql;

        CinemaOwners(Sql sql) {
            this.sql = sql;
        }

        @Override
        public List<Cinema> cinemas(Long userId) {
            return sql.query(
                    "SELECT c.id, c.city_id, c.name, c.address FROM cinema c"
                            + " WHERE ?1 IS NULL OR c.id IN"
                            + " (SELECT cinema_id FROM cinema_owner WHERE user_id = ?1)"
                            + " ORDER BY c.name, c.id",
                    rs ->
                            new Cinema(
                                    rs.getLong(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getString(4)),
                    userId);
        }

        @Override
        public List<Long> cinemaIdsOf(long userId) {
            return sql.query(
                    "SELECT cinema_id FROM cinema_owner WHERE user_id = ? ORDER BY cinema_id",
                    rs -> rs.getLong(1),
                    userId);
        }

        @Override
        public boolean owns(long userId, long cinemaId) {
            return exists(
                    sql,
                    "SELECT 1 FROM cinema_owner WHERE user_id = ? AND cinema_id = ?",
                    userId,
                    cinemaId);
        }

        @Override
        public void link(long cinemaId, long userId) {
            sql.update(
                    "INSERT INTO cinema_owner (cinema_id, user_id) VALUES (?, ?)"
                            + " ON CONFLICT DO NOTHING",
                    cinemaId,
                    userId);
        }

        @Override
        public void unlink(long cinemaId, long userId) {
            sql.update(
                    "DELETE FROM cinema_owner WHERE cinema_id = ? AND user_id = ?",
                    cinemaId,
                    userId);
        }

        @Override
        public void unlinkAll(long userId) {
            sql.update("DELETE FROM cinema_owner WHERE user_id = ?", userId);
        }
    }

    static final class UserRoles implements AdminRepositories.UserRoles {
        // '!' escapes LIKE wildcards, so a search for "%" or "_" matches only those characters.
        private static final String MATCH =
                " WHERE ?1 IS NULL OR lower(email) LIKE ?1 ESCAPE '!'"
                        + " OR lower(name) LIKE ?1 ESCAPE '!'";

        private final Sql sql;

        UserRoles(Sql sql) {
            this.sql = sql;
        }

        @Override
        public Page<User> page(String q, int page, int size) {
            String like = q == null || q.isBlank() ? null : "%" + escape(q.strip()) + "%";
            long total =
                    sql.queryOne("SELECT count(*) FROM app_user" + MATCH, rs -> rs.getLong(1), like)
                            .orElse(0L);
            List<User> items =
                    sql.query(
                            "SELECT "
                                    + SqlBookings.USER_COLS
                                    + " FROM app_user"
                                    + MATCH
                                    + " ORDER BY id LIMIT ?2 OFFSET ?3",
                            SqlBookings.USER,
                            like,
                            size,
                            (long) Math.max(page - 1, 0) * size);
            return new Page<>(items, page, size, total);
        }

        private static String escape(String q) {
            return q.toLowerCase(Locale.ROOT)
                    .replace("!", "!!")
                    .replace("%", "!%")
                    .replace("_", "!_");
        }

        @Override
        public void saveRoles(long userId, Set<Role> roles) {
            sql.update(
                    "UPDATE app_user SET roles = ? WHERE id = ?",
                    roles.stream().map(Role::name).sorted().collect(Collectors.joining(",")),
                    userId);
        }
    }
}
