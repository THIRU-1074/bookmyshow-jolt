package dev.thiru.bms.domain;

import java.time.Instant;

/**
 * A payment attempt for a booking. Misuse here is a caller bug (the use cases check state first),
 * so illegal moves throw {@link IllegalStateException} rather than a domain error.
 */
public record Payment(
        String id,
        String bookingId,
        long amount,
        String currency,
        PaymentStatus status,
        String gatewayRef,
        long refundedAmount,
        Instant createdAt,
        Instant updatedAt) {

    public static Payment initiate(String id, Booking booking, String gatewayRef, Instant now) {
        return new Payment(
                id,
                booking.id(),
                booking.amount(),
                booking.currency(),
                PaymentStatus.INITIATED,
                gatewayRef,
                0,
                now,
                now);
    }

    public boolean matches(long eventAmount, String eventCurrency) {
        return amount == eventAmount && currency.equals(eventCurrency);
    }

    public Payment succeed(Instant now) {
        requireStatus(PaymentStatus.INITIATED);
        return with(PaymentStatus.SUCCEEDED, refundedAmount, now);
    }

    public Payment fail(Instant now) {
        requireStatus(PaymentStatus.INITIATED);
        return with(PaymentStatus.FAILED, refundedAmount, now);
    }

    /** Full or partial refund of a succeeded payment; {@code refundedAmount <= amount}. */
    public Payment refund(long refund, Instant now) {
        requireStatus(PaymentStatus.SUCCEEDED);
        if (refund < 1 || refund > amount) {
            throw new IllegalStateException("refund out of range: " + refund);
        }
        return with(PaymentStatus.REFUNDED, refund, now);
    }

    private void requireStatus(PaymentStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("payment is " + status + ", expected " + expected);
        }
    }

    private Payment with(PaymentStatus newStatus, long refunded, Instant now) {
        return new Payment(
                id, bookingId, amount, currency, newStatus, gatewayRef, refunded, createdAt, now);
    }
}
