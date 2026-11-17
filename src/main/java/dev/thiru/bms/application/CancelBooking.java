package dev.thiru.bms.application;

import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.UnitOfWork;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.ports.SeatEvents;
import java.time.Clock;
import java.time.Instant;

/**
 * Owner cancellation with the time-based refund of SPEC section 4.
 *
 * <p>Kept as a test fixture until wave 5; delete with {@code AdminReportsAndQueriesTest}.
 * Production uses {@code features.booking}.
 */
public final class CancelBooking {

    private final UnitOfWork uow;
    private final PaymentGateway gateway;
    private final SeatEvents publisher;
    private final Clock clock;

    public CancelBooking(
            UnitOfWork uow, PaymentGateway gateway, SeatEvents publisher, Clock clock) {
        this.uow = uow;
        this.gateway = gateway;
        this.publisher = publisher;
        this.clock = clock;
    }

    public Booking execute(long userId, String bookingId) {
        EventBuffer events = new EventBuffer();
        Outcome<Booking> outcome = uow.inTransaction(r -> run(r, userId, bookingId, events));
        events.publishTo(publisher);
        return outcome.orThrow();
    }

    private Outcome<Booking> run(
            Repositories r, long userId, String bookingId, EventBuffer events) {
        Instant now = clock.instant();
        Booking booking = BookingOps.ownedBooking(r, bookingId, userId);
        if (booking.status().isOpen() && booking.isExpiredAt(now)) {
            BookingOps.expire(r, booking, now, events);
            return Outcome.fail(DomainException.bookingState("Booking has expired"));
        }
        Show show = r.shows().findById(booking.showId()).orElseThrow();
        BookingStatus before = booking.status();
        Booking cancelled =
                BookingOps.apply(r, booking, b -> b.cancelByOwner(now, show.startsAt()));
        if (before == BookingStatus.CONFIRMED && cancelled.refundAmount() > 0) {
            Payment payment = r.payments().findLatestByBooking(bookingId).orElseThrow();
            gateway.refund(payment.gatewayRef(), cancelled.refundAmount());
            r.payments().update(payment.refund(cancelled.refundAmount(), now));
        }
        events.released(
                booking.showId(),
                r.locks().deleteByHold(booking.holdId()),
                SeatEvent.Reason.CANCELLED);
        return Outcome.ok(cancelled);
    }
}
