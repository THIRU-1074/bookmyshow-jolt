package dev.thiru.bms.domain;

import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/** Human-readable booking code: {@code BK} plus 8 Crockford base32 characters. */
public final class BookingCode {

    // Crockford drops I, L, O and U so codes read aloud over the phone stay unambiguous.
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final Pattern FORMAT = Pattern.compile("BK[0-9A-HJKMNP-TV-Z]{8}");

    private BookingCode() {}

    /** Callers pass a {@code SecureRandom}; tests pass a seeded generator. */
    public static String generate(RandomGenerator random) {
        StringBuilder sb = new StringBuilder("BK");
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static boolean isValid(String code) {
        return code != null && FORMAT.matcher(code).matches();
    }
}
