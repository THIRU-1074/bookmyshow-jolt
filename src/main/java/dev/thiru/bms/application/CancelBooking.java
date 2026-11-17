package dev.thiru.bms.application;

import dev.thiru.bms.domain.Booking;
import dev.thiru.bms.domain.BookingStatus;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.Payment;
import dev.thiru.bms.domain.Show;
import dev.thiru.bms.domain.port.PaymentGateway;
import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.SeatEvent;
import dev.thiru.bms.domain.port.SeatEventPublisher;
import dev.thiru.bms.domain.port.UnitOfWork;
import java.time.Clock;
import java.time.Instant;

/** Owner cancellation with the time-based refund of SPEC section 4. */
public final class CancelBooking {

    private final UnitOfWork uow;
    private final PaymentGateway gateway;
    private final SeatEventPublisher publisher;
    private final Clock clock;

    public CancelBooking(
            UnitOfWork uow, PaymentGateway gateway, SeatEventPublisher publisher, Clock clock) {
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
