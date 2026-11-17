package dev.thiru.bms.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    private static Movie movie(long minutes) {
        return new Movie(
                1,
                "Kaveri Nights",
                List.of("en", "ta"),
                List.of("Drama"),
                Certificate.UA,
                (int) minutes,
                LocalDate.of(2026, 11, 1),
                "/posters/k.jpg",
                "desc");
    }

    private static Screen screen() {
        return new Screen(5, 2, "Screen 1", SeatLayoutTest.sample());
    }

    private static Map<String, Long> prices() {
        return Map.of("CLASSIC", 15000L, "PRIME", 25000L);
    }

    private static Show show(long id, Instant start, long minutes) {
        return Show.schedule(
                id, movie(minutes), screen(), start, "en", ShowFormat.TWO_D, prices(), List.of());
    }

    @Test
    void cityIdAndZoneAreValidated() {
        assertDoesNotThrow(() -> City.of("bengaluru", "Bengaluru", "Asia/Kolkata"));
        assertThrows(DomainException.class, () -> City.of("B", "x", "Asia/Kolkata"));
        assertThrows(DomainException.class, () -> City.of("Bengaluru", "x", "Asia/Kolkata"));
        assertThrows(DomainException.class, () -> City.of("pune", "Pune", "Mars/Base"));
    }

    @Test
    void screenCapacityMustBeWithinOneAndOneThousand() {
        assertEquals(23, screen().capacity());
        var rows = new java.util.ArrayList<SeatLayout.Row>();
        for (int i = 0; i < 21; i++) {
            rows.add(
                    new SeatLayout.Row(
                            String.valueOf((char) ('A' + i)), 50, "CLASSIC", List.of(), List.of()));
        }
        SeatLayout big = new SeatLayout(List.of(new SeatLayout.Category("CLASSIC", "C")), rows);
        assertThrows(DomainException.class, () -> new Screen(1, 1, "Big", big));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "ftp://x/y.png", "//cdn.x/y.png", "http://x/y.png", "posters/y.png"})
    void moviePosterMustBeHttpsOrSiteRelative(String url) {
        assertThrows(
                DomainException.class,
                () ->
                        new Movie(
                                1,
                                "T",
                                List.of("en"),
                                List.of("Drama"),
                                Certificate.U,
                                90,
                                LocalDate.of(2026, 1, 1),
                                url,
                                ""));
    }

    @Test
    void movieFieldRules() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        assertDoesNotThrow(
                () ->
                        new Movie(
                                1,
                                "T".repeat(120),
                                List.of("en"),
                                List.of("Drama"),
                                Certificate.S,
                                400,
                                d,
                                "https://x.example/p.png",
                                "d".repeat(2000)));
        List<String> en = List.of("en");
        List<String> dr = List.of("Drama");
        assertThrows(
                DomainException.class,
                () -> new Movie(1, "", en, dr, Certificate.U, 90, d, "/p", ""));
        assertThrows(
                DomainException.class,
                () -> new Movie(1, "T".repeat(121), en, dr, Certificate.U, 90, d, "/p", ""));
        assertThrows(
                DomainException.class,
                () -> new Movie(1, "T", en, dr, Certificate.U, 0, d, "/p", ""));
        assertThrows(
                DomainException.class,
                () -> new Movie(1, "T", en, dr, Certificate.U, 401, d, "/p", ""));
        assertThrows(
                DomainException.class,
                () -> new Movie(1, "T", List.of(), dr, Certificate.U, 90, d, "/p", ""));
        assertThrows(
                DomainException.class,
                () -> new Movie(1, "T", List.of("en", "en"), dr, Certificate.U, 90, d, "/p", ""));
        assertThrows(
                DomainException.class,
                () -> new Movie(1, "T", en, List.of(), Certificate.U, 90, d, "/p", ""));
        assertThrows(
                DomainException.class,
                () -> new Movie(1, "T", en, dr, Certificate.U, 90, d, "/p", "d".repeat(2001)));
    }

    @Test
    void showOccupiesDurationPlusFifteenMinutes() {
        Show s = show(1, T0, 120);
        assertEquals(T0.plus(Duration.ofMinutes(135)), s.endsAt());
    }

    @Test
    void showsOnOneScreenMustNotOverlapButMayTouch() {
        Show a = show(1, T0, 120); // ends 12:15
        Show touching =
                Show.schedule(
                        2,
                        movie(60),
                        screen(),
                        a.endsAt(),
                        "en",
                        ShowFormat.IMAX,
                        prices(),
                        List.of(a));
        assertFalse(touching.overlaps(a));
        assertFalse(a.overlaps(touching));
        Instant oneMinuteEarly = a.endsAt().minusSeconds(60);
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () ->
                                Show.schedule(
                                        3,
                                        movie(60),
                                        screen(),
                                        oneMinuteEarly,
                                        "en",
                                        ShowFormat.TWO_D,
                                        prices(),
                                        List.of(a)));
        assertTrue(e.errors().containsKey("startsAt"));
    }

    @Test
    void overlapIgnoresCancelledShowsAndTheShowItself() {
        Show a = show(1, T0, 120);
        Show cancelledSlot = a.cancel();
        assertDoesNotThrow(
                () ->
                        Show.schedule(
                                2,
                                movie(120),
                                screen(),
                                T0,
                                "en",
                                ShowFormat.TWO_D,
                                prices(),
                                List.of(cancelledSlot)));
        assertDoesNotThrow(
                () ->
                        Show.schedule(
                                1,
                                movie(120),
                                screen(),
                                T0,
                                "en",
                                ShowFormat.TWO_D,
                                prices(),
                                List.of(a)));
    }

    @Test
    void showLanguageMustBelongToMovie() {
        var e =
                assertThrows(
                        DomainException.class,
                        () ->
                                Show.schedule(
                                        1,
                                        movie(90),
                                        screen(),
                                        T0,
                                        "fr",
                                        ShowFormat.TWO_D,
                                        prices(),
                                        List.of()));
        assertTrue(e.errors().containsKey("language"));
    }

    @Test
    void showPricesCoverEveryCategoryAndNothingElse() {
        for (Map<String, Long> bad :
                List.of(
                        Map.of("CLASSIC", 15000L),
                        Map.of("CLASSIC", 15000L, "PRIME", 25000L, "EXTRA", 20000L),
                        Map.of("CLASSIC", 99L, "PRIME", 25000L),
                        Map.of("CLASSIC", 15000L, "PRIME", 1_000_001L))) {
            var e =
                    assertThrows(
                            DomainException.class,
                            () ->
                                    Show.schedule(
                                            1,
                                            movie(90),
                                            screen(),
                                            T0,
                                            "en",
                                            ShowFormat.TWO_D,
                                            bad,
                                            List.of()));
            assertTrue(e.errors().containsKey("prices"), bad.toString());
        }
        assertDoesNotThrow(
                () ->
                        Show.schedule(
                                1,
                                movie(90),
                                screen(),
                                T0,
                                "en",
                                ShowFormat.TWO_D,
                                Map.of("CLASSIC", 100L, "PRIME", 1_000_000L),
                                List.of()));
    }

    @Test
    void showStartAndCancel() {
        Show s = show(1, T0, 90);
        assertFalse(s.hasStarted(T0.minusMillis(1)));
        assertTrue(s.hasStarted(T0));
        assertEquals(ShowStatus.CANCELLED, s.cancel().status());
        assertEquals(15000L, s.minPrice());
        assertEquals("3D", ShowFormat.THREE_D.label());
        assertEquals(ShowFormat.IMAX, ShowFormat.parse("IMAX"));
    }
}
