package dev.thiru.bms.application;

import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.UnitOfWork;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.booking.SeatHold;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.ports.SeatEvents;
import java.time.Clock;
import java.time.Instant;

/**
 * Show cancellation by an admin or a theatre owner of the show's cinema: refunds confirmed
 * bookings, cancels open ones. Safe to repeat.
 *
 * <p>Kept as a test fixture until wave 4: {@code ProcessPaymentWebhookTest} cancels a show with it.
 * Delete it with that test. Production uses {@code features.admin.CancelShow}.
 */
public final class CancelShow {

    public record Result(int refunded, int cancelled, int holdsReleased) {}

    private final UnitOfWork uow;
    private final PaymentGateway gateway;
    private final SeatEvents publisher;
    private final Clock clock;

    public CancelShow(UnitOfWork uow, PaymentGateway gateway, SeatEvents publisher, Clock clock) {
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
