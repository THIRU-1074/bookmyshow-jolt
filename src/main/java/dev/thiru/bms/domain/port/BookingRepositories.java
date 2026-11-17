package dev.thiru.bms.domain.port;

import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.booking.SeatHold;
import dev.thiru.bms.kernel.payment.Payment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Ports for the transactional aggregates. */
public interface BookingRepositories {

    interface Holds {
        void insert(SeatHold hold);

        void update(SeatHold hold);

        Optional<SeatHold> findById(String id);

        Optional<SeatHold> findActiveByUserAndShow(long userId, long showId);

        List<SeatHold> findActiveByShow(long showId);

        /** ACTIVE holds with expiresAt <= now, oldest first. */
        List<SeatHold> findExpiredActive(Instant now, int limit);

        long countActive(Instant now);
    }

    interface Bookings {
        void insert(Booking booking);

        /**
         * Writes with {@code WHERE id = ? AND version = ?} and returns the copy with version + 1.
         * Throws {@code DomainException.versionConflict()} when no row matched.
         */
        Booking update(Booking booking);

        Optional<Booking> findById(String id);

        boolean existsByCode(String code);

        Page<Booking> page(long userId, Set<BookingStatus> statuses, int page, int size);

        /** HELD or PAYMENT_PENDING bookings with expiresAt <= now. */
        List<Booking> findOpenExpired(Instant now, int limit);

        /** CONFIRMED, HELD and PAYMENT_PENDING bookings of a show. */
        List<Booking> findLiveByShow(long showId);
    }

    interface Payments {
        void insert(Payment payment);

        void update(Payment payment);

        Optional<Payment> findById(String id);

        /** Most recent payment of a booking, if any. */
        Optional<Payment> findLatestByBooking(String bookingId);
    }

    interface PaymentEvents {
        /**
         * Records the event; false when {@code eventId} was already recorded (duplicate delivery).
         */
        boolean recordIfNew(String eventId, String paymentId, String type, Instant receivedAt);
    }

    interface Users {
        Optional<User> findById(long id);

        Optional<User> findByEmail(String email);

        User insert(User user);
    }
}
