package dev.thiru.bms.domain;

import java.time.Instant;
import java.util.List;

/**
 * A booking and its state machine. Instances are immutable; each transition returns a copy and
 * leaves {@code version} alone, because only the repository write increments it.
 */
public record Booking(
        String id,
        String code,
        long userId,
        long showId,
        String holdId,
        List<BookingSeat> seats,
        long amount,
        String currency,
        BookingStatus status,
        Instant expiresAt,
        long refundAmount,
        int checkoutCount,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static final String CURRENCY = "INR";

    public Booking {
        seats = List.copyOf(seats);
    }

    /** New booking in HELD, expiring with its hold. */
    public static Booking create(
            String id,
            String code,
            long userId,
            long showId,
            String holdId,
            List<BookingSeat> seats,
            Instant now,
            Instant holdExpiresAt) {
        long amount = seats.stream().mapToLong(BookingSeat::price).sum();
        if (seats.isEmpty() || amount <= 0) {
            throw DomainException.validation("seats", "a booking needs priced seats");
        }
        return new Booking(
                id,
                code,
                userId,
                showId,
                holdId,
                seats,
                amount,
                CURRENCY,
                BookingStatus.HELD,
                holdExpiresAt,
                0,
                0,
                0,
                now,
                now);
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public Booking withVersion(long newVersion) {
        return copy(status, expiresAt, refundAmount, checkoutCount, newVersion, updatedAt);
    }

    public Booking withCheckoutCount(int count) {
        return copy(status, expiresAt, refundAmount, count, version, updatedAt);
    }

    /** HELD to PAYMENT_PENDING; the expiry moves out to at least the payment window. */
    public Booking startCheckout(Instant now, BookingPolicy policy) {
        requireStatus(BookingStatus.HELD, "Only a held booking can be checked out");
        requireUnexpired(now);
        if (checkoutCount >= policy.maxCheckouts()) {
            throw DomainException.limitExceeded(
                    "At most " + policy.maxCheckouts() + " checkouts per booking");
        }
        Instant window = now.plus(policy.paymentWindow());
        Instant newExpiry = window.isAfter(expiresAt) ? window : expiresAt;
        return copy(
                BookingStatus.PAYMENT_PENDING,
                newExpiry,
                refundAmount,
                checkoutCount + 1,
                version,
                now);
    }

    /** PAYMENT_PENDING to CONFIRMED. Lock ownership and amount are checked by the caller. */
    public Booking paymentSucceeded(Instant now) {
        requireStatus(BookingStatus.PAYMENT_PENDING, "Booking is not awaiting payment");
        requireUnexpired(now);
        return copy(BookingStatus.CONFIRMED, expiresAt, refundAmount, checkoutCount, version, now);
    }

    /** PAYMENT_PENDING back to HELD so the user can try again. */
    public Booking paymentFailed(Instant now) {
        requireStatus(BookingStatus.PAYMENT_PENDING, "Booking is not awaiting payment");
        requireUnexpired(now);
        return copy(BookingStatus.HELD, expiresAt, refundAmount, checkoutCount, version, now);
    }

    /**
     * HELD or PAYMENT_PENDING to EXPIRED. Deliberately does not check the clock: the sweeper and
     * lazy checks decide when, and a payment race may expire a booking early.
     */
    public Booking expire(Instant now) {
        if (!status.isOpen()) {
            throw DomainException.bookingState("Booking is " + status + " and cannot expire");
        }
        return copy(BookingStatus.EXPIRED, expiresAt, refundAmount, checkoutCount, version, now);
    }

    /** Owner cancellation; refund per {@link RefundPolicy} for confirmed bookings. */
    public Booking cancelByOwner(Instant now, Instant showStartsAt) {
        return switch (status) {
            case HELD -> copy(BookingStatus.CANCELLED, expiresAt, 0, checkoutCount, version, now);
            case PAYMENT_PENDING -> throw DomainException.paymentInProgress();
            case CONFIRMED -> {
                long refund = RefundPolicy.refundAmount(amount, showStartsAt, now);
                BookingStatus to = refund > 0 ? BookingStatus.REFUNDED : BookingStatus.CANCELLED;
                yield copy(to, expiresAt, refund, checkoutCount, version, now);
            }
            default ->
                    throw DomainException.bookingState(
                            "Booking is " + status + " and cannot be cancelled");
        };
    }

    /** Admin show cancellation: confirmed bookings are refunded in full, open ones cancelled. */
    public Booking cancelByShow(Instant now) {
        return switch (status) {
            case CONFIRMED ->
                    copy(BookingStatus.REFUNDED, expiresAt, amount, checkoutCount, version, now);
            case HELD, PAYMENT_PENDING ->
                    copy(BookingStatus.CANCELLED, expiresAt, 0, checkoutCount, version, now);
            default ->
                    throw DomainException.bookingState(
                            "Booking is " + status + " and cannot be cancelled");
        };
    }

    private void requireStatus(BookingStatus expected, String message) {
        if (status != expected) {
            throw DomainException.bookingState(message);
        }
    }

    private void requireUnexpired(Instant now) {
        if (isExpiredAt(now)) {
            throw DomainException.holdExpired();
        }
    }

    private Booking copy(
            BookingStatus newStatus,
            Instant newExpiresAt,
            long newRefund,
            int newCheckoutCount,
            long newVersion,
            Instant now) {
        // Guards the table in BookingStatus against code paths that bypass it.
        if (newStatus != status && !status.canTransitionTo(newStatus)) {
            throw new IllegalStateException(status + " cannot move to " + newStatus);
        }
        return new Booking(
                id,
                code,
                userId,
                showId,
                holdId,
                seats,
                amount,
                currency,
                newStatus,
                newExpiresAt,
                newRefund,
                newCheckoutCount,
                newVersion,
                createdAt,
                now);
    }
}
