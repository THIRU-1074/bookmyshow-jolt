package dev.thiru.bms.features.admin;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.account.CinemaScope;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import dev.thiru.bms.kernel.payment.Payment;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.ports.SeatEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Show cancellation by an admin or a theatre owner of the show's cinema: releases active holds,
 * refunds confirmed bookings in full and cancels open ones, all in one transaction. Seat events go
 * out only after it commits; a failing refund rolls everything back. Live streams of the show end
 * on their next heartbeat, when they re-read the status. Safe to repeat.
 */
final class CancelShow {

    record Result(int refunded, int cancelled, int holdsReleased) {}

    private final Db db;
    private final PaymentGateway gateway;
    private final SeatEvents events;
    private final Clock clock;

    CancelShow(Db db, PaymentGateway gateway, SeatEvents events, Clock clock) {
        this.db = db;
        this.gateway = gateway;
        this.events = events;
        this.clock = clock;
    }

    Result execute(CinemaScope scope, long showId) {
        return db.inTransaction(tx -> run(tx, scope, showId));
    }

    private Result run(Db.Tx tx, CinemaScope scope, long showId) {
        Instant now = clock.instant();
        Show show = AdminSql.show(tx, showId).orElseThrow(() -> DomainException.notFound("Show"));
        AdminSql.require(tx, scope, AdminSql.cinemaOfShow(tx, show), "Show");
        if (show.status() != ShowStatus.CANCELLED) {
            tx.update("UPDATE show SET status = 'CANCELLED' WHERE id = ?", showId);
        }
        List<String> holds =
                tx.query(
                        "SELECT id FROM seat_hold WHERE show_id = ? AND status = 'ACTIVE'"
                                + " ORDER BY created_at, id",
                        rs -> rs.getString(1),
                        showId);
        for (String hold : holds) {
            // SeatHold.release: the only move out of ACTIVE that keeps the expiry.
            tx.update("UPDATE seat_hold SET status = 'RELEASED' WHERE id = ?", hold);
            released(tx, showId, freeSeats(tx, hold));
        }
        int refunded = 0;
        int cancelled = 0;
        for (Booking booking : liveBookings(tx, showId)) {
            boolean wasConfirmed = booking.status() == BookingStatus.CONFIRMED;
            Booking updated = apply(tx, booking, b -> b.cancelByShow(now));
            if (wasConfirmed) {
                Payment payment = latestPayment(tx, booking.id());
                gateway.refund(payment.gatewayRef(), updated.refundAmount());
                Payment refund = payment.refund(updated.refundAmount(), now);
                tx.update(
                        "UPDATE payment SET status = ?, gateway_ref = ?, refunded_amount = ?,"
                                + " updated_at = ? WHERE id = ?",
                        refund.status().name(),
                        refund.gatewayRef(),
                        refund.refundedAmount(),
                        Db.millis(refund.updatedAt()),
                        refund.id());
                refunded++;
            } else {
                cancelled++;
            }
            released(tx, showId, freeSeats(tx, booking.holdId()));
        }
        return new Result(refunded, cancelled, holds.size());
    }

    private void released(Db.Tx tx, long showId, List<SeatId> seats) {
        if (!seats.isEmpty()) {
            SeatEvent event = new SeatEvent.Released(showId, seats, SeatEvent.Reason.CANCELLED);
            tx.afterCommit(() -> events.publish(event));
        }
    }

    /** Deletes the hold's seat locks and returns the freed seats, sorted. */
    private static List<SeatId> freeSeats(Db.Tx tx, String holdId) {
        List<SeatId> freed =
                tx.query(
                        "SELECT seat_id FROM seat_lock WHERE hold_id = ?",
                        rs -> SeatId.parse(rs.getString(1)),
                        holdId);
        if (!freed.isEmpty()) {
            tx.update("DELETE FROM seat_lock WHERE hold_id = ?", holdId);
        }
        return freed.stream().sorted().toList();
    }

    private static final String BOOKING_COLS =
            "id, code, user_id, show_id, hold_id, amount, currency, status, expires_at,"
                    + " refund_amount, checkout_count, version, created_at, updated_at";

    /** Rows without their seats: show cancellation never reads or changes them. */
    private static final Db.Row<Booking> BOOKING =
            rs ->
                    new Booking(
                            rs.getString("id"),
                            rs.getString("code"),
                            rs.getLong("user_id"),
                            rs.getLong("show_id"),
                            rs.getString("hold_id"),
                            List.of(),
                            rs.getLong("amount"),
                            rs.getString("currency"),
                            BookingStatus.valueOf(rs.getString("status")),
                            Db.instant(rs, "expires_at"),
                            rs.getLong("refund_amount"),
                            rs.getInt("checkout_count"),
                            rs.getLong("version"),
                            Db.instant(rs, "created_at"),
                            Db.instant(rs, "updated_at"));

    private static List<Booking> liveBookings(Db.Tx tx, long showId) {
        return tx.query(
                "SELECT "
                        + BOOKING_COLS
                        + " FROM booking WHERE show_id = ? AND status IN ('CONFIRMED', 'HELD',"
                        + " 'PAYMENT_PENDING') ORDER BY created_at, id",
                BOOKING,
                showId);
    }

    /**
     * Writes a transition with the optimistic version. On a miss it re-reads and re-applies the
     * transition once (SPEC 5.4); a second miss is VERSION_CONFLICT.
     */
    private static Booking apply(Db.Tx tx, Booking loaded, UnaryOperator<Booking> transition) {
        Booking next = transition.apply(loaded);
        if (write(tx, next)) {
            return next.withVersion(next.version() + 1);
        }
        Booking fresh =
                tx.queryOne(
                                "SELECT " + BOOKING_COLS + " FROM booking WHERE id = ?",
                                BOOKING,
                                loaded.id())
                        .orElseThrow(() -> DomainException.notFound("Booking"));
        Booking retried = transition.apply(fresh);
        if (write(tx, retried)) {
            return retried.withVersion(retried.version() + 1);
        }
        throw DomainException.versionConflict();
    }

    private static boolean write(Db.Tx tx, Booking b) {
        return tx.update(
                        "UPDATE booking SET status = ?, expires_at = ?, refund_amount = ?,"
                                + " checkout_count = ?, updated_at = ?, version = version + 1"
                                + " WHERE id = ? AND version = ?",
                        b.status().name(),
                        Db.millis(b.expiresAt()),
                        b.refundAmount(),
                        b.checkoutCount(),
                        Db.millis(b.updatedAt()),
                        b.id(),
                        b.version())
                == 1;
    }

    private static Payment latestPayment(Db.Tx tx, String bookingId) {
        return tx.queryOne(
                        "SELECT id, booking_id, amount, currency, status, gateway_ref,"
                                + " refunded_amount, created_at, updated_at FROM payment"
                                + " WHERE booking_id = ? ORDER BY created_at DESC, rowid DESC"
                                + " LIMIT 1",
                        rs ->
                                new Payment(
                                        rs.getString("id"),
                                        rs.getString("booking_id"),
                                        rs.getLong("amount"),
                                        rs.getString("currency"),
                                        PaymentStatus.valueOf(rs.getString("status")),
                                        rs.getString("gateway_ref"),
                                        rs.getLong("refunded_amount"),
                                        Db.instant(rs, "created_at"),
                                        Db.instant(rs, "updated_at")),
                        bookingId)
                .orElseThrow();
    }
}
