package dev.thiru.bms.kernel;

import java.util.regex.Pattern;

/** Row letter plus number, such as {@code C7}. Row A is nearest the screen. */
public record SeatId(char row, int number) implements Comparable<SeatId> {

    public static final int MAX_NUMBER = 50;

    // No leading zeros and no whitespace, so "C07" and "C7 " are different strings that must not
    // both name one seat in the lock table.
    private static final Pattern FORMAT = Pattern.compile("([A-Z])([1-9][0-9]?)");

    public SeatId {
        if (row < 'A' || row > 'Z' || number < 1 || number > MAX_NUMBER) {
            throw DomainException.validation("seat", "invalid seat id");
        }
    }

    public static SeatId parse(String raw) {
        if (raw == null) {
            throw DomainException.validation("seat", "invalid seat id");
        }
        var m = FORMAT.matcher(raw);
        if (!m.matches()) {
            throw DomainException.validation("seat", "invalid seat id");
        }
        return new SeatId(m.group(1).charAt(0), Integer.parseInt(m.group(2)));
    }

    @Override
    public int compareTo(SeatId other) {
        int byRow = Character.compare(row, other.row);
        return byRow != 0 ? byRow : Integer.compare(number, other.number);
    }

    @Override
    public String toString() {
        return "" + row + number;
    }
}
