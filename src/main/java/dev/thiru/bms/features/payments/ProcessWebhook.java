package dev.thiru.bms.features.payments;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingSeat;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.Db.Tx;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.ports.SeatEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Applies a verified gateway event (SPEC 4). The signature is the route's middleware; this class
 * owns deduplication, the amount check and the booking and payment transitions, all in one
 * transaction. Seat events go out only after it commits.
 *
 * <p>The full refund of a late success calls the gateway inside the transaction, so a failed refund
 * rolls the event back and the gateway can redeliver it. See {@code
 * docs/restructure/ADR-0002-gateway-calls-in-transactions.md} for why that is acceptable only for
 * the mock gateway.
 */
public final class ProcessWebhook {

    public enum Type {
        PAYMENT_SUCCEEDED,
        PAYMENT_FAILED
    }

    public record Event(
            String eventId,
            Type type,
            String paymentId,
            String gatewayRef,
            long amount,
            String currency,
            Instant occurredAt) {}

    public enum Kind {
        PROCESSED,
        DUPLICATE,
        /** The payment was marked FAILED; the route answers 422. */
        AMOUNT_MISMATCH
    }

    /** {@code bookingStatus} is null for duplicates, which have no side effects. */
    public record Result(Kind kind, BookingStatus bookingStatus) {}

    private final Db db;
    private final PaymentGateway gateway;
    private final SeatEvents events;
    private final Clock clock;

    public ProcessWebhook(Db db, PaymentGateway gateway, SeatEvents events, Clock clock) {
        this.db = db;
        this.gateway = gateway;
        this.events = events;
        this.clock = clock;
    }

    public Result execute(Event event) {
        return db.inTransaction(tx -> process(tx, event));
    }

    private Result process(Tx tx, Event event) {
        Instant now = clock.instant();
        if (!PaymentSql.recordEvent(
                tx, event.eventId(), event.paymentId(), event.type().name(), now)) {
            return new Result(Kind.DUPLICATE, null);
        }
        Payment payment =
                PaymentSql.payment(tx, event.paymentId())
                        .orElseThrow(() -> DomainException.notFound("Payment"));
        Booking booking =
                PaymentSql.booking(tx, payment.bookingId())
                        .orElseThrow(() -> DomainException.notFound("Booking"));

        // Expiry is re-checked here, never left to the sweeper (SPEC 5.5).
        if (booking.status().isOpen() && booking.isExpiredAt(now)) {
            booking = BookingOps.expire(tx, events, booking, now);
        }

        boolean mismatch = !payment.matches(event.amount(), event.currency());
        if (mismatch || event.type() == Type.PAYMENT_FAILED) {
            booking = failPayment(tx, payment, booking, now);
            return new Result(mismatch ? Kind.AMOUNT_MISMATCH : Kind.PROCESSED, booking.status());
        }
        return new Result(Kind.PROCESSED, succeed(tx, payment, booking, now).status());
    }

    private static Booking failPayment(Tx tx, Payment payment, Booking booking, Instant now) {
        if (payment.status() != PaymentStatus.INITIATED) {
            return booking;
        }
        PaymentSql.updatePayment(tx, payment.fail(now));
        if (booking.status() == BookingStatus.PAYMENT_PENDING) {
            return BookingOps.apply(tx, booking, b -> b.paymentFailed(now));
        }
        return booking;
    }

    private Booking succeed(Tx tx, Payment payment, Booking booking, Instant now) {
        if (payment.status() != PaymentStatus.INITIATED) {
            return booking; // a late success for an attempt already failed or settled
        }
        Payment succeeded = payment.succeed(now);
        if (booking.status() == BookingStatus.PAYMENT_PENDING) {
            if (ownsAllLocks(tx, booking)) {
                Booking confirmed = BookingOps.apply(tx, booking, b -> b.paymentSucceeded(now));
                PaymentSql.markBooked(tx, booking.holdId());
                PaymentSql.updatePayment(tx, succeeded);
                BookingOps.publish(
                        tx,
                        events,
                        new SeatEvent.Booked(
                                booking.showId(),
                                booking.seats().stream().map(BookingSeat::seat).toList()));
                return confirmed;
            }
            // Seats were lost although the booking looked live: give the money back.
            booking = BookingOps.expire(tx, events, booking, now);
        }
        // The booking is EXPIRED, CANCELLED or REFUNDED: the customer paid for nothing.
        gateway.refund(succeeded.gatewayRef(), succeeded.amount());
        PaymentSql.updatePayment(tx, succeeded.refund(succeeded.amount(), now));
        return booking;
    }

    /** True when every seat of the booking is locked for this booking and no other. */
    private static boolean ownsAllLocks(Tx tx, Booking booking) {
        Set<SeatId> owned = new HashSet<>();
        for (PaymentSql.Lock l : PaymentSql.locks(tx, booking.holdId())) {
            if (booking.id().equals(l.bookingId())) {
                owned.add(l.seat());
            }
        }
        Set<SeatId> wanted = new HashSet<>();
        booking.seats().forEach(s -> wanted.add(s.seat()));
        return owned.equals(wanted);
    }
}
