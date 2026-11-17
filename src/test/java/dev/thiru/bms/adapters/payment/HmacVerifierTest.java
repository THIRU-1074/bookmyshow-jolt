package dev.thiru.bms.adapters.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class HmacVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-20T13:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String BODY = "{\"eventId\":\"e1\"}";
    private final HmacVerifier verifier = new HmacVerifier("s3cret", CLOCK);
    private final String ts = Long.toString(NOW.getEpochSecond());

    @Test
    void signatureHasV1HexFormatAndIsDeterministic() {
        String sig = verifier.sign(NOW.getEpochSecond(), BODY);
        assertTrue(sig.matches("v1=[0-9a-f]{64}"), sig);
        assertEquals(sig, verifier.sign(NOW.getEpochSecond(), BODY));
    }

    @Test
    void acceptsValidSignature() {
        String sig = verifier.sign(NOW.getEpochSecond(), BODY);
        assertEquals(HmacVerifier.Outcome.VALID, verifier.verify(ts, sig, BODY));
    }

    @Test
    void rejectsTamperedBody() {
        String sig = verifier.sign(NOW.getEpochSecond(), BODY);
        assertEquals(HmacVerifier.Outcome.BAD_SIGNATURE, verifier.verify(ts, sig, BODY + " "));
    }

    @Test
    void rejectsWrongSecret() {
        String sig = new HmacVerifier("other", CLOCK).sign(NOW.getEpochSecond(), BODY);
        assertEquals(HmacVerifier.Outcome.BAD_SIGNATURE, verifier.verify(ts, sig, BODY));
    }

    @Test
    void rejectsTamperedTimestamp() {
        String sig = verifier.sign(NOW.getEpochSecond(), BODY);
        assertEquals(
                HmacVerifier.Outcome.BAD_SIGNATURE,
                verifier.verify(Long.toString(NOW.getEpochSecond() + 1), sig, BODY));
    }

    @Test
    void skewBoundaryIs300Seconds() {
        for (long delta : new long[] {-300, 300}) {
            long t = NOW.getEpochSecond() + delta;
            assertEquals(
                    HmacVerifier.Outcome.VALID,
                    verifier.verify(Long.toString(t), verifier.sign(t, BODY), BODY));
        }
    }

    @Test
    void rejectsStaleAndFutureTimestamps() {
        long stale = NOW.getEpochSecond() - 301;
        long future = NOW.getEpochSecond() + 301;
        assertEquals(
                HmacVerifier.Outcome.STALE_TIMESTAMP,
                verifier.verify(Long.toString(stale), verifier.sign(stale, BODY), BODY));
        assertEquals(
                HmacVerifier.Outcome.STALE_TIMESTAMP,
                verifier.verify(Long.toString(future), verifier.sign(future, BODY), BODY));
    }

    @Test
    void rejectsMalformedInput() {
        String sig = verifier.sign(NOW.getEpochSecond(), BODY);
        String hex = sig.substring(3);
        assertEquals(HmacVerifier.Outcome.MALFORMED, verifier.verify(null, sig, BODY));
        assertEquals(HmacVerifier.Outcome.MALFORMED, verifier.verify(ts, null, BODY));
        assertEquals(HmacVerifier.Outcome.MALFORMED, verifier.verify(ts, sig, null));
        assertEquals(HmacVerifier.Outcome.MALFORMED, verifier.verify("abc", sig, BODY));
        assertEquals(HmacVerifier.Outcome.MALFORMED, verifier.verify(ts, hex, BODY));
        assertEquals(HmacVerifier.Outcome.MALFORMED, verifier.verify(ts, "v2=" + hex, BODY));
        assertEquals(
                HmacVerifier.Outcome.MALFORMED, verifier.verify(ts, "v1=" + "z".repeat(64), BODY));
        assertEquals(
                HmacVerifier.Outcome.MALFORMED,
                verifier.verify(ts, "v1=" + hex.substring(2), BODY));
        assertEquals(
                HmacVerifier.Outcome.MALFORMED,
                verifier.verify(ts, "v1=" + hex.toUpperCase(), BODY));
    }

    @Test
    void rejectsBlankSecret() {
        assertThrows(IllegalArgumentException.class, () -> new HmacVerifier("", CLOCK));
    }
}
