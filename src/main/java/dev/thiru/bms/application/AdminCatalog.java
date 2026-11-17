package dev.thiru.bms.application;

import dev.thiru.bms.domain.Cinema;
import dev.thiru.bms.domain.City;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.Movie;
import dev.thiru.bms.domain.Screen;
import dev.thiru.bms.domain.SeatLayout;
import dev.thiru.bms.domain.Show;
import dev.thiru.bms.domain.ShowFormat;
import dev.thiru.bms.domain.ShowStatus;
import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.UnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Admin catalog writes. Callers pass already-built domain objects (their constructors validate);
 * this class adds the rules that need the database: existence, uniqueness and "still in use".
 */
public final class AdminCatalog {

    private final UnitOfWork uow;
    private final Clock clock;

    public AdminCatalog(UnitOfWork uow, Clock clock) {
        this.uow = uow;
        this.clock = clock;
    }

    public City createCity(City city) {
        return uow.inTransaction(
                r -> {
                    if (r.cities().findById(city.id()).isPresent()) {
                        throw DomainException.conflict("City already exists");
                    }
                    return r.catalogWrites().saveCity(city);
                });
    }

    public City renameCity(String id, String name) {
        return uow.inTransaction(
                r -> {
                    City old =
                            r.cities()
                                    .findById(id)
                                    .orElseThrow(() -> DomainException.notFound("City"));
                    return r.catalogWrites().saveCity(new City(id, name, old.timeZone()));
                });
    }

    public Cinema createCinema(Cinema cinema) {
        return uow.inTransaction(
                r -> {
                    requireCity(r, cinema.cityId());
                    if (r.catalogWrites().cinemaNameTaken(cinema.cityId(), cinema.name(), 0)) {
                        throw DomainException.conflict("Cinema name is taken in this city");
                    }
                    return r.catalogWrites().saveCinema(cinema);
                });
    }

    public Cinema updateCinema(long id, String name, String address) {
        return uow.inTransaction(
                r -> {
                    Cinema old =
                            r.cinemas()
                                    .findById(id)
                                    .orElseThrow(() -> DomainException.notFound("Cinema"));
                    Cinema next = new Cinema(id, old.cityId(), name, address);
                    if (r.catalogWrites().cinemaNameTaken(old.cityId(), name, id)) {
                        throw DomainException.conflict("Cinema name is taken in this city");
                    }
                    return r.catalogWrites().saveCinema(next);
                });
    }

    public void deleteCinema(long id) {
        uow.inTransaction(
                r -> {
                    r.cinemas().findById(id).orElseThrow(() -> DomainException.notFound("Cinema"));
                    if (r.catalogWrites().cinemaHasShows(id)) {
                        throw DomainException.conflict("Cinema still has shows");
                    }
                    for (Screen s : r.screens().findByCinema(id)) {
                        r.catalogWrites().deleteScreen(s.id());
                    }
                    r.catalogWrites().deleteCinema(id);
                    return null;
                });
    }

    public Screen createScreen(long cinemaId, String name, SeatLayout layout) {
        return uow.inTransaction(
                r -> {
                    r.cinemas()
                            .findById(cinemaId)
                            .orElseThrow(() -> DomainException.notFound("Cinema"));
                    if (r.catalogWrites().screenNameTaken(cinemaId, name, 0)) {
                        throw DomainException.conflict("Screen name is taken in this cinema");
                    }
                    return r.catalogWrites().saveScreen(new Screen(0, cinemaId, name, layout));
                });
    }

    public Screen updateScreen(long id, String name, SeatLayout layout) {
        return uow.inTransaction(
                r -> {
                    Screen old =
                            r.screens()
                                    .findById(id)
                                    .orElseThrow(() -> DomainException.notFound("Screen"));
                    if (r.catalogWrites().screenNameTaken(old.cinemaId(), name, id)) {
                        throw DomainException.conflict("Screen name is taken in this cinema");
                    }
                    if (!layout.equals(old.layout())
                            && r.catalogWrites().screenHasFutureShows(id, clock.instant())) {
                        throw DomainException.conflict(
                                "Layout cannot change while future shows exist");
                    }
                    return r.catalogWrites()
                            .saveScreen(new Screen(id, old.cinemaId(), name, layout));
                });
    }

    public void deleteScreen(long id) {
        uow.inTransaction(
                r -> {
                    r.screens().findById(id).orElseThrow(() -> DomainException.notFound("Screen"));
                    if (r.catalogWrites().screenHasShows(id)) {
                        throw DomainException.conflict("Screen still has shows");
                    }
                    r.catalogWrites().deleteScreen(id);
                    return null;
                });
    }

    public Movie createMovie(Movie movie) {
        return uow.inTransaction(r -> r.catalogWrites().saveMovie(movie));
    }

    /** The id of {@code movie} is ignored; the path id wins. */
    public Movie updateMovie(long id, Movie movie) {
        return uow.inTransaction(
                r -> {
                    r.movies().findById(id).orElseThrow(() -> DomainException.notFound("Movie"));
                    return r.catalogWrites()
                            .saveMovie(
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

    public void deleteMovie(long id) {
        uow.inTransaction(
                r -> {
                    r.movies().findById(id).orElseThrow(() -> DomainException.notFound("Movie"));
                    if (r.catalogWrites().movieHasShows(id)) {
                        throw DomainException.conflict("Movie still has shows");
                    }
                    r.catalogWrites().deleteMovie(id);
                    return null;
                });
    }

    public Show createShow(
            long movieId,
            long screenId,
            Instant startsAt,
            String language,
            ShowFormat format,
            Map<String, Long> prices) {
        return uow.inTransaction(
                r -> {
                    Show show = build(r, 0, movieId, screenId, startsAt, language, format, prices);
                    return r.catalogWrites().insertShow(show);
                });
    }

    /** Allowed only while no seat of the show is locked (SPEC 7). */
    public Show updateShow(
            long id,
            long movieId,
            long screenId,
            Instant startsAt,
            String language,
            ShowFormat format,
            Map<String, Long> prices) {
        return uow.inTransaction(
                r -> {
                    Show old =
                            r.shows()
                                    .findById(id)
                                    .orElseThrow(() -> DomainException.notFound("Show"));
                    if (old.status() == ShowStatus.CANCELLED) {
                        throw DomainException.conflict("A cancelled show cannot be edited");
                    }
                    if (!r.locks().findByShow(id).isEmpty()) {
                        throw DomainException.conflict("Seats are already locked for this show");
                    }
                    Show show = build(r, id, movieId, screenId, startsAt, language, format, prices);
                    return r.shows().save(show);
                });
    }

    private Show build(
            Repositories r,
            long id,
            long movieId,
            long screenId,
            Instant startsAt,
            String language,
            ShowFormat format,
            Map<String, Long> prices) {
        Movie movie =
                r.movies().findById(movieId).orElseThrow(() -> DomainException.notFound("Movie"));
        Screen screen =
                r.screens()
                        .findById(screenId)
                        .orElseThrow(() -> DomainException.notFound("Screen"));
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
                r.shows().findByScreen(screenId));
    }

    private static void requireCity(Repositories r, String cityId) {
        r.cities().findById(cityId).orElseThrow(() -> DomainException.notFound("City"));
    }
}
