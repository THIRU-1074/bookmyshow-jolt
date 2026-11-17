package dev.thiru.bms.application;

import dev.thiru.bms.domain.port.AdminRepositories.CategoryStat;
import dev.thiru.bms.domain.port.AdminRepositories.ShowStat;
import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.domain.port.UnitOfWork;
import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.Page;
import dev.thiru.bms.kernel.catalog.Show;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** Sales and occupancy reports (admin, or a theatre owner for their cinemas) and counters. */
public final class AdminReports {

    /** {@code revenue} is what was paid minus what was refunded, in paise. */
    public record ShowReport(
            long showId,
            String movieTitle,
            String cinemaName,
            Instant startsAt,
            ShowStatus status,
            int capacity,
            int booked,
            double occupancyPercent,
            long revenue,
            long refunded) {}

    public record ShowReportDetail(ShowReport show, List<CategoryStat> byCategory) {}

    public record Stats(long activeHolds, long bookingsConfirmedToday) {}

    private final UnitOfWork uow;
    private final Clock clock;

    public AdminReports(UnitOfWork uow, Clock clock) {
        this.uow = uow;
        this.clock = clock;
    }

    public Page<ShowReport> shows(String cityId, Instant from, Instant to, int page, int size) {
        return shows(CinemaScope.everything(), cityId, from, to, page, size);
    }

    public Page<ShowReport> shows(
            CinemaScope scope, String cityId, Instant from, Instant to, int page, int size) {
        return uow.inTransaction(
                r ->
                        r.reports()
                                .showStats(cityId, scope.ownerId(), from, to, page, size)
                                .map(AdminReports::report));
    }

    public ShowReportDetail show(long showId) {
        return show(CinemaScope.everything(), showId);
    }

    public ShowReportDetail show(CinemaScope scope, long showId) {
        return uow.inTransaction(
                r -> {
                    Show show =
                            r.shows()
                                    .findById(showId)
                                    .orElseThrow(() -> DomainException.notFound("Show"));
                    scope.require(
                            r,
                            r.screens().findById(show.screenId()).orElseThrow().cinemaId(),
                            "Show");
                    return detail(r, showId);
                });
    }

    private static ShowReportDetail detail(Repositories r, long showId) {
        return new ShowReportDetail(
                report(
                        r.reports()
                                .showStat(showId)
                                .orElseThrow(() -> DomainException.notFound("Show"))),
                r.reports().categoryStats(showId));
    }

    /** "Today" is the UTC day; the spec does not name a zone for this counter. */
    public Stats stats() {
        Instant now = clock.instant();
        Instant dayStart =
                now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return uow.inTransaction(
                r ->
                        new Stats(
                                r.holds().countActive(now),
                                r.reports().countConfirmedSince(dayStart)));
    }

    private static ShowReport report(ShowStat s) {
        // One decimal place, computed in integers.
        double occupancy =
                s.capacity() == 0 ? 0 : Math.round(s.booked() * 1000.0 / s.capacity()) / 10.0;
        return new ShowReport(
                s.showId(),
                s.movieTitle(),
                s.cinemaName(),
                s.startsAt(),
                s.status(),
                s.capacity(),
                s.booked(),
                occupancy,
                s.paid() - s.refunded(),
                s.refunded());
    }
}
