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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The payments slice's SQL: gateway events, payments, the booking a payment settles and that
 * booking's seat locks. Every method runs inside the caller's transaction. The booking and lock
 * statements are copies of the booking slice's (slices never import each other).
 */
final class PaymentSql {

    private PaymentSql() {}

    /** A seat lock as the webhook needs it: which seat, and which booking it is attached to. */
    record Lock(SeatId seat, String bookingId) {}

    // ---- gateway events ----

    /** Records the event id; false if it was seen before, which makes the delivery a duplicate. */
    static boolean recordEvent(
            Tx tx, String eventId, String paymentId, String type, Instant receivedAt) {
        return tx.update(
                        "INSERT OR IGNORE INTO payment_event (event_id, payment_id, type,"
                                + " received_at) VALUES (?, ?, ?, ?)",
                        eventId,
                        paymentId,
                        type,
                        Db.millis(receivedAt))
                == 1;
    }

    // ---- payments ----

    private static final String PAYMENT_COLS =
            "id, booking_id, amount, currency, status, gateway_ref, refunded_amount, created_at,"
                    + " updated_at";

    static Optional<Payment> payment(Tx tx, String id) {
        return tx.queryOne(
                "SELECT " + PAYMENT_COLS + " FROM payment WHERE id = ?",
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
                id);
    }

    static void updatePayment(Tx tx, Payment p) {
        int n =
                tx.update(
                        "UPDATE payment SET status = ?, gateway_ref = ?, refunded_amount = ?,"
                                + " updated_at = ? WHERE id = ?",
                        p.status().name(),
                        p.gatewayRef(),
                        p.refundedAmount(),
                        Db.millis(p.updatedAt()),
                        p.id());
        if (n != 1) {
            throw DomainException.notFound("Payment");
        }
    }

    // ---- bookings ----

    private static final String BOOKING_COLS =
            "id, code, user_id, show_id, hold_id, amount, currency, status, expires_at,"
                    + " refund_amount, checkout_count, version, created_at, updated_at";

    /** The booking with its seats in the order the buyer chose them ({@code rowid} order). */
    static Optional<Booking> booking(Tx tx, String id) {
        List<BookingSeat> seats =
                tx.query(
                        "SELECT seat_id, category, price FROM booking_seat WHERE booking_id = ?"
                                + " ORDER BY rowid",
                        rs ->
                                new BookingSeat(
                                        SeatId.parse(rs.getString(1)),
                                        rs.getString(2),
                                        rs.getLong(3)),
                        id);
        return tx.queryOne(
                "SELECT " + BOOKING_COLS + " FROM booking WHERE id = ?",
                rs ->
                        new Booking(
                                rs.getString("id"),
                                rs.getString("code"),
                                rs.getLong("user_id"),
                                rs.getLong("show_id"),
                                rs.getString("hold_id"),
                                seats,
                                rs.getLong("amount"),
                                rs.getString("currency"),
                                BookingStatus.valueOf(rs.getString("status")),
                                Db.instant(rs, "expires_at"),
                                rs.getLong("refund_amount"),
                                rs.getInt("checkout_count"),
                                rs.getLong("version"),
                                Db.instant(rs, "created_at"),
                                Db.instant(rs, "updated_at")),
                id);
    }

    /** Writes a transition if nobody moved the row since it was read; VERSION_CONFLICT if not. */
    static Booking updateBooking(Tx tx, Booking b) {
        int n =
                tx.update(
                        "UPDATE booking SET status = ?, expires_at = ?, refund_amount = ?,"
                                + " checkout_count = ?, updated_at = ?, version = version + 1"
                                + " WHERE id = ? AND version = ?",
                        b.status().name(),
                        Db.millis(b.expiresAt()),
                        b.refundAmount(),
                        b.checkoutCount(),
                        Db.millis(b.updatedAt()),
                        b.id(),
                        b.version());
        if (n != 1) {
            throw DomainException.versionConflict();
        }
        return b.withVersion(b.version() + 1);
    }

    // ---- seat locks ----

    static List<Lock> locks(Tx tx, String holdId) {
        return tx.query(
                "SELECT seat_id, booking_id FROM seat_lock WHERE hold_id = ?",
                rs -> new Lock(SeatId.parse(rs.getString(1)), rs.getString(2)),
                holdId);
    }

    /** Paid seats never expire. */
    static void markBooked(Tx tx, String holdId) {
        tx.update(
                "UPDATE seat_lock SET state = 'BOOKED', expires_at = NULL WHERE hold_id = ?",
                holdId);
    }

    /** Deletes the hold's locks; returns the freed seats, sorted. */
    static List<SeatId> deleteLocks(Tx tx, String holdId) {
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
}
