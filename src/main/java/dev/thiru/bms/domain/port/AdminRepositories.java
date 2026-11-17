package dev.thiru.bms.domain.port;

import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.kernel.catalog.Cinema;
import dev.thiru.bms.kernel.catalog.City;
import dev.thiru.bms.kernel.catalog.Movie;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Write, reporting and access-management ports used by the admin and theatre owner use cases. */
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
            ShowStatus status,
            int capacity,
            int booked,
            long paid,
            long refunded) {}

    record CategoryStat(String category, int capacity, int booked, long revenue) {}

    interface Reports {
        /**
         * Shows of the city (all cities when null) starting in [from, to), by start time. A
         * non-null {@code ownerId} keeps only cinemas linked to that user.
         */
        Page<ShowStat> showStats(
                String cityId, Long ownerId, Instant from, Instant to, int page, int size);

        Optional<ShowStat> showStat(long showId);

        List<CategoryStat> categoryStats(long showId);

        long countConfirmedSince(Instant since);
    }

    /** Links between theatre owners and the cinemas they manage (SPEC 3, table cinema_owner). */
    interface CinemaOwners {
        /** Cinemas linked to the user, by name; every cinema when {@code userId} is null. */
        List<Cinema> cinemas(Long userId);

        /** Ids of the cinemas linked to the user, ascending. */
        List<Long> cinemaIdsOf(long userId);

        boolean owns(long userId, long cinemaId);

        /** Does nothing when the link exists. */
        void link(long cinemaId, long userId);

        /** Does nothing when there is no link. */
        void unlink(long cinemaId, long userId);

        void unlinkAll(long userId);
    }

    /** Account listing and role changes for administrators. */
    interface UserRoles {
        /**
         * Users ordered by id. A non-null {@code q} keeps users whose email or name contains it,
         * ignoring case.
         */
        Page<User> page(String q, int page, int size);

        void saveRoles(long userId, Set<Role> roles);
    }
}
