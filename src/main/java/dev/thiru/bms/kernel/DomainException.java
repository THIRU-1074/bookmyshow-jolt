package dev.thiru.bms.kernel;

import java.util.List;
import java.util.Map;

/**
 * Every business-rule failure. The web layer maps {@link Code} to the stable problem+json codes in
 * the spec, so this type carries no HTTP knowledge beyond the code name.
 */
public final class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Mirrors the error codes of SPEC section 7. */
    public enum Code {
        VALIDATION,
        NOT_FOUND,
        SEAT_UNAVAILABLE,
        HOLD_EXPIRED,
        BOOKING_STATE,
        PAYMENT_IN_PROGRESS,
        SHOW_STARTED,
        LIMIT_EXCEEDED,
        VERSION_CONFLICT,
        /** Business conflict with no finer code: duplicate email, entity still in use. */
        CONFLICT,
        /** Bad credentials; the web layer answers 401. */
        UNAUTHENTICATED
    }

    private final Code code;
    private final transient Map<String, String> errors;
    private final transient List<String> conflicts;

    private DomainException(
            Code code, String message, Map<String, String> errors, List<String> conflicts) {
        super(message);
        this.code = code;
        this.errors = Map.copyOf(errors);
        this.conflicts = List.copyOf(conflicts);
    }

    public Code code() {
        return code;
    }

    /** Field path to message, non-empty only for {@link Code#VALIDATION}. */
    public Map<String, String> errors() {
        return errors;
    }

    /** Seat ids that could not be taken, non-empty only for {@link Code#SEAT_UNAVAILABLE}. */
    public List<String> conflicts() {
        return conflicts;
    }

    public static DomainException validation(String field, String message) {
        return validation(Map.of(field, message));
    }

    public static DomainException validation(Map<String, String> errors) {
        return new DomainException(Code.VALIDATION, "Validation failed", errors, List.of());
    }

    public static DomainException notFound(String what) {
        return new DomainException(Code.NOT_FOUND, what + " not found", Map.of(), List.of());
    }

    public static DomainException seatUnavailable(List<SeatId> seats) {
        List<String> ids = seats.stream().map(SeatId::toString).sorted().toList();
        return new DomainException(
                Code.SEAT_UNAVAILABLE,
                "Seat " + String.join(", ", ids) + " is no longer available",
                Map.of(),
                ids);
    }

    public static DomainException holdExpired() {
        return new DomainException(
                Code.HOLD_EXPIRED, "Hold or booking has expired", Map.of(), List.of());
    }

    public static DomainException bookingState(String message) {
        return new DomainException(Code.BOOKING_STATE, message, Map.of(), List.of());
    }

    public static DomainException paymentInProgress() {
        return new DomainException(
                Code.PAYMENT_IN_PROGRESS,
                "A payment is in progress for this booking",
                Map.of(),
                List.of());
    }

    public static DomainException showStarted() {
        return new DomainException(
                Code.SHOW_STARTED, "The show has already started", Map.of(), List.of());
    }

    public static DomainException limitExceeded(String message) {
        return new DomainException(Code.LIMIT_EXCEEDED, message, Map.of(), List.of());
    }

    public static DomainException conflict(String message) {
        return new DomainException(Code.CONFLICT, message, Map.of(), List.of());
    }

    public static DomainException unauthenticated() {
        return new DomainException(
                Code.UNAUTHENTICATED, "Invalid credentials", Map.of(), List.of());
    }

    public static DomainException versionConflict() {
        return new DomainException(
                Code.VERSION_CONFLICT, "The booking was changed concurrently", Map.of(), List.of());
    }
}
