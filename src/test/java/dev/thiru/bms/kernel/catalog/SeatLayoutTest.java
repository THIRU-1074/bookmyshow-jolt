package dev.thiru.bms.kernel.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.catalog.SeatLayout.Category;
import dev.thiru.bms.kernel.catalog.SeatLayout.Row;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

public class SeatLayoutTest {

    static final List<Category> CATS =
            List.of(new Category("CLASSIC", "Classic"), new Category("PRIME", "Prime"));

    public static SeatLayout sample() {
        return new SeatLayout(
                CATS,
                List.of(
                        new Row("A", 14, "CLASSIC", List.of(4, 10), List.of(1)),
                        new Row("B", 10, "PRIME", List.of(), List.of())));
    }

    private static Map<String, String> errorsOf(List<Category> cats, List<Row> rows) {
        return assertThrows(DomainException.class, () -> new SeatLayout(cats, rows)).errors();
    }

    @Test
    void capacityIsSeatsMinusBlocked() {
        SeatLayout layout = sample();
        assertEquals(14 - 1 + 10, layout.capacity());
        assertTrue(layout.isBlocked(SeatId.parse("A1")));
        assertFalse(layout.isBlocked(SeatId.parse("A2")));
        assertTrue(layout.exists(SeatId.parse("B10")));
        assertFalse(layout.exists(SeatId.parse("B11")));
        assertFalse(layout.exists(SeatId.parse("C1")));
        assertEquals("PRIME", layout.categoryOf(SeatId.parse("B3")));
        assertEquals(23, layout.bookableSeats().size());
    }

    @Test
    void acceptsTheLargestLayoutShape() {
        List<Row> rows =
                IntStream.range(0, 26)
                        .mapToObj(
                                i ->
                                        new Row(
                                                String.valueOf((char) ('A' + i)),
                                                50,
                                                "CLASSIC",
                                                null,
                                                null))
                        .toList();
        SeatLayout layout = new SeatLayout(List.of(new Category("CLASSIC", "Classic")), rows);
        assertEquals(1300, layout.capacity());
    }

    @Test
    void rowsMustBeContiguousFromA() {
        Map<String, String> errors =
                errorsOf(
                        CATS,
                        List.of(
                                new Row("A", 5, "CLASSIC", null, null),
                                new Row("C", 5, "PRIME", null, null)));
        assertTrue(errors.containsKey("rows[1].row"), errors.toString());
    }

    @Test
    void rowCountAndSeatBounds() {
        assertTrue(errorsOf(CATS, List.of()).containsKey("rows"));
        assertTrue(
                errorsOf(
                                CATS,
                                List.of(
                                        new Row("A", 0, "CLASSIC", null, null),
                                        new Row("B", 51, "PRIME", null, null)))
                        .keySet()
                        .containsAll(List.of("rows[0].seats", "rows[1].seats")));
    }

    @Test
    void categoriesMustBeValidUniqueAndUsed() {
        Function<List<Category>, Map<String, String>> check =
                cats ->
                        errorsOf(
                                cats,
                                List.of(
                                        new Row("A", 5, "CLASSIC", null, null),
                                        new Row("B", 5, "PRIME", null, null)));
        assertTrue(
                check.apply(List.of(new Category("classic", "x"), new Category("PRIME", "Prime")))
                        .containsKey("categories[0].code"));
        assertTrue(
                check.apply(List.of(new Category("CLASSIC", "a"), new Category("CLASSIC", "b")))
                        .containsKey("categories[1].code"));
        Map<String, String> unused =
                check.apply(
                        List.of(
                                new Category("CLASSIC", "a"),
                                new Category("PRIME", "b"),
                                new Category("EXTRA", "c")));
        assertTrue(unused.containsKey("categories[2].code"), unused.toString());
        assertTrue(
                errorsOf(CATS, List.of(new Row("A", 5, "NOPE", null, null)))
                        .containsKey("rows[0].category"));
    }

    @Test
    void aisleAndBlockedStayWithinTheRow() {
        Map<String, String> errors =
                errorsOf(
                        CATS,
                        List.of(
                                new Row("A", 5, "CLASSIC", List.of(0, 6), List.of(9)),
                                new Row("B", 5, "PRIME", null, List.of(2, 2))));
        assertTrue(errors.containsKey("rows[0].aisleAfter"));
        assertTrue(errors.containsKey("rows[0].blocked"));
        assertTrue(errors.containsKey("rows[1].blocked"), "duplicate blocked seat");
    }

    @Test
    void layoutWithNoBookableSeatIsRejected() {
        assertTrue(
                errorsOf(
                                List.of(new Category("CLASSIC", "c")),
                                List.of(new Row("A", 1, "CLASSIC", null, List.of(1))))
                        .containsKey("rows"));
    }
}
