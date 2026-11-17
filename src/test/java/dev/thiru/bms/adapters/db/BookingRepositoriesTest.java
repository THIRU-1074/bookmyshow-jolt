package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.domain.Booking;
import dev.thiru.bms.domain.BookingPolicy;
import dev.thiru.bms.domain.BookingSeat;
import dev.thiru.bms.domain.BookingStatus;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.HoldStatus;
import dev.thiru.bms.domain.Page;
import dev.thiru.bms.domain.Payment;
import dev.thiru.bms.domain.PaymentStatus;
import dev.thiru.bms.domain.Role;
import dev.thiru.bms.domain.SeatHold;
import dev.thiru.bms.domain.SeatId;
import dev.thiru.bms.domain.User;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BookingRepositoriesTest extends DbTest {

    private static final Instant EXPIRES = NOW.plus(Duration.ofMinutes(7));

    long show;
    long user;
    int seq;

    @BeforeEach
    void fixtures() {
        show = anyShowId();
        user = newUser("buyer@example.com");
    }

    private SeatHold hold(String id, long owner, Instant expires, HoldStatus status) {
        SeatHold h =
                new SeatHold(
                        id,
                        show,
                        owner,
                        List.of(SeatId.parse("A1"), SeatId.parse("A2")),
                        NOW,
                        expires,
                        status);
        tx(
                r -> {
                    r.holds().insert(h);
                    return null;
                });
        return h;
    }

    private Booking booking(String id, long owner, Instant created, Instant expires) {
        String holdId = "hold-" + id;
        hold(holdId, owner, expires, HoldStatus.ACTIVE);
        Booking b =
                Booking.create(
                        id,
                        String.format("BK%08d", ++seq),
                        owner,
                        show,
                        holdId,
                        List.of(
                                new BookingSeat(SeatId.parse("A2"), "PRIME", 28000),
                                new BookingSeat(SeatId.parse("A1"), "PRIME", 28000)),
                        created,
                        expires);
        tx(
                r -> {
                    r.bookings().insert(b);
                    return null;
                });
        return b;
    }

    @Test
    void usersRoundTripAndEmailsAreCaseInsensitiveAndUnique() {
        User saved =
                tx(
                        r ->
                                r.users()
                                        .insert(
                                                new User(
                                                        0,
                                                        "Admin@Example.com",
                                                        "h",
                                                        "Ada",
                                                        Set.of(Role.AUDIENCE, Role.ADMIN),
                                                        NOW)));
        assertTrue(saved.id() > 0);
        assertEquals("admin@example.com", saved.email());
        assertEquals(saved, tx(r -> r.users().findById(saved.id())).orElseThrow());
        assertEquals(saved, tx(r -> r.users().findByEmail("ADMIN@example.com")).orElseThrow());
        assertTrue(tx(r -> r.users().findByEmail("nobody@example.com")).isEmpty());
        assertTrue(tx(r -> r.users().findById(999_999)).isEmpty());
        DomainException dup =
                assertThrows(
                        DomainException.class,
                        () ->
                                tx(
                                        r ->
                                                r.users()
                                                        .insert(
                                                                new User(
                                                                        0,
                                                                        "admin@example.com",
                                                                        "h",
                                                                        "Dup",
                                                                        Set.of(Role.AUDIENCE),
                                                                        NOW))));
        assertEquals(DomainException.Code.VALIDATION, dup.code());
        assertTrue(dup.errors().containsKey("email"));
    }

    @Test
    void holdsRoundTripAndAreFoundByStatusAndOwner() {
        SeatHold h = hold("h1", user, EXPIRES, HoldStatus.ACTIVE);
        assertEquals(h, tx(r -> r.holds().findById("h1")).orElseThrow());
        assertEquals(h, tx(r -> r.holds().findActiveByUserAndShow(user, show)).orElseThrow());
        assertTrue(tx(r -> r.holds().findActiveByUserAndShow(user + 1, show)).isEmpty());
        assertEquals(List.of(h), tx(r -> r.holds().findActiveByShow(show)));
        assertTrue(tx(r -> r.holds().findById("nope")).isEmpty());

        tx(
                r -> {
                    r.holds().update(h.release());
                    return null;
                });
        assertEquals(HoldStatus.RELEASED, tx(r -> r.holds().findById("h1")).orElseThrow().status());
        assertTrue(tx(r -> r.holds().findActiveByUserAndShow(user, show)).isEmpty());
        assertTrue(tx(r -> r.holds().findActiveByShow(show)).isEmpty());
        assertThrows(
                DomainException.class,
                () ->
                        tx(
                                r -> {
                                    r.holds()
                                            .update(
                                                    new SeatHold(
                                                            "ghost",
                                                            show,
                                                            user,
                                                            List.of(SeatId.parse("A1")),
                                                            NOW,
                                                            EXPIRES,
                                                            HoldStatus.EXPIRED));
                                    return null;
                                }));
    }

    @Test
    void expiredActiveHoldsAreOldestFirstLimitedAndCounted() {
        long other = newUser("other@example.com");
        hold("late", user, NOW.plusSeconds(20), HoldStatus.ACTIVE);
        hold("early", other, NOW.plusSeconds(10), HoldStatus.ACTIVE);
        hold("done", other, NOW.plusSeconds(5), HoldStatus.RELEASED);
        hold("fresh", other, NOW.plusSeconds(500), HoldStatus.ACTIVE);

        Instant now = NOW.plusSeconds(20);
        assertEquals(
                List.of("early", "late"),
                tx(r -> r.holds().findExpiredActive(now, 10)).stream().map(SeatHold::id).toList());
        assertEquals(1, tx(r -> r.holds().findExpiredActive(now, 1)).size());
        assertTrue(tx(r -> r.holds().findExpiredActive(NOW.plusSeconds(9), 10)).isEmpty());
        assertEquals(1, (long) tx(r -> r.holds().countActive(now)));
        assertEquals(3, (long) tx(r -> r.holds().countActive(NOW)));
    }

    @Test
    void bookingsRoundTripWithSeatsInBuyerOrder() {
        Booking b = booking("b1", user, NOW, EXPIRES);
        Booking loaded = tx(r -> r.bookings().findById("b1")).orElseThrow();
        assertEquals(b, loaded);
        assertEquals(
                List.of(SeatId.parse("A2"), SeatId.parse("A1")),
                loaded.seats().stream().map(BookingSeat::seat).toList());
        assertTrue(tx(r -> r.bookings().findById("nope")).isEmpty());
        assertTrue(flag(r -> r.bookings().existsByCode(b.code())));
        assertFalse(flag(r -> r.bookings().existsByCode("BKZZZZZZZZ")));
    }

    @Test
    void updateBumpsTheVersionAndStaleWritesConflict() {
        Booking b = booking("b1", user, NOW, EXPIRES);
        Booking pending =
                tx(
                        r ->
                                r.bookings()
                                        .update(
                                                b.startCheckout(
                                                        NOW.plusSeconds(1),
                                                        BookingPolicy.defaults())));
        assertEquals(1, pending.version());
        assertEquals(BookingStatus.PAYMENT_PENDING, pending.status());
        Booking stored = tx(r -> r.bookings().findById("b1")).orElseThrow();
        assertEquals(pending, stored);
        assertEquals(1, stored.checkoutCount());

        // The original copy still carries version 0, so a second write through it is stale.
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () -> tx(r -> r.bookings().update(b.expire(NOW.plusSeconds(2)))));
        assertEquals(DomainException.Code.VERSION_CONFLICT, e.code());
        assertEquals(
                BookingStatus.PAYMENT_PENDING,
                tx(r -> r.bookings().findById("b1")).orElseThrow().status());

        Booking confirmed =
                tx(r -> r.bookings().update(pending.paymentSucceeded(NOW.plusSeconds(3))));
        assertEquals(2, confirmed.version());
    }

    @Test
    void pagesAreNewestFirstOneBasedAndFilteredByStatus() {
        long other = newUser("other@example.com");
        booking("b1", user, NOW, EXPIRES);
        Booking b2 = booking("b2", user, NOW.plusSeconds(1), EXPIRES);
        booking("b3", user, NOW.plusSeconds(2), EXPIRES);
        booking("x1", other, NOW, EXPIRES);
        tx(r -> r.bookings().update(b2.expire(NOW.plusSeconds(9))));

        Page<Booking> first = tx(r -> r.bookings().page(user, Set.of(), 1, 2));
        assertEquals(3, first.total());
        assertEquals(List.of("b3", "b2"), first.items().stream().map(Booking::id).toList());
        assertEquals(
                List.of("b1"),
                tx(r -> r.bookings().page(user, Set.of(), 2, 2)).items().stream()
                        .map(Booking::id)
                        .toList());
        Page<Booking> expired =
                tx(r -> r.bookings().page(user, Set.of(BookingStatus.EXPIRED), 1, 10));
        assertEquals(List.of("b2"), expired.items().stream().map(Booking::id).toList());
        assertEquals(2, expired.items().get(0).seats().size());
        assertEquals(
                3,
                tx(r ->
                                r.bookings()
                                        .page(
                                                user,
                                                Set.of(BookingStatus.HELD, BookingStatus.EXPIRED),
                                                1,
                                                10))
                        .total());
    }

    @Test
    void openExpiredAndLiveByShowFollowStatusAndExpiry() {
        booking("held", user, NOW, NOW.plusSeconds(10));
        Booking pending = booking("pending", user, NOW, NOW.plusSeconds(20));
        Booking confirmed = booking("confirmed", user, NOW, NOW.plusSeconds(30));
        Booking gone = booking("gone", user, NOW, NOW.plusSeconds(5));
        tx(r -> r.bookings().update(pending.startCheckout(NOW, BookingPolicy.defaults())));
        Booking checkedOut =
                tx(
                        r ->
                                r.bookings()
                                        .update(
                                                confirmed.startCheckout(
                                                        NOW, BookingPolicy.defaults())));
        tx(r -> r.bookings().update(checkedOut.paymentSucceeded(NOW)));
        tx(r -> r.bookings().update(gone.expire(NOW)));

        Instant now = NOW.plus(Duration.ofHours(1));
        assertEquals(
                Set.of("held", "pending"),
                tx(r -> r.bookings().findOpenExpired(now, 10)).stream()
                        .map(Booking::id)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, tx(r -> r.bookings().findOpenExpired(now, 1)).size());
        assertTrue(tx(r -> r.bookings().findOpenExpired(NOW.plusSeconds(6), 10)).isEmpty());

        assertEquals(
                Set.of("held", "pending", "confirmed"),
                tx(r -> r.bookings().findLiveByShow(show)).stream()
                        .map(Booking::id)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void paymentsRoundTripAndLatestWins() {
        Booking b = booking("b1", user, NOW, EXPIRES);
        Payment first = Payment.initiate("p1", b, "gw-1", NOW);
        Payment second = Payment.initiate("p2", b, "gw-2", NOW.plusSeconds(5));
        tx(
                r -> {
                    r.payments().insert(first);
                    r.payments().insert(second);
                    return null;
                });
        assertEquals(first, tx(r -> r.payments().findById("p1")).orElseThrow());
        assertEquals(second, tx(r -> r.payments().findLatestByBooking("b1")).orElseThrow());
        assertTrue(tx(r -> r.payments().findLatestByBooking("none")).isEmpty());
        assertTrue(tx(r -> r.payments().findById("none")).isEmpty());

        Payment refunded = second.succeed(NOW.plusSeconds(6)).refund(10000, NOW.plusSeconds(7));
        tx(
                r -> {
                    r.payments().update(refunded);
                    return null;
                });
        assertEquals(refunded, tx(r -> r.payments().findById("p2")).orElseThrow());
        assertEquals(PaymentStatus.REFUNDED, refunded.status());
        assertThrows(
                DomainException.class,
                () ->
                        tx(
                                r -> {
                                    r.payments().update(Payment.initiate("ghost", b, null, NOW));
                                    return null;
                                }));
    }

    @Test
    void paymentEventsDeduplicateByEventId() {
        assertTrue(
                flag(r -> r.paymentEvents().recordIfNew("evt-1", "p1", "payment.succeeded", NOW)));
        assertFalse(
                flag(r -> r.paymentEvents().recordIfNew("evt-1", "p1", "payment.succeeded", NOW)));
        assertTrue(flag(r -> r.paymentEvents().recordIfNew("evt-2", "p1", "payment.failed", NOW)));
    }
}
