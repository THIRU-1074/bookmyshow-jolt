package dev.thiru.bms.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HoldPaymentUserTest {

    static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    static final BookingPolicy POLICY = BookingPolicy.defaults();
    static final Screen SCREEN = new Screen(5, 2, "S1", SeatLayoutTest.sample());
    static final Movie MOVIE =
            new Movie(
                    1,
                    "M",
                    List.of("en"),
                    List.of("Drama"),
                    Certificate.U,
                    100,
                    LocalDate.of(2026, 1, 1),
                    "/p.png",
                    "");
    static final Show SHOW =
            Show.schedule(
                    9,
                    MOVIE,
                    SCREEN,
                    NOW.plus(Duration.ofDays(1)),
                    "en",
                    ShowFormat.TWO_D,
                    Map.of("CLASSIC", 15000L, "PRIME", 25000L),
                    List.of());

    private static SeatHold hold(String... seats) {
        return SeatHold.create("h1", SHOW, SCREEN, 7, List.of(seats), NOW, POLICY);
    }

    private static DomainException.Code codeOf(Runnable r) {
        return assertThrows(DomainException.class, r::run).code();
    }

    @Test
    void holdExpiresAtCreatedPlusTtl() {
        SeatHold h = hold("A2", "B1");
        assertEquals(HoldStatus.ACTIVE, h.status());
        assertEquals(NOW.plus(Duration.ofMinutes(7)), h.expiresAt());
        assertEquals(List.of(SeatId.parse("A2"), SeatId.parse("B1")), h.seats());
        assertFalse(h.isExpiredAt(h.expiresAt().minusMillis(1)));
        assertTrue(h.isExpiredAt(h.expiresAt()));
    }

    @Test
    void holdSeatValidationReportsPerIndex() {
        Map<String, String> errors =
                assertThrows(
                                DomainException.class,
                                () -> hold("A2", "A2", "Z9", "A1", "nope", "B3"))
                        .errors();
        assertEquals("duplicate seat", errors.get("seats[1]"));
        assertEquals("unknown seat", errors.get("seats[2]"));
        assertEquals("seat is blocked", errors.get("seats[3]"));
        assertEquals("invalid seat id", errors.get("seats[4]"));
        assertFalse(errors.containsKey("seats[0]"));
        assertFalse(errors.containsKey("seats[5]"));
    }

    @Test
    void holdSizeLimits() {
        assertTrue(assertThrows(DomainException.class, () -> hold()).errors().containsKey("seats"));
        String[] eleven = {"A2", "A3", "A5", "A6", "A7", "A8", "A9", "A11", "A12", "A13", "A14"};
        assertTrue(
                assertThrows(DomainException.class, () -> hold(eleven))
                        .errors()
                        .containsKey("seats"));
        assertEquals(10, hold(java.util.Arrays.copyOf(eleven, 10)).seats().size());
    }

    @Test
    void holdsCloseAtShowStartAndForCancelledShows() {
        Instant atStart = SHOW.startsAt();
        assertEquals(
                DomainException.Code.SHOW_STARTED,
                codeOf(
                        () ->
                                SeatHold.create(
                                        "h", SHOW, SCREEN, 7, List.of("A2"), atStart, POLICY)));
        assertEquals(
                DomainException.Code.BOOKING_STATE,
                codeOf(
                        () ->
                                SeatHold.create(
                                        "h",
                                        SHOW.cancel(),
                                        SCREEN,
                                        7,
                                        List.of("A2"),
                                        NOW,
                                        POLICY)));
    }

    @Test
    void holdStateMoves() {
        SeatHold h = hold("A2");
        assertEquals(HoldStatus.CONSUMED, h.consume(NOW.plusSeconds(1)).status());
        assertEquals(HoldStatus.RELEASED, h.release().status());
        assertEquals(HoldStatus.EXPIRED, h.expire().status());
        assertEquals(DomainException.Code.HOLD_EXPIRED, codeOf(() -> h.consume(h.expiresAt())));
        SeatHold used = h.consume(NOW);
        assertEquals(DomainException.Code.BOOKING_STATE, codeOf(() -> used.consume(NOW)));
        assertEquals(DomainException.Code.BOOKING_STATE, codeOf(used::release));
    }

    @Test
    void paymentLifecycle() {
        Booking b = BookingTest.booking(BookingStatus.PAYMENT_PENDING);
        Payment p = Payment.initiate("p1", b, "gw_1", NOW);
        assertEquals(PaymentStatus.INITIATED, p.status());
        assertEquals(40000, p.amount());
        assertTrue(p.matches(40000, "INR"));
        assertFalse(p.matches(39999, "INR"));
        assertFalse(p.matches(40000, "USD"));
        assertEquals(PaymentStatus.SUCCEEDED, p.succeed(NOW).status());
        assertEquals(PaymentStatus.FAILED, p.fail(NOW).status());
        Payment ok = p.succeed(NOW);
        Payment refunded = ok.refund(20000, NOW);
        assertEquals(PaymentStatus.REFUNDED, refunded.status());
        assertEquals(20000, refunded.refundedAmount());
        assertThrows(IllegalStateException.class, () -> p.refund(1, NOW));
        assertThrows(IllegalStateException.class, () -> ok.refund(40001, NOW));
        assertThrows(IllegalStateException.class, () -> ok.succeed(NOW));
        assertThrows(IllegalStateException.class, () -> p.fail(NOW).succeed(NOW));
    }

    @Test
    void userRules() {
        User u = new User(1, "Ann@Example.COM", "hash", "Ann", Set.of(Role.AUDIENCE), NOW);
        assertEquals("ann@example.com", u.email());
        assertThrows(
                DomainException.class,
                () -> new User(1, "no-at", "hash", "Ann", Set.of(Role.AUDIENCE), NOW));
        assertThrows(
                DomainException.class, () -> new User(1, "a@b.c", "hash", "Ann", Set.of(), NOW));
        assertThrows(
                DomainException.class,
                () -> new User(1, "a@b.c", "", "Ann", Set.of(Role.AUDIENCE), NOW));
        User.validatePassword("x".repeat(10));
        User.validatePassword("x".repeat(128));
        assertThrows(DomainException.class, () -> User.validatePassword("x".repeat(9)));
        assertThrows(DomainException.class, () -> User.validatePassword("x".repeat(129)));
        assertThrows(DomainException.class, () -> User.validatePassword(null));
    }
}
