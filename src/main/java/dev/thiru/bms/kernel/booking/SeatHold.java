package dev.thiru.bms.kernel.booking;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.catalog.Screen;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A short-lived, all-or-nothing claim on seats of one show. */
public record SeatHold(
        String id,
        long showId,
        long userId,
        List<SeatId> seats,
        Instant createdAt,
        Instant expiresAt,
        HoldStatus status) {

    public SeatHold {
        seats = List.copyOf(seats);
    }

    /**
     * Validates sales window and seats, reporting every bad seat under {@code seats[i]}. Seat
     * existence and blocking come from the screen layout; availability is the lock table's job.
     */
    public static SeatHold create(
            String id,
            Show show,
            Screen screen,
            long userId,
            List<String> rawSeats,
            Instant now,
            BookingPolicy policy) {
        if (show.status() == ShowStatus.CANCELLED) {
            // The spec has no code for a cancelled show; BOOKING_STATE (409) is the closest.
            throw DomainException.bookingState("The show has been cancelled");
        }
        if (show.hasStarted(now)) {
            throw DomainException.showStarted();
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (rawSeats == null || rawSeats.isEmpty() || rawSeats.size() > policy.maxSeats()) {
            errors.put("seats", "choose 1 to " + policy.maxSeats() + " seats");
            throw DomainException.validation(errors);
        }
        Set<SeatId> seen = new HashSet<>();
        java.util.ArrayList<SeatId> seats = new java.util.ArrayList<>();
        for (int i = 0; i < rawSeats.size(); i++) {
            String key = "seats[" + i + "]";
            SeatId seat;
            try {
                seat = SeatId.parse(rawSeats.get(i));
            } catch (DomainException e) {
                errors.put(key, "invalid seat id");
                continue;
            }
            if (!screen.layout().exists(seat)) {
                errors.put(key, "unknown seat");
            } else if (screen.layout().isBlocked(seat)) {
                errors.put(key, "seat is blocked");
            } else if (!seen.add(seat)) {
                errors.put(key, "duplicate seat");
            } else {
                seats.add(seat);
            }
        }
        if (!errors.isEmpty()) {
            throw DomainException.validation(errors);
        }
        return new SeatHold(
                id, show.id(), userId, seats, now, now.plus(policy.holdTtl()), HoldStatus.ACTIVE);
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** ACTIVE to CONSUMED when a booking is created from the hold. */
    public SeatHold consume(Instant now) {
        if (status != HoldStatus.ACTIVE) {
            throw DomainException.bookingState("Hold is " + status);
        }
        if (isExpiredAt(now)) {
            throw DomainException.holdExpired();
        }
        return with(HoldStatus.CONSUMED);
    }

    public SeatHold release() {
        if (status != HoldStatus.ACTIVE) {
            throw DomainException.bookingState("Hold is " + status);
        }
        return with(HoldStatus.RELEASED);
    }

    public SeatHold expire() {
        if (status != HoldStatus.ACTIVE) {
            throw DomainException.bookingState("Hold is " + status);
        }
        return with(HoldStatus.EXPIRED);
    }

    private SeatHold with(HoldStatus newStatus) {
        return new SeatHold(id, showId, userId, seats, createdAt, expiresAt, newStatus);
    }
}
