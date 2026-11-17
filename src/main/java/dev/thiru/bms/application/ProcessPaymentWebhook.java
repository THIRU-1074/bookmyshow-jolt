package dev.thiru.bms.application;

import dev.thiru.bms.domain.Booking;
import dev.thiru.bms.domain.BookingSeat;
import dev.thiru.bms.domain.BookingStatus;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.Payment;
import dev.thiru.bms.domain.PaymentStatus;
import dev.thiru.bms.domain.SeatId;
import dev.thiru.bms.domain.port.PaymentGateway;
import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.SeatEvent;
import dev.thiru.bms.domain.port.SeatEventPublisher;
import dev.thiru.bms.domain.port.SeatLock;
import dev.thiru.bms.domain.port.UnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies a verified gateway event. Signature checking is the web layer's job; this class owns
 * deduplication, amount checks and the booking and payment transitions of SPEC section 4.
 */
public final class ProcessPaymentWebhook {

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
        /** The payment was marked FAILED; the web layer answers 422. */
        AMOUNT_MISMATCH
    }

    /** {@code bookingStatus} is null for duplicates, which have no side effects. */
    public record Result(Kind kind, BookingStatus bookingStatus) {}

    private final UnitOfWork uow;
    private final PaymentGateway gateway;
    private final SeatEventPublisher publisher;
    private final Clock clock;

    public ProcessPaymentWebhook(
            UnitOfWork uow, PaymentGateway gateway, SeatEventPublisher publisher, Clock clock) {
        this.uow = uow;
        this.gateway = gateway;
        this.publisher = publisher;
        this.clock = clock;
    }

    public Result execute(Event event) {
        EventBuffer events = new EventBuffer();
        Result result = uow.inTransaction(r -> process(r, event, events));
        events.publishTo(publisher);
        return result;
    }

    private Result process(Repositories r, Event event, EventBuffer events) {
        Instant now = clock.instant();
        if (!r.paymentEvents()
                .recordIfNew(event.eventId(), event.paymentId(), event.type().name(), now)) {
            return new Result(Kind.DUPLICATE, null);
        }
        Payment payment =
                r.payments()
                        .findById(event.paymentId())
                        .orElseThrow(() -> DomainException.notFound("Payment"));
        Booking booking =
                r.bookings()
                        .findById(payment.bookingId())
                        .orElseThrow(() -> DomainException.notFound("Booking"));

        // Expiry is re-checked here, never delegated to the sweeper (SPEC 5.5).
        if (booking.status().isOpen() && booking.isExpiredAt(now)) {
            booking = BookingOps.expire(r, booking, now, events);
        }

        boolean mismatch = !payment.matches(event.amount(), event.currency());
        if (mismatch || event.type() == Type.PAYMENT_FAILED) {
            booking = failPayment(r, payment, booking, now);
            return new Result(mismatch ? Kind.AMOUNT_MISMATCH : Kind.PROCESSED, booking.status());
        }
        return new Result(Kind.PROCESSED, succeed(r, payment, booking, now, events).status());
    }

    private Booking failPayment(Repositories r, Payment payment, Booking booking, Instant now) {
        if (payment.status() != PaymentStatus.INITIATED) {
            return booking;
        }
        r.payments().update(payment.fail(now));
        if (booking.status() == BookingStatus.PAYMENT_PENDING) {
            return BookingOps.apply(r, booking, b -> b.paymentFailed(now));
        }
        return booking;
    }

    private Booking succeed(
            Repositories r, Payment payment, Booking booking, Instant now, EventBuffer events) {
        if (payment.status() != PaymentStatus.INITIATED) {
            return booking; // a late success for an attempt already failed or settled
        }
        Payment succeeded = payment.succeed(now);
        if (booking.status() == BookingStatus.PAYMENT_PENDING) {
            if (ownsAllLocks(r, booking)) {
                Booking confirmed = BookingOps.apply(r, booking, b -> b.paymentSucceeded(now));
                r.locks().markBooked(booking.holdId());
                r.payments().update(succeeded);
                events.add(
                        new SeatEvent.Booked(
                                booking.showId(),
                                booking.seats().stream().map(BookingSeat::seat).toList()));
                return confirmed;
            }
            // Seats were lost although the booking looked live: give the money back.
            booking = BookingOps.expire(r, booking, now, events);
        }
        // The booking is EXPIRED, CANCELLED or REFUNDED: the customer paid for nothing.
        refundInFull(r, succeeded, now);
        return booking;
    }

    private void refundInFull(Repositories r, Payment succeeded, Instant now) {
        gateway.refund(succeeded.gatewayRef(), succeeded.amount());
        r.payments().update(succeeded.refund(succeeded.amount(), now));
    }

    private static boolean ownsAllLocks(Repositories r, Booking booking) {
        List<SeatLock> locks = r.locks().findByHold(booking.holdId());
        Set<SeatId> owned = new HashSet<>();
        for (SeatLock l : locks) {
            if (booking.id().equals(l.bookingId())) {
                owned.add(l.seat());
            }
        }
        Set<SeatId> wanted = new HashSet<>();
        booking.seats().forEach(s -> wanted.add(s.seat()));
        return owned.equals(wanted);
    }
}
