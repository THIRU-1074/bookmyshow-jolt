package dev.thiru.bms.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.thiru.bms.ports.GatewayCallback;
import dev.thiru.bms.ports.GatewayCallback.Event;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The mock gateway's callback as the mock gateway pages use it: the receiver's status comes back as
 * is, so the caller decides what a non-2xx means, and an event that never arrives throws.
 */
public abstract class GatewayCallbackContract {

    protected static final Event EVENT =
            new Event(
                    "evt-1",
                    GatewayCallback.SUCCEEDED,
                    "pay-1",
                    "gw_pay-1",
                    45000,
                    "INR",
                    Instant.parse("2026-11-20T13:00:00Z"));

    /** A callback whose receiver answers every delivery with {@code status}. */
    protected abstract GatewayCallback answering(int status);

    /** A callback whose deliveries cannot reach the receiver. */
    protected abstract GatewayCallback unreachable();

    /** The event ids the receiver behind the last {@link #answering} callback got, in order. */
    protected abstract List<String> delivered();

    @Test
    protected void aSuccessStatusComesBackAsIsAndTheEventArrives() throws Exception {
        assertEquals(200, answering(200).send(EVENT));
        assertEquals(List.of("evt-1"), delivered());
        assertEquals(204, answering(204).send(EVENT));
    }

    @Test
    protected void aRejectionIsAStatusNotAnException() throws Exception {
        assertEquals(401, answering(401).send(EVENT));
        assertEquals(500, answering(500).send(EVENT));
    }

    @Test
    protected void aFailedDeliveryThrows() {
        assertThrows(Exception.class, () -> unreachable().send(EVENT));
    }

    @Test
    protected void bothEventTypesAreDelivered() throws Exception {
        GatewayCallback callback = answering(200);
        callback.send(EVENT);
        callback.send(
                new Event(
                        "evt-2",
                        GatewayCallback.FAILED,
                        "pay-1",
                        "gw_pay-1",
                        45000,
                        "INR",
                        EVENT.occurredAt()));
        assertEquals(List.of("evt-1", "evt-2"), delivered());
    }
}
