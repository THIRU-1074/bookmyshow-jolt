package dev.thiru.bms.features.catalog;

import dev.thiru.bms.features.catalog.CatalogDtos.CinemaDetailDto;
import dev.thiru.bms.features.catalog.CatalogDtos.CinemaDto;
import dev.thiru.bms.features.catalog.CatalogDtos.CinemaShowsDto;
import dev.thiru.bms.features.catalog.CatalogDtos.CityDto;
import dev.thiru.bms.features.catalog.CatalogDtos.MovieDetail;
import dev.thiru.bms.features.catalog.CatalogDtos.MovieRef;
import dev.thiru.bms.features.catalog.CatalogDtos.MovieSummary;
import dev.thiru.bms.features.catalog.CatalogDtos.NamedRef;
import dev.thiru.bms.features.catalog.CatalogDtos.PriceDto;
import dev.thiru.bms.features.catalog.CatalogDtos.ScreenSummary;
import dev.thiru.bms.features.catalog.CatalogDtos.ShowDetailDto;
import dev.thiru.bms.features.catalog.CatalogDtos.ShowTimesDto;
import dev.thiru.bms.features.catalog.CatalogDtos.SlotDto;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.kernel.booking.SeatLock;
import dev.thiru.bms.kernel.catalog.Certificate;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.kernel.catalog.SeatMap;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import dev.thiru.bms.ports.Db;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The catalog's reads: cities, movies, cinemas, shows and the seat map. Pages are 1-based. Layouts
 * and prices are JSON columns, read with SQLite's JSON functions so no JSON library is needed.
 */
final class CatalogSql {

    enum Sort {
        RELEASE,
        TITLE
    }

    record MovieFilter(
            String cityId,
            LocalDate date,
            String q,
            Set<String> genres,
            Set<String> languages,
            Set<Certificate> certificates,
            Set<ShowFormat> formats,
            Sort sort) {}

    private static final String MOVIE_COLS =
            "m.id, m.title, m.certificate, m.duration_minutes, m.release_date, m.poster_url,"
                    + " m.description";

    private static final Db.Row<CinemaDto> CINEMA =
            rs ->
                    new CinemaDto(
                            rs.getLong("id"),
                            rs.getString("city_id"),
                            rs.getString("name"),
                            rs.getString("address"));

    private final Db db;
    private final Clock clock;
    private final BookingPolicy policy;

    CatalogSql(Db db, Clock clock, BookingPolicy policy) {
        this.db = db;
        this.clock = clock;
        this.policy = policy;
    }

    List<CityDto> cities() {
        return db.inTransaction(
                tx ->
                        tx.query(
                                "SELECT id, name, time_zone FROM city ORDER BY name",
                                rs ->
                                        new CityDto(
                                                rs.getString("id"),
                                                rs.getString("name"),
                                                ZoneId.of(rs.getString("time_zone")).getId())));
    }

