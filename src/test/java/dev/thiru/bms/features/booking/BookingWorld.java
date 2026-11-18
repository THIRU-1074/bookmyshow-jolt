package dev.thiru.bms.features.booking;

import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.kernel.booking.HoldStatus;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.testing.MutableClock;
import dev.thiru.bms.testing.RecordingEvents;
import dev.thiru.bms.testing.RecordingGateway;
import dev.thiru.bms.testing.SequentialIds;
import dev.thiru.bms.testing.TestDb;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The booking use cases on a migrated V2 database ({@link TestDb}) with the slice's fakes. The
 * clock starts at 08:00 IST on the seed date. {@link #show(int)} picks a 2D show in Audi 1 (screen
 * 1, LARGE layout: rows A to F CLASSIC at 18000 paise, G to J PRIME at 28000, A1 and J20 blocked)
 * on a given day.
 */
final class BookingWorld implements AutoCloseable, AfterEachCallback {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    final Path dir;
    final TestDb test;
    final Db db;
    final LocalDate seedDate;
    final MutableClock clock;
    final BookingPolicy policy = BookingPolicy.defaults();
    final RecordingEvents events = new RecordingEvents();
    final SequentialIds ids = new SequentialIds();
    final RecordingGateway gateway = new RecordingGateway();

    final CreateHold holds;
    final CreateBooking create;
    final Checkout checkout;
    final CancelBooking cancel;
    final BookingQueries queries;
    final ExpireSweep sweep;

    BookingWorld() {
        try {
            dir = Files.createTempDirectory("bms-booking");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        test = TestDb.open(dir);
        db = test.db();
        seedDate =
                db.inTransaction(
                                tx ->
                                        tx.queryOne(
                                                "SELECT min(starts_at) AS first FROM show",
                                                rs -> Db.instant(rs, "first")))
                        .orElseThrow()
                        .atZone(IST)
                        .toLocalDate();
        clock = new MutableClock(seedDate.atTime(LocalTime.of(8, 0)).atZone(IST).toInstant());
        holds = new CreateHold(db, events, ids, clock, policy);
        create = new CreateBooking(db, events, ids, clock);
        checkout = new Checkout(db, gateway, events, ids, clock, policy);
        cancel = new CancelBooking(db, gateway, events, clock);
        queries = new BookingQueries(db);
        sweep = new ExpireSweep(db, events, clock);
    }

    // ---- seed ----

    /** The first 2D show in Audi 1 on {@code day} days after the seed date. */
    long show(int day) {
        Instant from = seedDate.plusDays(day).atStartOfDay(IST).toInstant();
        return db.inTransaction(
                tx ->
                        tx.queryOne(
                                        "SELECT id FROM show WHERE screen_id = 1 AND format = '2D'"
                                                + " AND starts_at >= ? ORDER BY starts_at LIMIT 1",
                                        rs -> rs.getLong(1),
                                        Db.millis(from))
                                .orElseThrow());
    }

    Instant startsAt(long showId) {
        return db.inTransaction(
                tx ->
                        tx.queryOne(
                                        "SELECT starts_at FROM show WHERE id = ?",
                                        rs -> Db.instant(rs, "starts_at"),
                                        showId)
                                .orElseThrow());
    }

    long user(String email) {
        return db.inTransaction(
                tx ->
                        tx.insert(
                                "INSERT INTO app_user (email, password_hash, name, roles,"
                                        + " created_at) VALUES (?, 'x', 'Test', 'AUDIENCE', ?)",
                                email,
                                Db.millis(clock.instant())));
    }

    // ---- steps ----

    CreateHold.Result hold(long user, long show, String... seats) {
        return holds.execute(user, show, List.of(seats));
    }

    Booking held(long user, long show, String... seats) {
        return create.execute(user, hold(user, show, seats).hold().id());
    }

    /**
     * What the payments webhook does on success (wave 4 owns it): payment SUCCEEDED, booking
     * CONFIRMED with the version bumped, locks BOOKED.
     */
    Booking confirmed(long user, long show, String... seats) {
        Booking b = held(user, show, seats);
        Checkout.Result c = checkout.execute(user, b.id());
        db.inTransaction(
                tx -> {
                    tx.update(
                            "UPDATE payment SET status = 'SUCCEEDED', updated_at = ? WHERE id = ?",
                            Db.millis(clock.instant()),
                            c.paymentId());
                    tx.update(
                            "UPDATE booking SET status = 'CONFIRMED', version = version + 1,"
                                    + " updated_at = ? WHERE id = ?",
                            Db.millis(clock.instant()),
                            b.id());
                    tx.update(
                            "UPDATE seat_lock SET state = 'BOOKED', expires_at = NULL"
                                    + " WHERE hold_id = ?",
                            b.holdId());
                    return null;
                });
        return booking(b.id());
    }

    /** What the payments webhook does on failure: payment FAILED, booking back to HELD. */
    void paymentFailed(String paymentId, String bookingId) {
        db.inTransaction(
                tx -> {
                    tx.update("UPDATE payment SET status = 'FAILED' WHERE id = ?", paymentId);
                    tx.update(
                            "UPDATE booking SET status = 'HELD', version = version + 1"
                                    + " WHERE id = ?",
                            bookingId);
                    return null;
                });
    }

    // ---- reads ----

    Booking booking(String id) {
        return db.inTransaction(tx -> BookingSql.booking(tx, id)).orElseThrow();
    }

    BookingStatus bookingStatus(String id) {
        return booking(id).status();
    }

    HoldStatus holdStatus(String id) {
        return db.inTransaction(tx -> BookingSql.hold(tx, id)).orElseThrow().status();
    }

    List<PaymentStatus> payments(String bookingId) {
        return db.inTransaction(
                tx ->
                        tx.query(
                                "SELECT status FROM payment WHERE booking_id = ? ORDER BY rowid",
                                rs -> PaymentStatus.valueOf(rs.getString(1)),
                                bookingId));
    }

    long refunded(String bookingId) {
        return db.inTransaction(
                tx ->
                        tx.queryOne(
                                        "SELECT refunded_amount FROM payment WHERE booking_id = ?"
                                                + " ORDER BY rowid DESC LIMIT 1",
                                        rs -> rs.getLong(1),
                                        bookingId)
                                .orElseThrow());
    }

    /** {@code seat_id state booking_id expires_at} per lock, sorted by seat. */
    List<String> locks() {
        return db.inTransaction(
                tx ->
                        tx.query(
                                "SELECT seat_id, state, booking_id, expires_at FROM seat_lock"
                                        + " ORDER BY seat_id",
                                rs ->
                                        rs.getString(1)
                                                + " "
                                                + rs.getString(2)
                                                + " "
                                                + rs.getString(3)
                                                + " "
                                                + rs.getString(4)));
    }

    int count(String table) {
        return db.inTransaction(
                tx ->
                        tx.queryOne("SELECT count(*) FROM " + table, rs -> rs.getInt(1))
                                .orElseThrow());
    }

    // ---- lifecycle ----

    @Override
    public void afterEach(ExtensionContext context) {
        close();
    }

    @Override
    public void close() {
        test.close();
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | UncheckedIOException e) {
            // Best effort: the OS temp dir is cleaned anyway.
        }
    }
}
