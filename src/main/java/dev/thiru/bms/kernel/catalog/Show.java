package dev.thiru.bms.kernel.catalog;

import dev.thiru.bms.kernel.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A screening. {@code endsAt} is start plus film duration plus a cleaning gap, so the overlap rule
 * is a plain half-open interval test and the schema's {@code ends_at} needs no recomputation.
 */
public record Show(
        long id,
        long movieId,
        long screenId,
        Instant startsAt,
        Instant endsAt,
        String language,
        ShowFormat format,
        Map<String, Long> prices,
        ShowStatus status) {

    public static final Duration CLEANING_GAP = Duration.ofMinutes(15);
    public static final long MIN_PRICE = 100;
    public static final long MAX_PRICE = 1_000_000;

    public Show {
        prices = Map.copyOf(prices);
    }

    /**
     * Builds a SCHEDULED show after checking language, prices and overlap against the other shows
     * on the screen. Cancelled shows do not block their slot (the spec is silent; a cancelled show
     * holds no seats).
     */
    public static Show schedule(
            long id,
            Movie movie,
            Screen screen,
            Instant startsAt,
            String language,
            ShowFormat format,
            Map<String, Long> prices,
            Collection<Show> existingOnScreen) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!movie.languages().contains(language)) {
            errors.put("language", "must be one of the movie's languages");
        }
        if (format == null) {
            errors.put("format", "must be one of 2D, 3D, IMAX");
        }
        if (prices == null
                || !prices.keySet().equals(screen.layout().categoryCodes())
                || prices.values().stream()
                        .anyMatch(p -> p == null || p < MIN_PRICE || p > MAX_PRICE)) {
            errors.put(
                    "prices",
                    "needs a price of "
                            + MIN_PRICE
                            + " to "
                            + MAX_PRICE
                            + " paise for every category and no others");
        }
        if (startsAt == null) {
            errors.put("startsAt", "is required");
        }
        if (!errors.isEmpty()) {
            throw DomainException.validation(errors);
        }
        Instant endsAt =
                startsAt.plus(Duration.ofMinutes(movie.durationMinutes())).plus(CLEANING_GAP);
        Show candidate =
                new Show(
                        id,
                        movie.id(),
                        screen.id(),
                        startsAt,
                        endsAt,
                        language,
                        format,
                        prices,
                        ShowStatus.SCHEDULED);
        for (Show other : existingOnScreen) {
            if (other.id() != id
                    && other.status() == ShowStatus.SCHEDULED
                    && candidate.overlaps(other)) {
                throw DomainException.validation(
                        "startsAt", "overlaps another show on this screen");
            }
        }
        return candidate;
    }

    /** Half-open intervals: a show may start exactly when the previous one's slot ends. */
    public boolean overlaps(Show other) {
        return startsAt.isBefore(other.endsAt) && other.startsAt.isBefore(endsAt);
    }

    public boolean hasStarted(Instant now) {
        return !now.isBefore(startsAt);
    }

    public Show cancel() {
        return new Show(
                id,
                movieId,
                screenId,
                startsAt,
                endsAt,
                language,
                format,
                prices,
                ShowStatus.CANCELLED);
    }

    public long minPrice() {
        return prices.values().stream().mapToLong(Long::longValue).min().orElse(0);
    }
}