    Page<MovieSummary> movies(MovieFilter f, int page, int size) {
        return db.inTransaction(
                tx -> {
                    String q = f.q() == null || f.q().isBlank() ? null : f.q().trim();
                    if (q != null && q.length() < 2) {
                        throw DomainException.validation("q", "must be at least 2 characters");
                    }
                    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
                    List<Object> params = new ArrayList<>();
                    if (q != null) {
                        where.append(" AND lower(m.title) LIKE ? ESCAPE '\\'");
                        params.add(
                                "%"
                                        + q.toLowerCase(Locale.ROOT)
                                                .replace("\\", "\\\\")
                                                .replace("%", "\\%")
                                                .replace("_", "\\_")
                                        + "%");
                    }
                    tag(where, params, "movie_genre", "genre", f.genres());
                    tag(where, params, "movie_language", "language", f.languages());
                    if (!f.certificates().isEmpty()) {
                        where.append(" AND m.certificate IN (")
                                .append(Db.marks(f.certificates().size()))
                                .append(')');
                        f.certificates().forEach(c -> params.add(c.name()));
                    }
                    if (f.cityId() != null || !f.formats().isEmpty()) {
                        where.append(
                                " AND EXISTS (SELECT 1 FROM show s JOIN screen sc ON sc.id ="
                                        + " s.screen_id JOIN cinema c ON c.id = sc.cinema_id"
                                        + " WHERE s.movie_id = m.id AND s.status = 'SCHEDULED'");
                        if (f.cityId() != null) {
                            Instant[] window = window(cityZone(tx, f.cityId()), f.date());
                            where.append(
                                    " AND c.city_id = ? AND s.starts_at >= ? AND s.starts_at < ?");
                            params.add(f.cityId());
                            params.add(Db.millis(window[0]));
                            params.add(Db.millis(window[1]));
                        }
                        if (!f.formats().isEmpty()) {
                            where.append(" AND s.format IN (")
                                    .append(Db.marks(f.formats().size()))
                                    .append(')');
                            f.formats().forEach(x -> params.add(x.label()));
                        }
                        where.append(')');
                    }
                    long total =
                            tx.queryOne(
                                            "SELECT count(*) FROM movie m" + where,
                                            rs -> rs.getLong(1),
                                            params.toArray())
                                    .orElse(0L);
                    String order =
                            f.sort() == Sort.TITLE
                                    ? " ORDER BY lower(m.title), m.id"
                                    : " ORDER BY m.release_date DESC, lower(m.title), m.id";
                    params.add(size);
                    params.add((long) Math.max(page - 1, 0) * size);
                    List<MovieSummary> items =
                            movieRows(
                                            tx,
                                            "SELECT "
                                                    + MOVIE_COLS
                                                    + " FROM movie m"
                                                    + where
                                                    + order
                                                    + " LIMIT ? OFFSET ?",
                                            params.toArray())
                                    .stream()
                                    .map(MovieDetail::summary)
                                    .toList();
                    return new Page<>(items, page, size, total);
                });
    }

    private static void tag(
            StringBuilder where, List<Object> params, String table, String col, Set<String> in) {
        if (!in.isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM ")
                    .append(table)
                    .append(" t WHERE t.movie_id = m.id AND t.")
                    .append(col)
                    .append(" IN (")
                    .append(Db.marks(in.size()))
                    .append("))");
            params.addAll(in);
        }
    }

    MovieDetail movie(long id) {
        return db.inTransaction(
                tx ->
                        movieRows(tx, "SELECT " + MOVIE_COLS + " FROM movie m WHERE m.id = ?", id)
                                .stream()
                                .findFirst()
                                .orElseThrow(() -> DomainException.notFound("Movie")));
    }

    /** Movies with their languages and genres, one query each for the whole batch. */
    private static List<MovieDetail> movieRows(Db.Tx tx, String sql, Object... args) {
        record Raw(
                long id,
                String title,
                String certificate,
                int duration,
                LocalDate release,
                String poster,
                String description) {}
        List<Raw> rows =
                tx.query(
                        sql,
                        rs ->
                                new Raw(
                                        rs.getLong("id"),
                                        rs.getString("title"),
                                        rs.getString("certificate"),
                                        rs.getInt("duration_minutes"),
                                        LocalDate.parse(rs.getString("release_date")),
                                        rs.getString("poster_url"),
                                        rs.getString("description")),
                        args);
        if (rows.isEmpty()) {
            return List.of();
        }
        Object[] ids = rows.stream().map(Raw::id).toArray();
        Map<Long, List<String>> languages = tags(tx, "movie_language", "language", ids);
        Map<Long, List<String>> genres = tags(tx, "movie_genre", "genre", ids);
        return rows.stream()
                .map(
                        r ->
                                new MovieDetail(
                                        r.id(),
                                        r.title(),
                                        languages.getOrDefault(r.id(), List.of()),
                                        genres.getOrDefault(r.id(), List.of()),
                                        r.certificate(),
                                        r.duration(),
                                        r.release(),
                                        r.poster(),
                                        r.description()))
                .toList();
    }

