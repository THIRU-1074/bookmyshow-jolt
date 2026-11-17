package dev.thiru.bms.web;

/** Display formatting for paise; arithmetic stays in {@code long} paise everywhere else. */
final class Money {

    private Money() {}

    /** {@code 45050} to {@code "450.50"}, using integer arithmetic only. */
    static String rupees(long paise) {
        String sign = paise < 0 ? "-" : "";
        long abs = Math.abs(paise);
        return sign + (abs / 100) + "." + String.format("%02d", abs % 100);
    }
}
