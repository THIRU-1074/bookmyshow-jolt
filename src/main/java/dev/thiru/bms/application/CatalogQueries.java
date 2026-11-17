package dev.thiru.bms.application;

import dev.thiru.bms.domain.port.CatalogRepositories.MovieQuery;
import dev.thiru.bms.domain.port.CatalogRepositories.ShowListing;
import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.UnitOfWork;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.kernel.booking.SeatLock;
import dev.thiru.bms.kernel.catalog.Certificate;
import dev.thiru.bms.kernel.catalog.Cinema;
import dev.thiru.bms.kernel.catalog.City;
import dev.thiru.bms.kernel.catalog.Movie;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.SeatLayout;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only catalog and seat-map queries. */
public final class CatalogQueries {

    public enum Availability {
        AVAILABLE,
        FILLING_FAST,
        SOLD_OUT
    }

    public enum SeatStatus {
        AVAILABLE,
        HELD,
        BOOKED,
        BLOCKED
    }

    public record MovieFilter(
            String cityId,
            LocalDate date,
            String q,
            Set<String> genres,
            Set<String> languages,
            Set<Certificate> certificates,
            Set<ShowFormat> formats,
            MovieQuery.Sort sort) {}

    public record ShowSlot(
            Show show, LocalTime localTime, long minPrice, Availability availability) {}

    public record CinemaShows(Cinema cinema, List<ShowSlot> shows) {}

    public record ShowTimes(LocalDate date, List<CinemaShows> cinemas) {}

    public record CinemaDetail(Cinema cinema, List<Screen> screens) {}

    public record PriceLine(String category, String name, long amount) {}

    public record ShowDetail(
            Show show,
            Movie movie,
            Cinema cinema,
            Screen screen,
            City city,
            LocalTime localTime,
            List<PriceLine> prices) {}

    public record SeatCell(SeatId id, int number, String category, SeatStatus status) {}

    public record SeatRow(String row, List<SeatCell> seats, List<Integer> aisleAfter) {}

    /**
     * {@code version} is a content hash of every seat status, so it changes exactly when the map
     * does.
     */
    public record SeatMap(
            long showId,
            long version,
            List<PriceLine> categories,
            List<SeatRow> rows,
            long holdTtlSeconds) {}

    private final UnitOfWork uow;
    private final Clock clock;
    private final BookingPolicy policy;

    public CatalogQueries(UnitOfWork uow, Clock clock, BookingPolicy policy) {
        this.uow = uow;
        this.clock = clock;
        this.policy = policy;
    }

    public List<City> cities() {
        return uow.inTransaction(r -> r.cities().findAll());
    }

    public Page<Movie> movies(MovieFilter f, int page, int size) {
        return uow.inTransaction(
                r -> {
                    String q = f.q() == null || f.q().isBlank() ? null : f.q().trim();
                    if (q != null && q.length() < 2) {
                        throw DomainException.validation("q", "must be at least 2 characters");
                    }
                    Instant from = null;
                    Instant to = null;
                    if (f.cityId() != null) {
                        City city = city(r, f.cityId());
                        Instant[] window = window(city, f.date());
                        from = window[0];
                        to = window[1];
                    }
                    return r.movies()
                            .search(
                                    new MovieQuery(
                                            f.cityId(),
                                            from,
                                            to,
                                            q,
                                            f.genres(),
                                            f.languages(),
                                            f.certificates(),
                                            f.formats(),
                                            f.sort()),
                                    page,
                                    size);
                });
    }

    public Movie movie(long id) {
        return uow.inTransaction(
                r -> r.movies().findById(id).orElseThrow(() -> DomainException.notFound("Movie")));
    }

    /** Future SCHEDULED shows of the movie on one local date, grouped by cinema. */
    public ShowTimes showTimes(
            long movieId,
            String cityId,
            LocalDate date,
            Set<String> languages,
            Set<ShowFormat> formats) {
        return uow.inTransaction(
                r -> {
                    City city = city(r, cityId);
                    LocalDate day = date != null ? date : today(city);
                    Instant[] window = window(city, day);
                    List<ShowListing> listings =
                            r.shows()
                                    .findListings(
                                            movieId, cityId, window[0], window[1], languages,
                                            formats);
                    Map<Long, Integer> taken =
                            r.locks()
                                    .countLive(
                                            listings.stream().map(l -> l.show().id()).toList(),
                                            clock.instant());
                    Map<Long, CinemaShows> byCinema = new LinkedHashMap<>();
                    Map<Long, List<ShowSlot>> slots = new LinkedHashMap<>();
                    for (ShowListing l : listings) {
                        slots.computeIfAbsent(l.cinema().id(), k -> new ArrayList<>())
                                .add(
                                        new ShowSlot(
                                                l.show(),
                                                l.show()
                                                        .startsAt()
                                                        .atZone(city.timeZone())
                                                        .toLocalTime(),
                                                l.show().minPrice(),
                                                availability(
                                                        l.screen().capacity(),
                                                        taken.getOrDefault(l.show().id(), 0))));
                        byCinema.putIfAbsent(l.cinema().id(), new CinemaShows(l.cinema(), null));
                    }
                    List<CinemaShows> out = new ArrayList<>();
                    byCinema.forEach(
                            (id, c) ->
                                    out.add(
                                            new CinemaShows(
                                                    c.cinema(), List.copyOf(slots.get(id)))));
                    return new ShowTimes(day, out);
                });
    }

