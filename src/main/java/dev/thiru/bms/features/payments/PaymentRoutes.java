package dev.thiru.bms.features.payments;

import dev.thiru.bms.features.payments.PaymentDtos.CompleteRequest;
import dev.thiru.bms.features.payments.PaymentDtos.WebhookBody;
import dev.thiru.bms.kernel.Money;
import dev.thiru.bms.ports.Db;
import dev.thiru.bms.ports.GatewayCallback;
import dev.thiru.bms.ports.Ids;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.SeatEvents;
import dev.thiru.bms.web.ApiException;
import in.jolt.HttpException;
import in.jolt.JsonMapper;
import in.jolt.Middleware;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.RouteModule;
import in.jolt.Router;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The payment webhook and, when enabled, the mock gateway pages (SPEC 7, payments). The webhook
 * carries no JWT: the HMAC signature over the raw body is its credential, checked by the {@code
 * signature} middleware passed in, so a forged or stale call is 401 before anything is parsed.
 */
public final class PaymentRoutes implements RouteModule {

    private final ProcessWebhook webhook;
    private final Middleware signature;
    private final MockGateway mock;
    private final JsonMapper mapper;

    /**
     * {@code mockSender} is null when {@code MOCK_GATEWAY} is off; the mock routes are then not
     * registered.
     */
    public PaymentRoutes(
            Db db,
            PaymentGateway gateway,
            SeatEvents events,
            Ids ids,
            Clock clock,
            Middleware signature,
            GatewayCallback mockSender,
            JsonMapper mapper) {
        this.webhook = new ProcessWebhook(db, gateway, events, clock);
        this.signature = signature;
        this.mock = mockSender == null ? null : new MockGateway(db, mockSender, ids, clock);
        this.mapper = mapper;
    }

    @Override
    public void register(Router r) {
        r.post("/api/payments/webhook", signature, this::webhook);
        if (mock != null) {
            r.get("/mock-gateway/:id", this::page);
            r.post("/mock-gateway/:id/complete", this::complete);
        }
    }

    private void webhook(Request req, Response res) {
        WebhookBody in;
        try {
            in = mapper.read(req.body(), WebhookBody.class);
        } catch (RuntimeException e) {
            throw ApiException.validation("body", "is not a valid webhook event");
        }
        ProcessWebhook.Result result = webhook.execute(toEvent(in));
        res.noStore();
        switch (result.kind()) {
            case DUPLICATE -> res.json(Map.of("duplicate", true));
            case AMOUNT_MISMATCH ->
                    throw ApiException.validation(
                            "amount", "does not match the payment (payment marked FAILED)");
            case PROCESSED -> res.json(Map.of("status", result.bookingStatus().name()));
        }
    }

    private static ProcessWebhook.Event toEvent(WebhookBody in) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (in == null) {
            throw ApiException.validation("body", "is required");
        }
        require(errors, "eventId", in.eventId());
        require(errors, "paymentId", in.paymentId());
        require(errors, "currency", in.currency());
        if (in.amount() == null) {
            errors.put("amount", "is required");
        }
        if (in.occurredAt() == null) {
            errors.put("occurredAt", "is required");
        }
        ProcessWebhook.Type type = null;
        if (GatewayCallback.SUCCEEDED.equals(in.type())) {
            type = ProcessWebhook.Type.PAYMENT_SUCCEEDED;
        } else if (GatewayCallback.FAILED.equals(in.type())) {
            type = ProcessWebhook.Type.PAYMENT_FAILED;
        } else {
            errors.put("type", "must be payment.succeeded or payment.failed");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return new ProcessWebhook.Event(
                in.eventId(),
                type,
                in.paymentId(),
                in.gatewayRef(),
                in.amount(),
                in.currency(),
                in.occurredAt());
    }

    private static void require(Map<String, String> errors, String field, String value) {
        if (value == null || value.isBlank()) {
            errors.put(field, "is required");
        }
    }

    private void page(Request req, Response res) {
        MockGateway.Pending p = mock.find(req.param("id"));
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("paymentId", p.paymentId());
        model.put("amount", Money.rupees(p.amount()));
        model.put("currency", p.currency());
        model.put("status", p.status().name());
        model.put("action", "/mock-gateway/" + p.paymentId() + "/complete");
        res.noStore().render("mock-gateway", model);
    }

    private void complete(Request req, Response res) {
        String outcome = outcome(req);
        boolean success;
        if ("SUCCESS".equals(outcome)) {
            success = true;
        } else if ("FAILURE".equals(outcome)) {
            success = false;
        } else {
            throw ApiException.validation("outcome", "must be SUCCESS or FAILURE");
        }
        String bookingId = mock.complete(req.param("id"), success);
        res.redirect(303, "/bookings/" + bookingId);
    }

    /** The page posts a form; scripts and tests may post JSON. */
    private static String outcome(Request req) {
        String type = req.header("Content-Type");
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            CompleteRequest in = req.body(CompleteRequest.class);
            return in == null ? null : in.outcome();
        }
        try {
            return req.form().get("outcome");
        } catch (HttpException e) {
            return null;
        }
    }
}
