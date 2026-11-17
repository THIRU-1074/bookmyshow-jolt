package dev.thiru.bms.web;

import dev.thiru.bms.application.MockGatewayPayments;
import dev.thiru.bms.application.ProcessPaymentWebhook;
import dev.thiru.bms.application.WebhookVerifier;
import in.jolt.HttpException;
import in.jolt.JsonMapper;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.RouteModule;
import in.jolt.Router;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The payment webhook and, when enabled, the mock gateway pages (SPEC 7, payments). The webhook
 * carries no JWT: the HMAC signature over the raw body is its credential, so a forged call is 401
 * before anything is parsed.
 */
final class PaymentModule implements RouteModule {

    record WebhookBody(
            String eventId,
            String type,
            String paymentId,
            String gatewayRef,
            Long amount,
            String currency,
            Instant occurredAt) {}

    record CompleteRequest(String outcome) {}

    private final ProcessPaymentWebhook webhook;
    private final WebhookVerifier verifier;
    private final MockGatewayPayments mock;
    private final JsonMapper mapper;

    /**
     * {@code mock} is null when {@code MOCK_GATEWAY} is off; its routes are then not registered.
     */
    PaymentModule(
            ProcessPaymentWebhook webhook,
            WebhookVerifier verifier,
            MockGatewayPayments mock,
            JsonMapper mapper) {
        this.webhook = webhook;
        this.verifier = verifier;
        this.mock = mock;
        this.mapper = mapper;
    }

    @Override
    public void register(Router r) {
        r.post("/api/payments/webhook", this::webhook);
        if (mock != null) {
            r.get("/mock-gateway/:id", this::page);
            r.post("/mock-gateway/:id/complete", this::complete);
        }
    }

    private void webhook(Request req, Response res) {
        String raw = new String(req.body(), StandardCharsets.UTF_8);
        if (!verifier.verify(req.header("Bms-Timestamp"), req.header("Bms-Signature"), raw)) {
            throw ApiException.unauthenticated("Invalid webhook signature");
        }
        WebhookBody in;
        try {
            in = mapper.read(req.body(), WebhookBody.class);
        } catch (RuntimeException e) {
            throw ApiException.validation("body", "is not a valid webhook event");
        }
        ProcessPaymentWebhook.Result result = webhook.execute(toEvent(in));
        res.noStore();
        switch (result.kind()) {
            case DUPLICATE -> res.json(Map.of("duplicate", true));
            case AMOUNT_MISMATCH ->
                    throw ApiException.validation(
                            "amount", "does not match the payment (payment marked FAILED)");
            case PROCESSED -> res.json(Map.of("status", result.bookingStatus().name()));
        }
    }

    private static ProcessPaymentWebhook.Event toEvent(WebhookBody in) {
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
        ProcessPaymentWebhook.Type type = null;
        if ("payment.succeeded".equals(in.type())) {
            type = ProcessPaymentWebhook.Type.PAYMENT_SUCCEEDED;
        } else if ("payment.failed".equals(in.type())) {
            type = ProcessPaymentWebhook.Type.PAYMENT_FAILED;
        } else {
            errors.put("type", "must be payment.succeeded or payment.failed");
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return new ProcessPaymentWebhook.Event(
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
        MockGatewayPayments.Pending p = mock.find(req.param("id"));
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
        if (type != null
                && type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
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
