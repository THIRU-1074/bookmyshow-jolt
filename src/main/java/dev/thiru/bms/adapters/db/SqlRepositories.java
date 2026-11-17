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
        throw new UnsupportedOperationException();
    }

    @Override
    public BookingRepositories.Holds holds() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SeatLockRepository locks() {
        throw new UnsupportedOperationException();
    }

    @Override
    public BookingRepositories.Bookings bookings() {
        throw new UnsupportedOperationException();
    }

    @Override
    public BookingRepositories.Payments payments() {
        throw new UnsupportedOperationException();
    }

    @Override
    public BookingRepositories.PaymentEvents paymentEvents() {
        throw new UnsupportedOperationException();
    }
}
