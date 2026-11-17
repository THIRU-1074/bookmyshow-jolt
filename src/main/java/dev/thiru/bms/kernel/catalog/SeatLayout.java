package dev.thiru.bms.kernel.catalog;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A screen's seat plan. Construction validates the SPEC section 3 rules and reports every problem
 * at once under nested paths such as {@code rows[3].seats}, so the admin UI can mark each field.
 */
public record SeatLayout(List<Category> categories, List<Row> rows) {

    private static final Pattern CATEGORY_CODE = Pattern.compile("[A-Z_]{2,16}");

    public record Category(String code, String name) {}

    public record Row(
            String row,
            int seats,
            String category,
            List<Integer> aisleAfter,
            List<Integer> blocked) {
        public Row {
            aisleAfter = aisleAfter == null ? List.of() : List.copyOf(aisleAfter);
            blocked = blocked == null ? List.of() : List.copyOf(blocked);
        }
    }

    public SeatLayout {
        categories = categories == null ? List.of() : List.copyOf(categories);
        rows = rows == null ? List.of() : List.copyOf(rows);
        Map<String, String> errors = new LinkedHashMap<>();
        validateCategories(categories, errors);
        validateRows(categories, rows, errors);
        if (errors.isEmpty() && capacity(rows) == 0) {
            errors.put("rows", "layout needs at least one bookable seat");
        }
        if (!errors.isEmpty()) {
            throw DomainException.validation(errors);
        }
    }

    private static void validateCategories(List<Category> categories, Map<String, String> errors) {
        if (categories.isEmpty()) {
            errors.put("categories", "at least one category is required");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < categories.size(); i++) {
            Category c = categories.get(i);
            if (c == null || c.code() == null || !CATEGORY_CODE.matcher(c.code()).matches()) {
                errors.put("categories[" + i + "].code", "must match [A-Z_]{2,16}");
            } else if (!seen.add(c.code())) {
                errors.put("categories[" + i + "].code", "duplicate category");
            }
            if (c == null || c.name() == null || c.name().isBlank()) {
                errors.put("categories[" + i + "].name", "must not be blank");
            }
        }
    }

    private static void validateRows(
            List<Category> categories, List<Row> rows, Map<String, String> errors) {
        if (rows.isEmpty() || rows.size() > 26) {
            errors.put("rows", "must have 1 to 26 rows");
        }
        Set<String> codes = new HashSet<>();
        categories.stream()
                .filter(c -> c != null && c.code() != null)
                .forEach(c -> codes.add(c.code()));
        Set<String> used = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String p = "rows[" + i + "].";
            if (r == null) {
                errors.put("rows[" + i + "]", "must not be null");
                continue;
            }
            String expected = String.valueOf((char) ('A' + i));
            if (!expected.equals(r.row())) {
                errors.put(
                        p + "row",
                        "rows must be unique and contiguous from A, expected " + expected);
            }
            if (r.seats() < 1 || r.seats() > SeatId.MAX_NUMBER) {
                errors.put(p + "seats", "must be 1 to " + SeatId.MAX_NUMBER);
            }
            if (r.category() == null || !codes.contains(r.category())) {
                errors.put(p + "category", "unknown category");
            } else {
                used.add(r.category());
            }
            checkWithin(errors, p + "aisleAfter", r.aisleAfter(), r.seats(), false);
            checkWithin(errors, p + "blocked", r.blocked(), r.seats(), true);
        }
        for (int i = 0; i < categories.size(); i++) {
            Category c = categories.get(i);
            if (c != null
                    && c.code() != null
                    && !used.contains(c.code())
                    && !errors.containsKey("categories[" + i + "].code")) {
                errors.put("categories[" + i + "].code", "category is not used by any row");
            }
        }
    }

    // Duplicate blocked seats would make capacity (seats minus blocked) undercount.
    private static void checkWithin(
            Map<String, String> errors,
            String path,
            List<Integer> values,
            int seats,
            boolean unique) {
        Set<Integer> seen = new HashSet<>();
        for (int v : values) {
            if (v < 1 || v > seats) {
                errors.put(path, "values must be within 1 to " + seats);
                return;
            }
            if (unique && !seen.add(v)) {
                errors.put(path, "duplicate value " + v);
                return;
            }
        }
    }

    private static int capacity(List<Row> rows) {
        return rows.stream().mapToInt(r -> r.seats() - r.blocked().size()).sum();
    }

    /** Sum of seats minus blocked. */
    public int capacity() {
        return capacity(rows);
    }

    private Row rowOf(SeatId seat) {
        int index = seat.row() - 'A';
        return index < rows.size() ? rows.get(index) : null;
    }

    /** True when the seat is part of the plan, blocked or not. */
    public boolean exists(SeatId seat) {
        Row row = rowOf(seat);
        return row != null && seat.number() <= row.seats();
    }

    public boolean isBlocked(SeatId seat) {
        Row row = rowOf(seat);
        return row != null && row.blocked().contains(seat.number());
    }

    public String categoryOf(SeatId seat) {
        Row row = rowOf(seat);
        if (row == null || seat.number() > row.seats()) {
            throw DomainException.validation("seat", "unknown seat");
        }
        return row.category();
    }

    public String categoryName(String code) {
        return categories.stream()
                .filter(c -> c.code().equals(code))
                .map(Category::name)
                .findFirst()
                .orElse(code);
    }

    public Set<String> categoryCodes() {
        Set<String> codes = new HashSet<>();
        categories.forEach(c -> codes.add(c.code()));
        return codes;
    }

    /** Every seat that can be sold, in row then number order. */
    public List<SeatId> bookableSeats() {
        List<SeatId> out = new ArrayList<>();
        for (Row r : rows) {
            for (int n = 1; n <= r.seats(); n++) {
                if (!r.blocked().contains(n)) {
                    out.add(new SeatId(r.row().charAt(0), n));
                }
            }
        }
        return out;
    }
}
