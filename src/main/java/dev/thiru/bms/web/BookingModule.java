package dev.thiru.bms.web;

import dev.thiru.bms.application.BookingQueries;
import dev.thiru.bms.application.CancelBooking;
import dev.thiru.bms.application.Checkout;
import dev.thiru.bms.application.CreateBooking;
import dev.thiru.bms.application.CreateHold;
import dev.thiru.bms.domain.Booking;
import dev.thiru.bms.domain.BookingStatus;
import dev.thiru.bms.domain.SeatId;
import in.jolt.Middleware;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.RouteModule;
import in.jolt.Router;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds, bookings, checkout, cancel and ticket. Every handler passes the caller's id from the
 * verified token to the use case, which applies the ownership rule; a stranger's id reads as 404 so
 * holds and bookings cannot be probed.
 */
final class BookingModule implements RouteModule {

    record HoldRequest(Long showId, List<String> seats) {}

    record BookingRequest(String holdId) {}

    private final CreateHold holds;
    private final CreateBooking create;
    private final BookingQueries queries;
    private final Checkout checkout;
    private final CancelBooking cancel;
    private final Middleware idempotent;
    private final Middleware holdLimit;

    BookingModule(
            CreateHold holds,
            CreateBooking create,
            BookingQueries queries,
            Checkout checkout,
            CancelBooking cancel,
            Middleware idempotent,
            Middleware holdLimit) {
        this.holds = holds;
        this.create = create;
        this.queries = queries;
        this.checkout = checkout;
        this.cancel = cancel;
        this.idempotent = idempotent;
        this.holdLimit = holdLimit;
    }

    @Override
    public void register(Router r) {
        r.post("/api/holds", holdLimit, idempotent, this::hold);
        r.delete(
                "/api/holds/:id",
                (req, res) -> {
                    holds.release(Principal.of(req).userId(), req.param("id"));
                    res.status(204);
                });
        r.post("/api/bookings", idempotent, this::book);
        r.get("/api/bookings", this::list);
        r.get(
                "/api/bookings/:id",
                (req, res) -> {
                    Principal who = Principal.of(req);
                    res.noStore()
                            .json(
                                    Dtos.BookingDto.of(
                                            queries.get(
                                                    who.userId(), who.isAdmin(), req.param("id"))));
                });
        r.post(
                "/api/bookings/:id/checkout",
                idempotent,
                (req, res) -> {
                    Checkout.Result c =
                            checkout.execute(Principal.of(req).userId(), req.param("id"));
                    res.status(201)
                            .noStore()
                            .json(
                                    new Dtos.CheckoutDto(
                                            c.paymentId(),
                                            c.checkoutUrl(),
                                            c.amount(),
                                            c.currency(),
                                            c.expiresAt()));
                });
        r.post(
                "/api/bookings/:id/cancel",
                idempotent,
                (req, res) -> {
                    long userId = Principal.of(req).userId();
                    Booking done = cancel.execute(userId, req.param("id"));
                    res.noStore().json(Dtos.BookingDto.of(queries.get(userId, false, done.id())));
                });
        r.get("/api/bookings/:id/ticket", this::ticket);
    }

    private void hold(Request req, Response res) {
        HoldRequest in = req.body(HoldRequest.class);
        // TODO(jolt#61): declare these with nested validation rules.
        Map<String, String> errors = new LinkedHashMap<>();
        if (in == null || in.showId() == null) {
            errors.put("showId", "is required");
        }
        if (in == null || in.seats() == null || in.seats().isEmpty()) {
            errors.put("seats", "must list at least one seat");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        CreateHold.Result h = holds.execute(Principal.of(req).userId(), in.showId(), in.seats());
        res.status(201)
                .noStore()
                .json(
                        new Dtos.HoldDto(
                                h.hold().id(),
                                h.hold().showId(),
                                h.hold().seats().stream().map(SeatId::toString).toList(),
                                h.amount(),
                                h.currency(),
                                h.hold().expiresAt()));
    }

    private void book(Request req, Response res) {
        BookingRequest in = req.body(BookingRequest.class);
        if (in == null || in.holdId() == null || in.holdId().isBlank()) {
            throw ApiException.validation("holdId", "is required");
        }
        long userId = Principal.of(req).userId();
        Booking b = create.execute(userId, in.holdId());
        res.status(201).noStore().json(Dtos.BookingDto.of(queries.get(userId, false, b.id())));
    }

    private void list(Request req, Response res) {
        PageRequest page = PageRequest.of(req);
        var statuses =
                Query.list(req, "status", s -> BookingStatus.valueOf(s.toUpperCase(Locale.ROOT)));
        res.noStore()
                .json(
                        PageResult.of(
                                queries.list(
                                        Principal.of(req).userId(),
                                        statuses,
                                        page.page(),
                                        page.size()),
                                Dtos.BookingDto::of));
    }

    private void ticket(Request req, Response res) {
        BookingQueries.BookingView v = queries.ticket(Principal.of(req).userId(), req.param("id"));
        Booking b = v.booking();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("code", b.code());
        model.put("movie", v.show().movieTitle());
        model.put("cinema", v.show().cinemaName());
        model.put("screen", v.show().screenName());
        model.put("date", v.show().localDate().toString());
        model.put("time", v.show().localTime().toString());
        model.put("seats", b.seats().stream().map(s -> s.seat().toString()).toList());
        model.put("amount", Money.rupees(b.amount()));
        res.noStore().render("ticket", model);
    }
}
