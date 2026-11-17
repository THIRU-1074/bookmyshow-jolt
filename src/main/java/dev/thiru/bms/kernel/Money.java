package dev.thiru.bms.kernel;

/** Display formatting for paise; arithmetic stays in {@code long} paise everywhere else. */
public final class Money {

    private Money() {}

    /** {@code 45050} to {@code "450.50"}, using integer arithmetic only. */
    public static String rupees(long paise) {
        String sign = paise < 0 ? "-" : "";
        long abs = Math.abs(paise);
        return sign + (abs / 100) + "." + String.format("%02d", abs % 100);
    }
}
