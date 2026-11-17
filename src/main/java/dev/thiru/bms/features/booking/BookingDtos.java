package dev.thiru.bms.features.booking;

import dev.thiru.bms.kernel.booking.Booking;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * The booking slice's wire types, copied from {@code web.Dtos} and {@code web.BookingModule}: same
 * component names, order and types, so the JSON is byte-identical. {@code RecordGeneratorTest}
 * holds them to {@code api/openapi.yaml}.
 */
final class BookingDtos {

    private BookingDtos() {}

    record HoldRequest(Long showId, List<String> seats) {}

    record BookingRequest(String holdId) {}

    record HoldDto(
            String id,
            long showId,
            List<String> seats,
            long amount,
            String currency,
            Instant expiresAt) {}

    record ShowInfoDto(
            long id,
            String movieTitle,
            String cinemaName,
            String screenName,
            Instant startsAt,
            LocalTime localTime) {}

    record BookingSeatDto(String id, String category, long price) {}

    record BookingDto(
            String id,
            String code,
            String status,
            ShowInfoDto show,
            List<BookingSeatDto> seats,
            long amount,
            String currency,
            long refundAmount,
            Instant expiresAt,
            long version,
            Instant createdAt) {

        static BookingDto of(BookingQueries.BookingView v) {
            Booking b = v.booking();
            BookingSql.ShowInfo s = v.show();
            return new BookingDto(
                    b.id(),
                    b.code(),
                    b.status().name(),
                    new ShowInfoDto(
                            s.id(),
                            s.movieTitle(),
                            s.cinemaName(),
                            s.screenName(),
                            s.startsAt(),
                            s.localTime()),
                    b.seats().stream()
                            .map(
                                    x ->
                                            new BookingSeatDto(
                                                    x.seat().toString(), x.category(), x.price()))
                            .toList(),
                    b.amount(),
                    b.currency(),
                    b.refundAmount(),
                    b.expiresAt(),
                    b.version(),
                    b.createdAt());
        }
    }

    record CheckoutDto(
            String paymentId,
            String checkoutUrl,
            long amount,
            String currency,
            Instant expiresAt) {}
}
