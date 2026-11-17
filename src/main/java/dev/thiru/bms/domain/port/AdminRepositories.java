package dev.thiru.bms.domain.port;

import dev.thiru.bms.domain.Cinema;
import dev.thiru.bms.domain.City;
import dev.thiru.bms.domain.Movie;
import dev.thiru.bms.domain.Page;
import dev.thiru.bms.domain.Screen;
import dev.thiru.bms.domain.Show;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Write and reporting ports used only by the admin use cases. */
public interface AdminRepositories {

    /** Catalog writes. Id 0 on a save or insert means "assign one"; the saved copy carries it. */
    interface CatalogWrites {
        City saveCity(City city);

        Cinema saveCinema(Cinema cinema);

        boolean cinemaNameTaken(String cityId, String name, long exceptId);

        void deleteCinema(long id);

        boolean cinemaHasShows(long id);

        Screen saveScreen(Screen screen);

        boolean screenNameTaken(long cinemaId, String name, long exceptId);

        void deleteScreen(long id);

        boolean screenHasShows(long id);

        /** SCHEDULED shows on the screen starting after {@code now}. */
        boolean screenHasFutureShows(long id, Instant now);

        Movie saveMovie(Movie movie);

        void deleteMovie(long id);

        boolean movieHasShows(long id);

        Show insertShow(Show show);
    }

    /** One show's sales. {@code paid} and {@code refunded} are paise. */
    record ShowStat(
            long showId,
            String movieTitle,
            String cinemaName,
            Instant startsAt,
            int capacity,
            int booked,
            long paid,
            long refunded) {}

    record CategoryStat(String category, int capacity, int booked, long revenue) {}

    interface Reports {
        /** Shows of the city (all cities when null) starting in [from, to), by start time. */
        Page<ShowStat> showStats(String cityId, Instant from, Instant to, int page, int size);

        Optional<ShowStat> showStat(long showId);

        List<CategoryStat> categoryStats(long showId);

        long countConfirmedSince(Instant since);
    }
}