    private static Map<Long, List<String>> tags(Db.Tx tx, String table, String col, Object[] ids) {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        tx.query(
                "SELECT movie_id, "
                        + col
                        + " FROM "
                        + table
                        + " WHERE movie_id IN ("
                        + Db.marks(ids.length)
                        + ") ORDER BY movie_id, "
                        + col,
                rs ->
                        out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                                .add(rs.getString(2)),
                ids);
        return out;
    }

    /** Future SCHEDULED shows of the movie on one local date, grouped by cinema. */
    ShowTimesDto showTimes(
            long movieId,
            String cityId,
            LocalDate date,
            Set<String> languages,
            Set<ShowFormat> formats) {
        return db.inTransaction(
                tx -> {
                    ZoneId zone = cityZone(tx, cityId);
                    LocalDate day = date != null ? date : today(zone);
                    Instant[] window = window(zone, day);
                    StringBuilder sql =
                            new StringBuilder(
                                    "SELECT s.id, s.starts_at, s.language, s.format,"
                                            + " coalesce((SELECT min(p.value) FROM"
                                            + " json_each(s.prices_json) p), 0) AS min_price,"
                                            + " sc.capacity, c.id AS c_id, c.name AS c_name,"
                                            + " c.address AS c_address"
                                            + " FROM show s JOIN screen sc ON sc.id = s.screen_id"
                                            + " JOIN cinema c ON c.id = sc.cinema_id"
                                            + " WHERE s.movie_id = ? AND s.status = 'SCHEDULED'"
                                            + " AND c.city_id = ? AND s.starts_at >= ?"
                                            + " AND s.starts_at < ?");
                    List<Object> params = new ArrayList<>();
                    params.add(movieId);
                    params.add(cityId);
                    params.add(Db.millis(window[0]));
                    params.add(Db.millis(window[1]));
                    if (!languages.isEmpty()) {
                        sql.append(" AND s.language IN (")
                                .append(Db.marks(languages.size()))
                                .append(')');
                        params.addAll(languages);
                    }
                    if (!formats.isEmpty()) {
                        sql.append(" AND s.format IN (")
                                .append(Db.marks(formats.size()))
                                .append(')');
                        formats.forEach(x -> params.add(x.label()));
                    }
                    sql.append(" ORDER BY c.id, s.starts_at, s.id");
                    record Listing(
                            long id,
                            Instant startsAt,
                            String language,
                            String format,
                            long minPrice,
                            int capacity,
                            CinemaDto cinema) {}
                    List<Listing> listings =
                            tx.query(
                                    sql.toString(),
                                    rs ->
                                            new Listing(
                                                    rs.getLong("id"),
                                                    Db.instant(rs, "starts_at"),
                                                    rs.getString("language"),
                                                    rs.getString("format"),
                                                    rs.getLong("min_price"),
                                                    rs.getInt("capacity"),
                                                    new CinemaDto(
                                                            rs.getLong("c_id"),
                                                            cityId,
                                                            rs.getString("c_name"),
                                                            rs.getString("c_address"))),
                                    params.toArray());
                    Map<Long, Integer> taken =
                            countLive(tx, listings.stream().map(Listing::id).toList());
                    Map<CinemaDto, List<SlotDto>> byCinema = new LinkedHashMap<>();
                    for (Listing l : listings) {
                        byCinema.computeIfAbsent(l.cinema(), k -> new ArrayList<>())
                                .add(
                                        new SlotDto(
                                                l.id(),
                                                l.startsAt(),
                                                l.startsAt().atZone(zone).toLocalTime(),
                                                l.language(),
                                                ShowFormat.parse(l.format()).label(),
                                                l.minPrice(),
                                                availability(
                                                        l.capacity(),
                                                        taken.getOrDefault(l.id(), 0))));
                    }
                    List<CinemaShowsDto> cinemas = new ArrayList<>();
                    byCinema.forEach(
                            (c, slots) ->
                                    cinemas.add(
                                            new CinemaShowsDto(
                                                    c.id(),
                                                    c.name(),
                                                    c.address(),
                                                    List.copyOf(slots))));
                    return new ShowTimesDto(day, cinemas);
                });
    }

