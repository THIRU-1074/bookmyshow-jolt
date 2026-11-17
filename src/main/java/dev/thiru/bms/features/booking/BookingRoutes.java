package dev.thiru.bms.features.booking;

import dev.thiru.bms.features.booking.BookingDtos.BookingDto;
import dev.thiru.bms.features.booking.BookingDtos.BookingRequest;
import dev.thiru.bms.features.booking.BookingDtos.CheckoutDto;
import dev.thiru.bms.features.booking.BookingDtos.HoldDto;
import dev.thiru.bms.features.booking.BookingDtos.HoldRequest;
import dev.thiru.bms.kernel.Money;
import dev.thiru.bms.kernel.SeatId;
import dev.thiru.bms.kernel.booking.Booking;
import dev.thiru.bms.kernel.booking.BookingPolicy;
import dev.thiru.bms.kernel.booking.BookingStatus;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.Ids;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.SeatEvents;
import dev.thiru.bms.web.ApiException;
import dev.thiru.bms.web.Paging;
import dev.thiru.bms.web.Principal;
import dev.thiru.bms.web.Query;
import in.jolt.Middleware;
import in.jolt.Page;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.RouteModule;
import in.jolt.Router;
import in.jolt.Validate;
import in.jolt.Validator;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds, bookings, checkout, cancel and ticket (SPEC 7). Every handler passes the caller's id from
 * the verified token to the use case, which applies the ownership rule; a stranger's id reads as
 * 404 so holds and bookings cannot be probed. An admin may read any booking but acts only on their
 * own. Seat events go out through {@code Tx.afterCommit}, never before the commit.
 */
public final class BookingRoutes implements RouteModule {

    /**
     * Shape only; the use case checks the seats against the layout ({@code seats[2]: unknown
     * seat}).
     */
    static final Validator<HoldRequest> HOLD =
            (in, r) -> {
                if (in.showId() == null) {
                    r.error("showId", "is required");
                }
                if (in.seats() == null || in.seats().isEmpty()) {
                    r.error("seats", "must list at least one seat");
                }
                // A null element is "seats[i]: is required".
                r.each("seats", in.seats(), (seat, sr) -> {});
            };

    private final CreateHold holds;
    private final CreateBooking create;
    private final BookingQueries queries;
    private final Checkout checkout;
    private final CancelBooking cancel;
    private final Middleware idempotent;
    private final Middleware holdLimit;

    /**
     * @param idempotent the pipeline's Idempotency-Key middleware
     * @param holdLimit the pipeline's per-user hold rate limit
     */
    public BookingRoutes(
            Db db,
            Clock clock,
            BookingPolicy policy,
            Ids ids,
            SeatEvents events,
            PaymentGateway gateway,
            Middleware idempotent,
            Middleware holdLimit) {
        this.holds = new CreateHold(db, events, ids, clock, policy);
        this.create = new CreateBooking(db, events, ids, clock);
        this.queries = new BookingQueries(db);
        this.checkout = new Checkout(db, gateway, events, ids, clock, policy);
        this.cancel = new CancelBooking(db, gateway, events, clock);
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
                                    BookingDto.of(
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
                                    new CheckoutDto(
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
                    res.noStore().json(BookingDto.of(queries.get(userId, false, done.id())));
                });
        r.get("/api/bookings/:id/ticket", this::ticket);
    }

    private void hold(Request req, Response res) {
        HoldRequest body = req.body(HoldRequest.class);
        HoldRequest in = body == null ? new HoldRequest(null, null) : body;
        Validate.check(r -> HOLD.validate(in, r));
        CreateHold.Result h = holds.execute(Principal.of(req).userId(), in.showId(), in.seats());
        res.status(201)
                .noStore()
                .json(
                        new HoldDto(
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
        res.status(201).noStore().json(BookingDto.of(queries.get(userId, false, b.id())));
    }

    private void list(Request req, Response res) {
        Page page = Paging.request(req);
        var statuses =
                Query.list(req, "status", s -> BookingStatus.valueOf(s.toUpperCase(Locale.ROOT)));
        res.noStore()
                .json(
                        Paging.result(
                                queries.list(
                                        Principal.of(req).userId(),
                                        statuses,
                                        page.page(),
                                        page.size()),
                                BookingDto::of));
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
        List<String> seats = b.seats().stream().map(s -> s.seat().toString()).toList();
        model.put("seats", seats);
        model.put("amount", Money.rupees(b.amount()));
        res.noStore().render("ticket", model);
    }
}
