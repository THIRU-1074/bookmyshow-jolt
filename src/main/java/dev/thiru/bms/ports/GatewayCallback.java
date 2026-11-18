package dev.thiru.bms.ports;

import java.time.Instant;

/**
 * The mock gateway's way back into the app: delivers a payment result to the webhook endpoint, as a
 * real gateway would (SPEC 7). Only the mock gateway pages use it.
 */
@FunctionalInterface
public interface GatewayCallback {

    String SUCCEEDED = "payment.succeeded";
    String FAILED = "payment.failed";

    /**
     * A gateway event as the webhook receives it; amount in paise.
     *
     * @param type the wire string, {@link #SUCCEEDED} or {@link #FAILED}
     */
    record Event(
            String eventId,
            String type,
            String paymentId,
            String gatewayRef,
            long amount,
            String currency,
            Instant occurredAt) {}

    /**
     * Delivers {@code event} and returns the receiver's HTTP status, whatever it is. Throws when
     * the event could not be delivered at all.
     */
    int send(Event event) throws Exception;
}
