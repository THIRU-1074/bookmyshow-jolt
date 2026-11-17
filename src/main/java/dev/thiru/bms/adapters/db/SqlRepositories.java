package dev.thiru.bms.adapters.db;

import dev.thiru.bms.domain.port.BookingRepositories;
import dev.thiru.bms.domain.port.CatalogRepositories;
import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.SeatLockRepository;
import in.jolt.db.Sql;

/** All repositories over the single connection of one open transaction. */
final class SqlRepositories implements Repositories {

    private final Sql sql;

    SqlRepositories(Sql sql) {
        this.sql = sql;
    }

    @Override
    public CatalogRepositories.Cities cities() {
        return new SqlCatalog.Cities(sql);
    }

    @Override
    public CatalogRepositories.Cinemas cinemas() {
        return new SqlCatalog.Cinemas(sql);
    }

    @Override
    public CatalogRepositories.Screens screens() {
        return new SqlCatalog.Screens(sql);
    }

    @Override
    public CatalogRepositories.Movies movies() {
        return new SqlCatalog.Movies(sql);
    }

    @Override
    public CatalogRepositories.Shows shows() {
        return new SqlCatalog.Shows(sql);
    }

    @Override
    public BookingRepositories.Users users() {
        return new SqlBookings.Users(sql);
    }

    @Override
    public BookingRepositories.Holds holds() {
        return new SqlBookings.Holds(sql);
    }

    @Override
    public SeatLockRepository locks() {
        return new SqlSeatLocks(sql);
    }

    @Override
    public BookingRepositories.Bookings bookings() {
        return new SqlBookings.Bookings(sql);
    }

    @Override
    public BookingRepositories.Payments payments() {
        return new SqlBookings.Payments(sql);
    }

    @Override
    public BookingRepositories.PaymentEvents paymentEvents() {
        return new SqlBookings.PaymentEvents(sql);
    }
}
