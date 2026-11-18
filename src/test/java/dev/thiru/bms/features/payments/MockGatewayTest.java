package dev.thiru.bms.features.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.thiru.bms.kernel.DomainException;
import dev.thiru.bms.kernel.payment.PaymentStatus;
import dev.thiru.bms.ports.GatewayCallback;
import dev.thiru.bms.testing.SliceKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Copied from {@code AdminReportsAndQueriesTest.mockGatewayDeliversAnEventAndReturnsTheBooking}.
 */
class MockGatewayTest {

    final PaymentFlow flow = PaymentFlow.boot();
    final SliceKit kit = flow.kit;
    final String[] pending = flow.pending(kit.audience("bo@example.com"), "A6");

    @AfterEach
    void close() {
        kit.close();
    }

    private MockGateway gateway(GatewayCallback callback) {
        return new MockGateway(kit.db(), callback, kit.ids(), kit.clock());
    }

    @Test
    void mockGatewayDeliversAnEventAndReturnsTheBooking() {
        MockGateway gw = gateway(kit.callbacks());
        MockGateway.Pending p = gw.find(pending[1]);
        assertEquals(18000, p.amount());
        assertEquals("INR", p.currency());
        assertEquals(PaymentStatus.INITIATED, p.status());
        assertEquals(pending[0], p.bookingId());
        assertEquals(pending[0], gw.complete(pending[1], false));
        assertEquals(GatewayCallback.FAILED, kit.callbacks().sent.get(0).type());
        assertEquals(
                DomainException.Code.NOT_FOUND,
                assertThrows(DomainException.class, () -> gw.find("nope")).code());
        MockGateway rejecting = gateway(e -> 401);
        assertEquals(
                "webhook rejected with status 401",
                assertThrows(
                                IllegalStateException.class,
                                () -> rejecting.complete(pending[1], true))
                        .getMessage());
    }

    @Test
    void aLostDeliveryIsAnIllegalState() {
        kit.callbacks().unreachable = true;
        assertEquals(
                "webhook delivery failed",
                assertThrows(
                                IllegalStateException.class,
                                () -> gateway(kit.callbacks()).complete(pending[1], true))
                        .getMessage());
    }

    @Test
    void eachEventGetsAFreshId() {
        MockGateway gw = gateway(kit.callbacks());
        gw.complete(pending[1], true);
        gw.complete(pending[1], true);
        var sent = kit.callbacks().sent;
        assertEquals(GatewayCallback.SUCCEEDED, sent.get(0).type());
        assertEquals(2, sent.stream().map(GatewayCallback.Event::eventId).distinct().count());
    }
}
