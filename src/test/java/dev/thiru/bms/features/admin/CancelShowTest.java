package dev.thiru.bms.features.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.account.CinemaScope;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.testing.MutableClock;
import dev.thiru.bms.testing.RecordingEvents;
import dev.thiru.bms.testing.RecordingGateway;
import dev.thiru.bms.testing.TestDb;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Show cancellation on the V2 seed: screen 1 (Audi 1, cinema 1, LARGE layout). */
class CancelShowTest {

    @TempDir Path dir;
    TestDb test;
    Db db;
    MutableClock clock;
    AdminFixtures f;
    final RecordingGateway gateway = new RecordingGateway();
    final RecordingEvents events = new RecordingEvents();
    CancelShow cancel;
    long show;

    @BeforeEach
    void open() {
        test = TestDb.open(dir);
        db = test.db();
        clock =
                new MutableClock(
                        AdminFixtures.seedDate(db)
                                .atTime(LocalTime.of(8, 0))
                                .atZone(AdminFixtures.IST)
                                .toInstant());
        f = new AdminFixtures(db, clock);
        cancel = new CancelShow(db, gateway, events, clock);
        show = f.show(1, 1);
    }

    @AfterEach
    void close() {
        test.close();
    }

    @Test
    void refundsConfirmedCancelsOpenReleasesHoldsAndIsIdempotent() {
        String paid = f.confirmed(f.user("a@x.in"), show, "A3", "A2");
        String held = f.held(f.user("b@x.in"), show, "A5");
        String pending = f.pending(f.user("c@x.in"), show, "A6");
        String hold = f.hold(f.user("d@x.in"), show, "B1");
        clock.advance(Duration.ofMinutes(1));

        assertEquals(
                new CancelShow.Result(1, 2, 1), cancel.execute(CinemaScope.everything(), show));

        assertEquals("CANCELLED", f.status("show", show));
        assertEquals("REFUNDED", f.status("booking", paid));
        assertEquals(f.amount(paid) + ":1", f.refundAndVersion(paid));
        assertEquals("CANCELLED", f.status("booking", held));
        assertEquals("0:1", f.refundAndVersion(held));
        assertEquals("CANCELLED", f.status("booking", pending));
        assertEquals("RELEASED", f.status("seat_hold", hold));
        assertEquals(List.of("REFUNDED:" + f.amount(paid) + ":gw_p-3"), f.payments(paid));
        assertEquals(List.of("INITIATED:0:gw_p-8"), f.payments(pending));
        assertEquals(List.of("gw_p-3:" + f.amount(paid)), gateway.refunds);
        assertEquals(0, f.count("SELECT count(*) FROM seat_lock WHERE show_id = ?", show));
        // Holds first, then bookings by creation; seats sorted within each event.
        assertEquals(
                List.of(released("B1"), released("A2", "A3"), released("A5"), released("A6")),
                events.published);

        assertEquals(
                new CancelShow.Result(0, 0, 0), cancel.execute(CinemaScope.everything(), show));
        assertEquals(1, gateway.refunds.size(), "no second refund");
        assertEquals(4, events.published.size(), "no second event");
    }

    private SeatEvent released(String... seats) {
        return new SeatEvent.Released(
                show,
                List.of(seats).stream().map(SeatId::parse).toList(),
                SeatEvent.Reason.CANCELLED);
    }

    @Test
    void worksAfterTheShowStartedAndRejectsUnknownShows() {
        clock.advance(Duration.ofDays(4));
        cancel.execute(CinemaScope.everything(), show);
        assertEquals("CANCELLED", f.status("show", show));
        assertEquals(
                "Show not found",
                assertThrows(
                                DomainException.class,
                                () -> cancel.execute(CinemaScope.everything(), 999_999))
                        .getMessage());
    }

    @Test
    void aFailingRefundLeavesEverythingUntouchedAndPublishesNothing() {
        String paid = f.confirmed(f.user("a@x.in"), show, "A2");
        f.hold(f.user("d@x.in"), show, "B1");
        gateway.failRefunds = true;
        assertThrows(
                IllegalStateException.class, () -> cancel.execute(CinemaScope.everything(), show));
        assertEquals("SCHEDULED", f.status("show", show));
        assertEquals("CONFIRMED", f.status("booking", paid));
        assertEquals(2, f.count("SELECT count(*) FROM seat_lock WHERE show_id = ?", show));
        assertEquals(List.of(), events.published);
    }

    @Test
    void aFailingSubscriberDoesNotUndoTheCancellation() {
        f.hold(f.user("d@x.in"), show, "B1");
        events.fail = true;
        assertEquals(
                new CancelShow.Result(0, 0, 1), cancel.execute(CinemaScope.everything(), show));
        assertEquals("CANCELLED", f.status("show", show));
    }

    @Test
    void onlyAnOwnerOfTheShowsCinemaMayCancelIt() {
        long owner = f.user("o@x.in");
        f.link(owner, 2);
        DomainException e =
                assertThrows(
                        DomainException.class,
                        () -> cancel.execute(CinemaScope.ownedBy(owner), show));
        assertEquals("Show not found", e.getMessage());
        assertEquals("SCHEDULED", f.status("show", show));
        f.link(owner, 1);
        cancel.execute(CinemaScope.ownedBy(owner), show);
        assertEquals("CANCELLED", f.status("show", show));
    }
}
