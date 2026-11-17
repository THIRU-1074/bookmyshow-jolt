package dev.thiru.bms.application;

import dev.thiru.bms.domain.Booking;
import dev.thiru.bms.domain.BookingPolicy;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.Payment;
import dev.thiru.bms.domain.port.IdGenerator;
import dev.thiru.bms.domain.port.PaymentGateway;
import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.SeatEventPublisher;
import dev.thiru.bms.domain.port.UnitOfWork;
import java.time.Clock;
import java.time.Instant;

/** HELD to PAYMENT_PENDING: opens a payment with the gateway and extends the payment window. */
public final class Checkout {

    public record Result(
            String paymentId,
            String checkoutUrl,
            long amount,
            String currency,
            Instant expiresAt) {}

    private final UnitOfWork uow;
    private final PaymentGateway gateway;
    private final SeatEventPublisher publisher;
    private final IdGenerator ids;
    private final Clock clock;
    private final BookingPolicy policy;

    /** The publisher carries the release events of a lazy expiry. */
    public Checkout(
            UnitOfWork uow,
            PaymentGateway gateway,
            SeatEventPublisher publisher,
            IdGenerator ids,
            Clock clock,
            BookingPolicy policy) {
        this.uow = uow;
        this.gateway = gateway;
        this.publisher = publisher;
        this.ids = ids;
        this.clock = clock;
        this.policy = policy;
    }

    public Result execute(long userId, String bookingId) {
        EventBuffer events = new EventBuffer();
        Outcome<Result> outcome = uow.inTransaction(r -> run(r, userId, bookingId, events));
        events.publishTo(publisher);
        return outcome.orThrow();
    }

    private Outcome<Result> run(Repositories r, long userId, String bookingId, EventBuffer events) {
        Instant now = clock.instant();
        Booking booking = BookingOps.ownedBooking(r, bookingId, userId);
        if (booking.status().isOpen() && booking.isExpiredAt(now)) {
            BookingOps.expire(r, booking, now, events);
            return Outcome.fail(DomainException.holdExpired());
        }
        Booking updated = BookingOps.apply(r, booking, b -> b.startCheckout(now, policy));
        String paymentId = ids.newId();
        PaymentGateway.CheckoutSession session =
                gateway.createSession(paymentId, updated.amount(), updated.currency());
        r.payments().insert(Payment.initiate(paymentId, updated, session.gatewayRef(), now));
        r.locks().updateExpiry(updated.holdId(), updated.expiresAt());
        return Outcome.ok(
                new Result(
                        paymentId,
                        session.checkoutUrl(),
                        updated.amount(),
                        updated.currency(),
                        updated.expiresAt()));
    }
}
