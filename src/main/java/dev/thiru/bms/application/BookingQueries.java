package dev.thiru.bms.application;

import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.UnitOfWork;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.catalog.Cinema;
import dev.thiru.bms.kernel.catalog.City;
import dev.thiru.bms.kernel.catalog.Movie;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.Show;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/** Read side of bookings with ownership rules: strangers see "not found", never "forbidden". */
public final class BookingQueries {

    public record ShowInfo(
            long id,
            String movieTitle,
            String cinemaName,
            String screenName,
            Instant startsAt,
            LocalDate localDate,
            LocalTime localTime) {}

    public record BookingView(Booking booking, ShowInfo show) {}

    private final UnitOfWork uow;

    public BookingQueries(UnitOfWork uow) {
        this.uow = uow;
    }

    /** Owner or admin; anyone else gets NOT_FOUND so ids cannot be probed. */
    public BookingView get(long userId, boolean admin, String bookingId) {
        return uow.inTransaction(
                r -> {
                    Booking b =
                            r.bookings()
                                    .findById(bookingId)
                                    .filter(x -> admin || x.userId() == userId)
                                    .orElseThrow(() -> DomainException.notFound("Booking"));
                    return view(r, b);
                });
    }

    public Page<BookingView> list(long userId, Set<BookingStatus> statuses, int page, int size) {
        return uow.inTransaction(
                r -> r.bookings().page(userId, statuses, page, size).map(b -> view(r, b)));
    }

    /** Owner only, CONFIRMED only. */
    public BookingView ticket(long userId, String bookingId) {
        BookingView v = get(userId, false, bookingId);
        if (v.booking().status() != BookingStatus.CONFIRMED) {
            throw DomainException.bookingState("A ticket exists only for a confirmed booking");
        }
        return v;
    }

    private static BookingView view(Repositories r, Booking b) {
        Show show = r.shows().findById(b.showId()).orElseThrow();
        Screen screen = r.screens().findById(show.screenId()).orElseThrow();
        Cinema cinema = r.cinemas().findById(screen.cinemaId()).orElseThrow();
        City city = r.cities().findById(cinema.cityId()).orElseThrow();
        Movie movie = r.movies().findById(show.movieId()).orElseThrow();
        var local = show.startsAt().atZone(city.timeZone());
        return new BookingView(
                b,
                new ShowInfo(
                        show.id(),
                        movie.title(),
                        cinema.name(),
                        screen.name(),
                        show.startsAt(),
                        local.toLocalDate(),
                        local.toLocalTime()));
    }
}
