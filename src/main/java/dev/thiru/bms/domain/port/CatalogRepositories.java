package dev.thiru.bms.domain.port;

import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.catalog.Certificate;
import dev.thiru.bms.kernel.catalog.Cinema;
import dev.thiru.bms.kernel.catalog.City;
import dev.thiru.bms.kernel.catalog.Movie;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Read and write ports for catalog data. */
public interface CatalogRepositories {

    interface Cities {
        List<City> findAll();

        Optional<City> findById(String id);
    }

    interface Cinemas {
        Optional<Cinema> findById(long id);

        Page<Cinema> page(String cityId, int page, int size);
    }

    interface Screens {
        Optional<Screen> findById(long id);

        List<Screen> findByCinema(long cinemaId);
    }

    /**
     * Movie filter. Empty sets mean "any"; {@code from}/{@code to} (with {@code cityId}) restrict
     * to movies with a SCHEDULED show starting in [from, to) in that city.
     */
    record MovieQuery(
            String cityId,
            Instant from,
            Instant to,
            String q,
            Set<String> genres,
            Set<String> languages,
            Set<Certificate> certificates,
            Set<ShowFormat> formats,
            Sort sort) {
        public enum Sort {
            RELEASE,
            TITLE
        }
    }

    interface Movies {
        Optional<Movie> findById(long id);

        Page<Movie> search(MovieQuery query, int page, int size);
    }

    /** A show joined with where it plays. */
    record ShowListing(Show show, Cinema cinema, Screen screen) {}

    interface Shows {
        Optional<Show> findById(long id);

        Show save(Show show);

        /**
         * SCHEDULED shows of the movie in the city starting in [from, to), ordered by cinema then
         * time.
         */
        List<ShowListing> findListings(
                long movieId,
                String cityId,
                Instant from,
                Instant to,
                Set<String> languages,
                Set<ShowFormat> formats);

        List<Show> findByScreen(long screenId);
    }
}
