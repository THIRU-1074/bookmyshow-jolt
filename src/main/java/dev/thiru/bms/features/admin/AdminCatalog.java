package dev.thiru.bms.features.admin;

import static dev.thiru.bms.features.admin.AdminSql.exists;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.account.CinemaScope;
import dev.thiru.bms.kernel.catalog.Cinema;
import dev.thiru.bms.kernel.catalog.City;
import dev.thiru.bms.kernel.catalog.Movie;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import dev.thiru.bms.ports.Db;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin and theatre owner catalog writes. Callers pass already-built domain objects (their
 * constructors validate); this class adds the rules that need the database: existence, uniqueness,
 * "still in use" and, for a {@link CinemaScope}, cinema ownership. The public catalog cache is
 * cleared by the pipeline after any successful write under {@code /api/admin} or {@code
 * /api/owner}, so nothing here touches it.
 */
final class AdminCatalog {

    /** A cinema with its screens, layouts included. */
    record CinemaScreens(Cinema cinema, List<Screen> screens) {}

    private final Db db;
    private final Clock clock;

    AdminCatalog(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    // ---- cities and cinemas (admin only) ----

    City createCity(City city) {
        return db.inTransaction(
                tx -> {
                    if (AdminSql.city(tx, city.id()).isPresent()) {
                        throw DomainException.conflict("City already exists");
                    }
                    return saveCity(tx, city);
                });
    }

    City renameCity(String id, String name) {
        return db.inTransaction(
                tx -> {
                    City old =
                            AdminSql.city(tx, id)
                                    .orElseThrow(() -> DomainException.notFound("City"));
                    return saveCity(tx, new City(id, name, old.timeZone()));
                });
    }

    private static City saveCity(Db.Tx tx, City city) {
        tx.update(
                "INSERT INTO city (id, name, time_zone) VALUES (?, ?, ?) ON CONFLICT (id)"
                        + " DO UPDATE SET name = excluded.name, time_zone = excluded.time_zone",
                city.id(),
                city.name(),
                city.timeZone().getId());
        return city;
    }

    Cinema createCinema(Cinema cinema) {
        return db.inTransaction(
                tx -> {
                    AdminSql.city(tx, cinema.cityId())
                            .orElseThrow(() -> DomainException.notFound("City"));
                    requireFreeCinemaName(tx, cinema.cityId(), cinema.name(), 0);
                    long id =
                            tx.insert(
                                    "INSERT INTO cinema (city_id, name, address) VALUES (?, ?, ?)",
                                    cinema.cityId(),
                                    cinema.name(),
                                    cinema.address());
                    return new Cinema(id, cinema.cityId(), cinema.name(), cinema.address());
                });
    }

    Cinema updateCinema(long id, String name, String address) {
        return db.inTransaction(
                tx -> {
                    Cinema old =
                            AdminSql.cinema(tx, id)
                                    .orElseThrow(() -> DomainException.notFound("Cinema"));
                    Cinema next = new Cinema(id, old.cityId(), name, address);
                    requireFreeCinemaName(tx, old.cityId(), name, id);
                    tx.update(
                            "UPDATE cinema SET city_id = ?, name = ?, address = ? WHERE id = ?",
                            next.cityId(),
                            next.name(),
                            next.address(),
                            id);
                    return next;
                });
    }

    private static void requireFreeCinemaName(Db.Tx tx, String cityId, String name, long except) {
        if (exists(
                tx,
                "SELECT 1 FROM cinema WHERE city_id = ? AND lower(name) = lower(?) AND id <> ?",
                cityId,
                name,
                except)) {
            throw DomainException.conflict("Cinema name is taken in this city");
        }
    }

    void deleteCinema(long id) {
        db.inTransaction(
                tx -> {
                    AdminSql.cinema(tx, id).orElseThrow(() -> DomainException.notFound("Cinema"));
                    if (exists(
                            tx,
                            "SELECT 1 FROM show s JOIN screen sc ON sc.id = s.screen_id"
                                    + " WHERE sc.cinema_id = ? LIMIT 1",
                            id)) {
                        throw DomainException.conflict("Cinema still has shows");
                    }
                    tx.update("DELETE FROM screen WHERE cinema_id = ?", id);
                    tx.update("DELETE FROM cinema WHERE id = ?", id);
                    return null;
                });
    }

    /** The cinemas in scope, by name, each with its screens. */
    List<CinemaScreens> cinemas(CinemaScope scope) {
        return db.inTransaction(
                tx ->
                        AdminSql.cinemas(tx, scope).stream()
                                .map(c -> new CinemaScreens(c, AdminSql.screensOf(tx, c.id())))
                                .toList());
    }

    // ---- screens ----

    Screen createScreen(CinemaScope scope, long cinemaId, String name, SeatLayout layout) {
        return db.inTransaction(
                tx -> {
                    AdminSql.cinema(tx, cinemaId)
                            .orElseThrow(() -> DomainException.notFound("Cinema"));
                    AdminSql.require(tx, scope, cinemaId, "Cinema");
                    requireFreeScreenName(tx, cinemaId, name, 0);
                    Screen screen = new Screen(0, cinemaId, name, layout);
                    long id =
                            tx.insert(
                                    "INSERT INTO screen (cinema_id, name, layout_json, capacity)"
                                            + " VALUES (?, ?, ?, ?)",
                                    cinemaId,
                                    name,
                                    AdminSql.layoutJson(layout),
                                    screen.capacity());
                    return new Screen(id, cinemaId, name, layout);
                });
    }

    Screen updateScreen(CinemaScope scope, long id, String name, SeatLayout layout) {
        return db.inTransaction(
                tx -> {
                    Screen old =
                            AdminSql.screen(tx, id)
                                    .orElseThrow(() -> DomainException.notFound("Screen"));
                    AdminSql.require(tx, scope, old.cinemaId(), "Screen");
                    requireFreeScreenName(tx, old.cinemaId(), name, id);
                    if (!layout.equals(old.layout())
                            && exists(
                                    tx,
                                    "SELECT 1 FROM show WHERE screen_id = ? AND status ="
                                            + " 'SCHEDULED' AND starts_at > ? LIMIT 1",
                                    id,
                                    Db.millis(clock.instant()))) {
                        throw DomainException.conflict(
                                "Layout cannot change while future shows exist");
                    }
                    Screen next = new Screen(id, old.cinemaId(), name, layout);
                    tx.update(
                            "UPDATE screen SET cinema_id = ?, name = ?, layout_json = ?,"
                                    + " capacity = ? WHERE id = ?",
                            next.cinemaId(),
                            name,
                            AdminSql.layoutJson(layout),
                            next.capacity(),
                            id);
                    return next;
                });
    }

    private static void requireFreeScreenName(Db.Tx tx, long cinemaId, String name, long except) {
        if (exists(
                tx,
                "SELECT 1 FROM screen WHERE cinema_id = ? AND lower(name) = lower(?) AND id <> ?",
                cinemaId,
                name,
                except)) {
            throw DomainException.conflict("Screen name is taken in this cinema");
        }
    }

    void deleteScreen(long id) {
        db.inTransaction(
                tx -> {
                    AdminSql.screen(tx, id).orElseThrow(() -> DomainException.notFound("Screen"));
                    if (exists(tx, "SELECT 1 FROM show WHERE screen_id = ? LIMIT 1", id)) {
                        throw DomainException.conflict("Screen still has shows");
                    }
                    tx.update("DELETE FROM screen WHERE id = ?", id);
                    return null;
                });
    }

    // ---- movies (admin only) ----

    Movie createMovie(Movie movie) {
        return db.inTransaction(tx -> saveMovie(tx, movie));
    }

    /** The id of {@code movie} is ignored; the path id wins. */
    Movie updateMovie(long id, Movie movie) {
        return db.inTransaction(
                tx -> {
                    requireMovie(tx, id);
                    return saveMovie(
                            tx,
                            new Movie(
                                    id,
                                    movie.title(),
                                    movie.languages(),
                                    movie.genres(),
                                    movie.certificate(),
                                    movie.durationMinutes(),
                                    movie.releaseDate(),
                                    movie.posterUrl(),
                                    movie.description()));
                });
    }

    void deleteMovie(long id) {
        db.inTransaction(
                tx -> {
                    requireMovie(tx, id);
                    if (exists(tx, "SELECT 1 FROM show WHERE movie_id = ? LIMIT 1", id)) {
                        throw DomainException.conflict("Movie still has shows");
                    }
                    // Languages and genres go with it (ON DELETE CASCADE).
                    tx.update("DELETE FROM movie WHERE id = ?", id);
                    return null;
                });
    }

    private static void requireMovie(Db.Tx tx, long id) {
        if (!exists(tx, "SELECT 1 FROM movie WHERE id = ?", id)) {
            throw DomainException.notFound("Movie");
        }
    }

    private static Movie saveMovie(Db.Tx tx, Movie m) {
        long id;
        if (m.id() > 0) {
            tx.update(
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
            tx.update("DELETE FROM movie_language WHERE movie_id = ?", id);
            tx.update("DELETE FROM movie_genre WHERE movie_id = ?", id);
        } else {
            id =
                    tx.insert(
                            "INSERT INTO movie (title, certificate, duration_minutes,"
                                    + " release_date, poster_url, description)"
                                    + " VALUES (?, ?, ?, ?, ?, ?)",
                            m.title(),
                            m.certificate().name(),
                            m.durationMinutes(),
                            m.releaseDate().toString(),
                            m.posterUrl(),
                            m.description());
        }
        long movieId = id;
        tx.batch(
                "INSERT INTO movie_language (movie_id, language) VALUES (?, ?)",
                m.languages().stream().map(l -> new Object[] {movieId, l}).toList());
        tx.batch(
                "INSERT INTO movie_genre (movie_id, genre) VALUES (?, ?)",
                m.genres().stream().map(g -> new Object[] {movieId, g}).toList());
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

    // ---- shows ----

    /** The overlap rule and price checks are the same for admins and owners. */
    Show createShow(
            CinemaScope scope,
            long movieId,
            long screenId,
            Instant startsAt,
            String language,
            ShowFormat format,
            Map<String, Long> prices) {
        return db.inTransaction(
                tx -> {
                    AdminSql.screen(tx, screenId)
                            .ifPresent(s -> AdminSql.require(tx, scope, s.cinemaId(), "Screen"));
                    Show show = build(tx, 0, movieId, screenId, startsAt, language, format, prices);
                    long id =
                            tx.insert(
                                    "INSERT INTO show (movie_id, screen_id, starts_at, ends_at,"
                                            + " language, format, prices_json, status)"
                                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                                    show.movieId(),
                                    show.screenId(),
                                    Db.millis(show.startsAt()),
                                    Db.millis(show.endsAt()),
                                    show.language(),
                                    show.format().label(),
                                    AdminSql.pricesJson(show.prices()),
                                    show.status().name());
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
                });
    }

    /** Allowed only while no seat of the show is locked (SPEC 7). Admin only. */
    Show updateShow(
            long id,
            long movieId,
            long screenId,
            Instant startsAt,
            String language,
            ShowFormat format,
            Map<String, Long> prices) {
        return db.inTransaction(
                tx -> {
                    Show old =
                            AdminSql.show(tx, id)
                                    .orElseThrow(() -> DomainException.notFound("Show"));
                    if (old.status() == ShowStatus.CANCELLED) {
                        throw DomainException.conflict("A cancelled show cannot be edited");
                    }
                    if (exists(tx, "SELECT 1 FROM seat_lock WHERE show_id = ? LIMIT 1", id)) {
                        throw DomainException.conflict("Seats are already locked for this show");
                    }
                    Show show =
                            build(tx, id, movieId, screenId, startsAt, language, format, prices);
                    tx.update(
                            "UPDATE show SET movie_id = ?, screen_id = ?, starts_at = ?,"
                                    + " ends_at = ?, language = ?, format = ?, prices_json = ?,"
                                    + " status = ? WHERE id = ?",
                            show.movieId(),
                            show.screenId(),
                            Db.millis(show.startsAt()),
                            Db.millis(show.endsAt()),
                            show.language(),
                            show.format().label(),
                            AdminSql.pricesJson(show.prices()),
                            show.status().name(),
                            id);
                    return show;
                });
    }

    private Show build(
            Db.Tx tx,
            long id,
            long movieId,
            long screenId,
            Instant startsAt,
            String language,
            ShowFormat format,
            Map<String, Long> prices) {
        Movie movie =
                AdminSql.movie(tx, movieId).orElseThrow(() -> DomainException.notFound("Movie"));
        Screen screen =
                AdminSql.screen(tx, screenId).orElseThrow(() -> DomainException.notFound("Screen"));
        if (startsAt != null && !startsAt.isAfter(clock.instant())) {
            throw DomainException.validation("startsAt", "must be in the future");
        }
        return Show.schedule(
                id,
                movie,
                screen,
                startsAt,
                language,
                format,
                prices,
                AdminSql.showsOnScreen(tx, screenId));
    }
}