    public Page<Cinema> cinemas(String cityId, int page, int size) {
        return uow.inTransaction(r -> r.cinemas().page(cityId, page, size));
    }

    public CinemaDetail cinema(long id) {
        return uow.inTransaction(
                r -> {
                    Cinema cinema =
                            r.cinemas()
                                    .findById(id)
                                    .orElseThrow(() -> DomainException.notFound("Cinema"));
                    return new CinemaDetail(cinema, r.screens().findByCinema(id));
                });
    }

    public ShowDetail show(long id) {
        return uow.inTransaction(
                r -> {
                    Show show =
                            r.shows()
                                    .findById(id)
                                    .orElseThrow(() -> DomainException.notFound("Show"));
                    Screen screen = r.screens().findById(show.screenId()).orElseThrow();
                    Cinema cinema = r.cinemas().findById(screen.cinemaId()).orElseThrow();
                    City city = city(r, cinema.cityId());
                    Movie movie = r.movies().findById(show.movieId()).orElseThrow();
                    return new ShowDetail(
                            show,
                            movie,
                            cinema,
                            screen,
                            city,
                            show.startsAt().atZone(city.timeZone()).toLocalTime(),
                            prices(show, screen.layout()));
                });
    }

    public SeatMap seatMap(long showId) {
        return uow.inTransaction(
                r -> {
                    Show show =
                            r.shows()
                                    .findById(showId)
                                    .orElseThrow(() -> DomainException.notFound("Show"));
                    SeatLayout layout =
                            r.screens().findById(show.screenId()).orElseThrow().layout();
                    Instant now = clock.instant();
                    Map<SeatId, SeatStatus> taken = new HashMap<>();
                    for (SeatLock l : r.locks().findByShow(showId)) {
                        if (l.isLiveAt(now)) {
                            taken.put(
                                    l.seat(),
                                    l.state() == SeatLock.State.BOOKED
                                            ? SeatStatus.BOOKED
                                            : SeatStatus.HELD);
                        }
                    }
                    List<SeatRow> rows = new ArrayList<>();
                    List<Object> fingerprint = new ArrayList<>();
                    for (SeatLayout.Row row : layout.rows()) {
                        List<SeatCell> cells = new ArrayList<>();
                        for (int n = 1; n <= row.seats(); n++) {
                            SeatId id = new SeatId(row.row().charAt(0), n);
                            SeatStatus status =
                                    layout.isBlocked(id)
                                            ? SeatStatus.BLOCKED
                                            : taken.getOrDefault(id, SeatStatus.AVAILABLE);
                            cells.add(new SeatCell(id, n, row.category(), status));
                            fingerprint.add(status);
                        }
                        rows.add(new SeatRow(row.row(), cells, row.aisleAfter()));
                    }
                    return new SeatMap(
                            showId,
                            Arrays.hashCode(fingerprint.toArray()) & 0xffffffffL,
                            prices(show, layout),
                            rows,
                            policy.holdTtl().toSeconds());
                });
    }

    private static List<PriceLine> prices(Show show, SeatLayout layout) {
        return layout.categories().stream()
                .map(c -> new PriceLine(c.code(), c.name(), show.prices().get(c.code())))
                .toList();
    }

    // Under 20 percent free is "filling fast"; integer math keeps the boundary exact.
    private static Availability availability(int capacity, int taken) {
        int free = Math.max(0, capacity - taken);
        if (free == 0) {
            return Availability.SOLD_OUT;
        }
        return free * 5 < capacity ? Availability.FILLING_FAST : Availability.AVAILABLE;
    }

    private static City city(Repositories r, String id) {
        return r.cities().findById(id).orElseThrow(() -> DomainException.notFound("City"));
    }

    private LocalDate today(City city) {
        return clock.instant().atZone(city.timeZone()).toLocalDate();
    }

    /** The local day as a UTC interval, never starting in the past: started shows are not sold. */
    private Instant[] window(City city, LocalDate date) {
        ZoneId zone = city.timeZone();
        LocalDate day = date != null ? date : today(city);
        Instant start = day.atStartOfDay(zone).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
        // A show starting exactly now is already closed to sales, and the repository interval is
        // [from, to), so the earliest sellable start is one millisecond ahead.
        Instant earliest = clock.instant().plusMillis(1);
        return new Instant[] {start.isBefore(earliest) ? earliest : start, end};
    }
}