    /** Live seat locks per show: BOOKED, or HELD and not yet expired. */
    private Map<Long, Integer> countLive(Db.Tx tx, List<Long> showIds) {
        if (showIds.isEmpty()) {
            return Map.of();
        }
        List<Object> params = new ArrayList<>(showIds);
        params.add(Db.millis(clock.instant()));
        Map<Long, Integer> out = new HashMap<>();
        tx.query(
                "SELECT show_id, count(*) FROM seat_lock WHERE show_id IN ("
                        + Db.marks(showIds.size())
                        + ") AND (state = 'BOOKED' OR expires_at > ?) GROUP BY show_id",
                rs -> out.put(rs.getLong(1), rs.getInt(2)),
                params.toArray());
        return out;
    }

    // Under 20 percent free is "filling fast"; integer math keeps the boundary exact.
    static String availability(int capacity, int taken) {
        int free = Math.max(0, capacity - taken);
        if (free == 0) {
            return "SOLD_OUT";
        }
        return free * 5 < capacity ? "FILLING_FAST" : "AVAILABLE";
    }

    Page<CinemaDto> cinemas(String cityId, int page, int size) {
        return db.inTransaction(
                tx -> {
                    // A null city lists every cinema; the optional filter keeps one statement.
                    long total =
                            tx.queryOne(
                                            "SELECT count(*) FROM cinema"
                                                    + " WHERE (?1 IS NULL OR city_id = ?1)",
                                            rs -> rs.getLong(1),
                                            cityId)
                                    .orElse(0L);
                    List<CinemaDto> items =
                            tx.query(
                                    "SELECT id, city_id, name, address FROM cinema"
                                            + " WHERE (?1 IS NULL OR city_id = ?1)"
                                            + " ORDER BY name, id LIMIT ?2 OFFSET ?3",
                                    CINEMA,
                                    cityId,
                                    size,
                                    (long) Math.max(page - 1, 0) * size);
                    return new Page<>(items, page, size, total);
                });
    }

    CinemaDetailDto cinema(long id) {
        return db.inTransaction(
                tx -> {
                    CinemaDto c =
                            tx.queryOne(
                                            "SELECT id, city_id, name, address FROM cinema"
                                                    + " WHERE id = ?",
                                            CINEMA,
                                            id)
                                    .orElseThrow(() -> DomainException.notFound("Cinema"));
                    List<ScreenSummary> screens =
                            tx.query(
                                    "SELECT id, name, capacity FROM screen WHERE cinema_id = ?"
                                            + " ORDER BY name, id",
                                    rs ->
                                            new ScreenSummary(
                                                    rs.getLong("id"),
                                                    rs.getString("name"),
                                                    rs.getInt("capacity")),
                                    id);
                    return new CinemaDetailDto(c.id(), c.cityId(), c.name(), c.address(), screens);
                });
    }

