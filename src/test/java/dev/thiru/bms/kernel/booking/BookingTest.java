package dev.thiru.bms.kernel.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

public class BookingTest {

    static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    static final Instant SHOW = NOW.plus(Duration.ofDays(3));
    static final BookingPolicy POLICY = BookingPolicy.defaults();

    public static Booking booking(BookingStatus status) {
        return new Booking(
                "b1",
                "BK0123ABCD",
                7,
                9,
                "h1",
                List.of(
                        new BookingSeat(SeatId.parse("A2"), "CLASSIC", 15000),
                        new BookingSeat(SeatId.parse("B1"), "PRIME", 25000)),
                40000,
                "INR",
                status,
                NOW.plusSeconds(300),
                0,
                0,
                0,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60));
    }

    private record Action(String name, BiFunction<Booking, Instant, Booking> run) {}

    private static final List<Action> ACTIONS =
            List.of(
                    new Action("checkout", (b, now) -> b.startCheckout(now, POLICY)),
                    new Action("paymentSucceeded", Booking::paymentSucceeded),
                    new Action("paymentFailed", Booking::paymentFailed),
                    new Action("expire", Booking::expire),
                    new Action("cancelByOwner", (b, now) -> b.cancelByOwner(now, SHOW)),
                    new Action("cancelByShow", Booking::cancelByShow));

    /** Expected result per (status, action): a status, or an error code. */
    private static Object expected(BookingStatus from, String action) {
        return switch (from) {
            case HELD ->
                    switch (action) {
                        case "checkout" -> BookingStatus.PAYMENT_PENDING;
                        case "expire" -> BookingStatus.EXPIRED;
                        case "cancelByOwner", "cancelByShow" -> BookingStatus.CANCELLED;
                        default -> DomainException.Code.BOOKING_STATE;
                    };
            case PAYMENT_PENDING ->
                    switch (action) {
                        case "paymentSucceeded" -> BookingStatus.CONFIRMED;
                        case "paymentFailed" -> BookingStatus.HELD;
                        case "expire", "cancelByShow" ->
                                action.equals("expire")
                                        ? BookingStatus.EXPIRED
                                        : BookingStatus.CANCELLED;
                        case "cancelByOwner" -> DomainException.Code.PAYMENT_IN_PROGRESS;
                        default -> DomainException.Code.BOOKING_STATE;
                    };
            case CONFIRMED ->
                    switch (action) {
                        case "cancelByOwner" -> BookingStatus.REFUNDED; // 3 days ahead
                        case "cancelByShow" -> BookingStatus.REFUNDED;
                        default -> DomainException.Code.BOOKING_STATE;
                    };
            default -> DomainException.Code.BOOKING_STATE;
        };
    }

    @Test
    void everyStatusActionPairFollowsTheTransitionTable() {
        for (BookingStatus from : BookingStatus.values()) {
            for (Action action : ACTIONS) {
                Object want = expected(from, action.name());
                String label = from + " + " + action.name();
                if (want instanceof BookingStatus status) {
                    Booking out = action.run().apply(booking(from), NOW);
                    assertEquals(status, out.status(), label);
                    assertEquals(NOW, out.updatedAt(), label);
                    assertTrue(from.canTransitionTo(out.status()) || from == out.status(), label);
                } else {
                    DomainException e =
                            assertThrows(
                                    DomainException.class,
                                    () -> action.run().apply(booking(from), NOW),
                                    label);
                    assertEquals(want, e.code(), label);
                }
            }
        }
    }

    @Test
    void transitionTableMatchesTheSpecDiagram() {
        assertEquals(
                Set.of(
                        BookingStatus.PAYMENT_PENDING,
                        BookingStatus.EXPIRED,
                        BookingStatus.CANCELLED),
                BookingStatus.HELD.next());
        assertEquals(
                Set.of(
                        BookingStatus.CONFIRMED,
                        BookingStatus.HELD,
                        BookingStatus.EXPIRED,
                        BookingStatus.CANCELLED),
                BookingStatus.PAYMENT_PENDING.next());
        assertEquals(
                Set.of(BookingStatus.REFUNDED, BookingStatus.CANCELLED),
                BookingStatus.CONFIRMED.next());
        for (BookingStatus t :
                List.of(BookingStatus.EXPIRED, BookingStatus.CANCELLED, BookingStatus.REFUNDED)) {
            assertTrue(t.isTerminal());
            assertTrue(t.next().isEmpty());
        }
        assertFalse(BookingStatus.HELD.isTerminal());
    }

    @Test
    void checkoutExtendsExpiryToAtLeastThePaymentWindowAndCounts() {
        Booking held = booking(BookingStatus.HELD); // expires in 300 s, window is 5 min = 300 s
        Booking later = held.startCheckout(NOW.plusSeconds(100), POLICY);
        assertEquals(NOW.plusSeconds(400), later.expiresAt());
        assertEquals(1, later.checkoutCount());
        // never shortens an expiry that is already further out
        Booking far =
                new Booking(
                        "b",
                        "BK0123ABCD",
                        1,
                        1,
                        "h",
                        held.seats(),
                        40000,
                        "INR",
                        BookingStatus.HELD,
                        NOW.plus(Duration.ofHours(1)),
                        0,
                        0,
                        0,
                        NOW,
                        NOW);
        assertEquals(NOW.plus(Duration.ofHours(1)), far.startCheckout(NOW, POLICY).expiresAt());
    }

    @Test
    void thirdCheckoutIsAllowedFourthIsLimited() {
        Booking b = booking(BookingStatus.HELD);
        for (int i = 0; i < 3; i++) {
            b = b.startCheckout(NOW, POLICY).paymentFailed(NOW);
        }
        assertEquals(3, b.checkoutCount());
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () ->
                                booking(BookingStatus.HELD)
                                        .withCheckoutCount(3)
                                        .startCheckout(NOW, POLICY));
        assertEquals(DomainException.Code.LIMIT_EXCEEDED, e.code());
    }

    @Test
    void expiryIsCheckedAtTheBoundary() {
        Booking pp = booking(BookingStatus.PAYMENT_PENDING);
        Instant expiry = pp.expiresAt();
        assertEquals(BookingStatus.CONFIRMED, pp.paymentSucceeded(expiry.minusMillis(1)).status());
        for (var action :
                List.<java.util.function.Function<Booking, Booking>>of(
                        b -> b.paymentSucceeded(expiry),
                        b -> b.paymentFailed(expiry),
                        b -> booking(BookingStatus.HELD).startCheckout(expiry, POLICY))) {
            DomainException e = assertThrows(DomainException.class, () -> action.apply(pp));
            assertEquals(DomainException.Code.HOLD_EXPIRED, e.code());
        }
        assertTrue(pp.isExpiredAt(expiry));
        assertFalse(pp.isExpiredAt(expiry.minusMillis(1)));
    }

    @Test
    void ownerCancelRefundsPerTheTimeBand() {
        Booking confirmed = booking(BookingStatus.CONFIRMED);
        Booking full = confirmed.cancelByOwner(NOW, SHOW);
        assertEquals(BookingStatus.REFUNDED, full.status());
        assertEquals(40000, full.refundAmount());
        Booking half = confirmed.cancelByOwner(SHOW.minus(Duration.ofHours(24)), SHOW);
        assertEquals(BookingStatus.REFUNDED, half.status());
        assertEquals(20000, half.refundAmount());
        Booking none = confirmed.cancelByOwner(SHOW.minus(Duration.ofHours(3)), SHOW);
        assertEquals(BookingStatus.CANCELLED, none.status());
        assertEquals(0, none.refundAmount());
        DomainException e =
                assertThrows(DomainException.class, () -> confirmed.cancelByOwner(SHOW, SHOW));
        assertEquals(DomainException.Code.SHOW_STARTED, e.code());
    }

    @Test
    void showCancelRefundsConfirmedBookingsInFull() {
        Booking out = booking(BookingStatus.CONFIRMED).cancelByShow(SHOW.minusSeconds(1));
        assertEquals(BookingStatus.REFUNDED, out.status());
        assertEquals(40000, out.refundAmount());
        assertEquals(0, booking(BookingStatus.HELD).cancelByShow(NOW).refundAmount());
    }

    @Test
    void createStartsHeldAtVersionZeroWithHoldExpiry() {
        Booking b = booking(BookingStatus.HELD);
        assertEquals(0, b.version());
        assertEquals(40000, b.seats().stream().mapToLong(BookingSeat::price).sum());
        assertThrows(
                DomainException.class,
                () ->
                        Booking.create(
                                "b", "BK0123ABCD", 1, 1, "h", List.of(), NOW, NOW.plusSeconds(1)));
    }
}
