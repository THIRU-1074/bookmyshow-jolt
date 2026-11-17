package dev.thiru.bms.features.booking;

import dev.thiru.bms.ports.Ids;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.SeatEvent;
import dev.thiru.bms.ports.SeatEvents;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

/**
 * Port fakes the booking slice's tests need beyond {@code testing}: seat events, ids and the
 * payment gateway. Each is held to its port contract in {@link BookingFakesContractTest}.
 */
final class BookingFakes {

    private BookingFakes() {}

    /** Records every published event; {@link #fail} makes every publish throw. */
    static final class Events implements SeatEvents {
        final List<SeatEvent> published = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        @Override
        public void publish(SeatEvent event) {
            if (fail) {
                throw new IllegalStateException("subscriber down");
            }
            published.add(event);
        }

        <T extends SeatEvent> List<T> of(Class<T> kind) {
            return published.stream().filter(kind::isInstance).map(kind::cast).toList();
        }
    }

    /** {@code id-1}, {@code id-2}, ... and a seeded random source for booking codes. */
    static final class SeqIds implements Ids {
        private final AtomicInteger next = new AtomicInteger();
        private final Random random = new Random(42);

        @Override
        public String newId() {
            return "id-" + next.incrementAndGet();
        }

        @Override
        public RandomGenerator random() {
            return random;
        }
    }

    /** As the mock gateway: refs {@code gw_<paymentId>}, URLs {@code http://pay.test/<id>}. */
    static final class Gateway implements PaymentGateway {
        final List<String> refunds = new CopyOnWriteArrayList<>();
        final AtomicInteger sessions = new AtomicInteger();
        volatile boolean failRefunds;

        @Override
        public CheckoutSession createSession(String paymentId, long amount, String currency) {
            sessions.incrementAndGet();
            return new CheckoutSession("gw_" + paymentId, "http://pay.test/" + paymentId);
        }

        @Override
        public void refund(String gatewayRef, long amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("refund amount must not be negative");
            }
            if (failRefunds) {
                throw new IllegalStateException("gateway down");
            }
            refunds.add(gatewayRef + ":" + amount);
        }
    }
}