    ShowDetailDto show(long id) {
        return db.inTransaction(
                tx -> {
                    // Prices come from the layout, so the head row is completed below.
                    ShowDetailDto head =
                            tx.queryOne(
                                            "SELECT s.id, s.starts_at, s.language, s.format,"
                                                    + " s.status, m.id AS m_id, m.title,"
                                                    + " m.certificate, m.duration_minutes,"
                                                    + " c.id AS c_id, c.name AS c_name,"
                                                    + " sc.id AS sc_id, sc.name AS sc_name,"
                                                    + " ci.time_zone"
                                                    + " FROM show s"
                                                    + " JOIN screen sc ON sc.id = s.screen_id"
                                                    + " JOIN cinema c ON c.id = sc.cinema_id"
                                                    + " JOIN city ci ON ci.id = c.city_id"
                                                    + " JOIN movie m ON m.id = s.movie_id"
                                                    + " WHERE s.id = ?",
                                            rs -> {
                                                Instant startsAt = Db.instant(rs, "starts_at");
                                                ZoneId zone = ZoneId.of(rs.getString("time_zone"));
                                                return new ShowDetailDto(
                                                        rs.getLong("id"),
                                                        new MovieRef(
                                                                rs.getLong("m_id"),
                                                                rs.getString("title"),
                                                                rs.getString("certificate"),
                                                                rs.getInt("duration_minutes")),
                                                        new NamedRef(
                                                                rs.getLong("c_id"),
                                                                rs.getString("c_name")),
                                                        new NamedRef(
                                                                rs.getLong("sc_id"),
                                                                rs.getString("sc_name")),
                                                        startsAt,
                                                        startsAt.atZone(zone).toLocalTime(),
                                                        rs.getString("language"),
                                                        ShowFormat.parse(rs.getString("format"))
                                                                .label(),
                                                        List.of(),
                                                        rs.getString("status"));
                                            },
                                            id)
                                    .orElseThrow(() -> DomainException.notFound("Show"));
                    List<PriceDto> prices =
                            SeatMap.prices(layout(tx, head.screen().id()), prices(tx, id)).stream()
                                    .map(PriceDto::of)
                                    .toList();
                    return new ShowDetailDto(
                            head.id(),
                            head.movie(),
                            head.cinema(),
                            head.screen(),
                            head.startsAt(),
                            head.localTime(),
                            head.language(),
                            head.format(),
                            prices,
                            head.status());
                });
    }

    SeatMap seatMap(long showId) {
        return db.inTransaction(
                tx -> {
                    long screenId =
                            tx.queryOne(
                                            "SELECT screen_id FROM show WHERE id = ?",
                                            rs -> rs.getLong(1),
                                            showId)
                                    .orElseThrow(() -> DomainException.notFound("Show"));
                    List<SeatLock> locks =
                            tx.query(
                                    "SELECT show_id, seat_id, hold_id, booking_id, state,"
                                            + " expires_at FROM seat_lock WHERE show_id = ?",
                                    rs -> {
                                        long expires = rs.getLong("expires_at");
                                        // wasNull() describes the last column read.
                                        boolean noExpiry = rs.wasNull();
                                        return new SeatLock(
                                                rs.getLong("show_id"),
                                                SeatId.parse(rs.getString("seat_id")),
                                                rs.getString("hold_id"),
                                                rs.getString("booking_id"),
                                                SeatLock.State.valueOf(rs.getString("state")),
                                                noExpiry ? null : Instant.ofEpochMilli(expires));
                                    },
                                    showId);
                    return SeatMap.assemble(
                            showId,
                            layout(tx, screenId),
                            prices(tx, showId),
                            locks,
                            clock.instant(),
                            policy.holdTtl());
                });
    }

    /** The screen's {@code layout_json}, read element by element. */
    private static SeatLayout layout(Db.Tx tx, long screenId) {
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

    private static Map<String, Long> prices(Db.Tx tx, long showId) {
        Map<String, Long> out = new HashMap<>();
        tx.query(
                "SELECT p.key, p.value FROM show s, json_each(s.prices_json) p WHERE s.id = ?",
                rs -> out.put(rs.getString(1), rs.getLong(2)),
                showId);
        return out;
    }

    private static ZoneId cityZone(Db.Tx tx, String cityId) {
        return tx.queryOne(
                        "SELECT time_zone FROM city WHERE id = ?",
                        rs -> ZoneId.of(rs.getString(1)),
                        cityId)
                .orElseThrow(() -> DomainException.notFound("City"));
    }

    private LocalDate today(ZoneId zone) {
        return clock.instant().atZone(zone).toLocalDate();
    }

    /** The local day as a UTC interval, never starting in the past: started shows are not sold. */
    private Instant[] window(ZoneId zone, LocalDate date) {
        LocalDate day = date != null ? date : today(zone);
        Instant start = day.atStartOfDay(zone).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
        // A show starting exactly now is already closed to sales, and the interval is [from, to),
        // so the earliest sellable start is one millisecond ahead.
        Instant earliest = clock.instant().plusMillis(1);
        return new Instant[] {start.isBefore(earliest) ? earliest : start, end};
    }
}
