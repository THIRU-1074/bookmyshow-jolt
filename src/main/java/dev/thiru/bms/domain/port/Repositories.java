package dev.thiru.bms.domain.port;

/** Every repository bound to the one open transaction of a {@link UnitOfWork}. */
public interface Repositories {

    CatalogRepositories.Cities cities();

    CatalogRepositories.Cinemas cinemas();

    CatalogRepositories.Screens screens();

    CatalogRepositories.Movies movies();

    CatalogRepositories.Shows shows();

    BookingRepositories.Users users();

    BookingRepositories.Holds holds();

    SeatLockRepository locks();

    BookingRepositories.Bookings bookings();

    BookingRepositories.Payments payments();

    BookingRepositories.PaymentEvents paymentEvents();
}
