package dev.thiru.bms.features.booking;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.Db.Tx;
import dev.thiru.bms.ports.Ids;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.SeatEvents;
import java.time.Clock;
import java.time.Instant;

/** HELD to PAYMENT_PENDING: opens a payment with the gateway and extends the payment window. */
final class Checkout {

    record Result(
            String paymentId,
            String checkoutUrl,
            long amount,
            String currency,
            Instant expiresAt) {}

    private final Db db;
    private final PaymentGateway gateway;
    private final SeatEvents events;
    private final Ids ids;
    private final Clock clock;
    private final BookingPolicy policy;

    /** The events carry the release of a lazy expiry. */
    Checkout(
            Db db,
            PaymentGateway gateway,
            SeatEvents events,
            Ids ids,
            Clock clock,
            BookingPolicy policy) {
        this.db = db;
        this.gateway = gateway;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.policy = policy;
    }

    Result execute(long userId, String bookingId) {
        return db.inTransaction(tx -> run(tx, userId, bookingId)).orThrow();
    }

    private Outcome<Result> run(Tx tx, long userId, String bookingId) {
        Instant now = clock.instant();
        Booking booking = BookingOps.owned(tx, bookingId, userId);
        if (booking.status().isOpen() && booking.isExpiredAt(now)) {
            BookingOps.expire(tx, events, booking, now);
            return Outcome.fail(DomainException.holdExpired());
        }
        Booking updated = BookingOps.apply(tx, booking, b -> b.startCheckout(now, policy));
        String paymentId = ids.newId();
        PaymentGateway.CheckoutSession session =
                gateway.createSession(paymentId, updated.amount(), updated.currency());
        BookingSql.insertPayment(
                tx, Payment.initiate(paymentId, updated, session.gatewayRef(), now));
        BookingSql.extendLocks(tx, updated.holdId(), updated.expiresAt());
        return Outcome.ok(
                new Result(
                        paymentId,
                        session.checkoutUrl(),
                        updated.amount(),
                        updated.currency(),
                        updated.expiresAt()));
    }
}
