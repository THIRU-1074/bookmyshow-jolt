package dev.thiru.bms.features.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.DomainException.Code;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import dev.thiru.bms.ports.Db;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CheckoutTest {

    @RegisterExtension final BookingWorld w = new BookingWorld();
    final long show = w.show(0);
    final long ada = w.user("ada@example.com");
    final Booking held = w.held(ada, show, "A2", "G1");

    @Test
    void movesToPaymentPendingAndOpensAPayment() {
        Checkout.Result r = w.checkout.execute(ada, held.id());
        Booking b = w.booking(held.id());
        assertEquals(BookingStatus.PAYMENT_PENDING, b.status());
        assertEquals(1, b.checkoutCount());
        assertEquals(1, b.version());
        assertEquals(46000, r.amount());
        assertEquals("INR", r.currency());
        assertEquals("http://pay.test/" + r.paymentId(), r.checkoutUrl());
        assertEquals(List.of(PaymentStatus.INITIATED), w.payments(held.id()));
        assertEquals(
                "gw_" + r.paymentId(),
                w.db.inTransaction(tx -> BookingSql.latestPayment(tx, held.id()))
                        .orElseThrow()
                        .gatewayRef());
        assertEquals(b.expiresAt(), r.expiresAt());
    }

    @Test
    void extendsBookingAndLockExpiryToThePaymentWindow() {
        Instant start = w.clock.instant();
        w.clock.advance(Duration.ofMinutes(6));
        Checkout.Result r = w.checkout.execute(ada, held.id());
        assertEquals(start.plus(Duration.ofMinutes(11)), r.expiresAt());
        assertTrue(
                w.locks().stream().allMatch(l -> l.endsWith(" " + r.expiresAt().toEpochMilli())));
    }

    @Test
    void onlyTheOwnerCanCheckOut() {
        long eve = w.user("eve@example.com");
        assertEquals(
                Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> w.checkout.execute(eve, held.id()))
                        .code());
    }

    @Test
    void secondCheckoutWhilePendingIsAStateError() {
        w.checkout.execute(ada, held.id());
        assertEquals(
                Code.BOOKING_STATE,
                assertThrows(DomainException.class, () -> w.checkout.execute(ada, held.id()))
                        .code());
        assertEquals(1, w.count("payment"));
    }

    @Test
    void fourthCheckoutIsRefused() {
        for (int i = 0; i < 3; i++) {
            Checkout.Result r = w.checkout.execute(ada, held.id());
            w.paymentFailed(r.paymentId(), held.id());
        }
        assertEquals(
                Code.LIMIT_EXCEEDED,
                assertThrows(DomainException.class, () -> w.checkout.execute(ada, held.id()))
                        .code());
        assertEquals(3, w.count("payment"));
    }

    @Test
    void expiredBookingGivesGoneAndIsExpiredAndFreed() {
        w.clock.advance(Duration.ofMinutes(7));
        DomainException e =
                assertThrows(DomainException.class, () -> w.checkout.execute(ada, held.id()));
        assertEquals(Code.HOLD_EXPIRED, e.code());
        assertEquals(BookingStatus.EXPIRED, w.bookingStatus(held.id()));
        assertEquals(0, w.count("seat_lock"));
        assertEquals(0, w.count("payment"));
        assertEquals(0, w.gateway.sessions.get());
    }

    @Test
    void versionConflictIsRetriedOnceThenSurfaces() {
        Checkout once = new Checkout(conflicting(1), w.gateway, w.events, w.ids, w.clock, w.policy);
        once.execute(ada, held.id());
        assertEquals(BookingStatus.PAYMENT_PENDING, w.bookingStatus(held.id()));

        Booking other = w.held(w.user("eve@example.com"), show, "B5");
        Checkout twice =
                new Checkout(conflicting(2), w.gateway, w.events, w.ids, w.clock, w.policy);
        long eve = other.userId();
        DomainException e =
                assertThrows(DomainException.class, () -> twice.execute(eve, other.id()));
        assertEquals(Code.VERSION_CONFLICT, e.code());
        assertEquals(BookingStatus.HELD, w.bookingStatus(other.id()));
        assertTrue(w.payments(other.id()).isEmpty(), "failed checkout leaves no payment behind");
    }

    /** {@code w.db}, but the next {@code misses} versioned booking writes find no row. */
    private Db conflicting(int misses) {
        AtomicInteger left = new AtomicInteger(misses);
        return new Db() {
            @Override
            public <T> T inTransaction(Function<Tx, T> work) {
                return w.db.inTransaction(tx -> work.apply(missing(tx, left)));
            }
        };
    }

    private static Db.Tx missing(Db.Tx tx, AtomicInteger left) {
        return new Db.Tx() {
            @Override
            public <T> List<T> query(String sql, Db.Row<T> row, Object... args) {
                return tx.query(sql, row, args);
            }

            @Override
            public <T> Optional<T> queryOne(String sql, Db.Row<T> row, Object... args) {
                return tx.queryOne(sql, row, args);
            }

            @Override
            public int update(String sql, Object... args) {
                if (sql.startsWith("UPDATE booking SET") && left.getAndDecrement() > 0) {
                    return 0;
                }
                return tx.update(sql, args);
            }

            @Override
            public long insert(String sql, Object... args) {
                return tx.insert(sql, args);
            }

            @Override
            public int[] batch(String sql, List<Object[]> rows) {
                return tx.batch(sql, rows);
            }

            @Override
            public void afterCommit(Runnable action) {
                tx.afterCommit(action);
            }
        };
    }
}
