package dev.thiru.bms.application;

import dev.thiru.bms.domain.Booking;
import dev.thiru.bms.domain.BookingStatus;
import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.Payment;
import dev.thiru.bms.domain.SeatHold;
import dev.thiru.bms.domain.Show;
import dev.thiru.bms.domain.ShowStatus;
import dev.thiru.bms.domain.port.PaymentGateway;
import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.SeatEvent;
import dev.thiru.bms.domain.port.SeatEventPublisher;
import dev.thiru.bms.domain.port.UnitOfWork;
import java.time.Clock;
import java.time.Instant;

/** Admin show cancellation: refunds confirmed bookings, cancels open ones. Safe to repeat. */
public final class CancelShow {

    public record Result(int refunded, int cancelled, int holdsReleased) {}

    private final UnitOfWork uow;
    private final PaymentGateway gateway;
    private final SeatEventPublisher publisher;
    private final Clock clock;

    public CancelShow(
            UnitOfWork uow, PaymentGateway gateway, SeatEventPublisher publisher, Clock clock) {
        this.uow = uow;
        this.gateway = gateway;
        this.publisher = publisher;
        this.clock = clock;
    }

    public Result execute(long showId) {
        EventBuffer events = new EventBuffer();
        Result result = uow.inTransaction(r -> run(r, showId, events));
        events.publishTo(publisher);
        return result;
    }

    private Result run(Repositories r, long showId, EventBuffer events) {
        Instant now = clock.instant();
        Show show = r.shows().findById(showId).orElseThrow(() -> DomainException.notFound("Show"));
        if (show.status() != ShowStatus.CANCELLED) {
            r.shows().save(show.cancel());
        }
        int holds = 0;
        for (SeatHold hold : r.holds().findActiveByShow(showId)) {
            r.holds().update(hold.release());
            events.released(showId, r.locks().deleteByHold(hold.id()), SeatEvent.Reason.CANCELLED);
            holds++;
        }
        int refunded = 0;
        int cancelled = 0;
        for (Booking booking : r.bookings().findLiveByShow(showId)) {
            boolean wasConfirmed = booking.status() == BookingStatus.CONFIRMED;
            Booking updated = BookingOps.apply(r, booking, b -> b.cancelByShow(now));
            if (wasConfirmed) {
                Payment payment = r.payments().findLatestByBooking(booking.id()).orElseThrow();
                gateway.refund(payment.gatewayRef(), updated.refundAmount());
                r.payments().update(payment.refund(updated.refundAmount(), now));
                refunded++;
            } else {
                cancelled++;
            }
            events.released(
                    showId, r.locks().deleteByHold(booking.holdId()), SeatEvent.Reason.CANCELLED);
        }
        return new Result(refunded, cancelled, holds);
    }
}
