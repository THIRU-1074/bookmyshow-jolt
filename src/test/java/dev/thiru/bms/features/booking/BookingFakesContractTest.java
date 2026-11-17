package dev.thiru.bms.features.booking;

import dev.thiru.bms.contract.IdGeneratorContract;
import dev.thiru.bms.contract.PaymentGatewayContract;
import dev.thiru.bms.contract.SeatEventPublisherContract;
import dev.thiru.bms.ports.Ids;
import dev.thiru.bms.ports.PaymentGateway;
import dev.thiru.bms.ports.SeatEvents;
import org.junit.jupiter.api.Nested;

/** The slice's fakes meet the contracts their production adapters meet. */
class BookingFakesContractTest {

    @Nested
    class Gateway extends PaymentGatewayContract {
        @Override
        protected PaymentGateway newGateway() {
            return new BookingFakes.Gateway();
        }
    }

    @Nested
    class Ids_ extends IdGeneratorContract {
        @Override
        protected Ids newIds() {
            return new BookingFakes.SeqIds();
        }
    }

    @Nested
    class Events extends SeatEventPublisherContract {
        @Override
        protected SeatEvents newPublisher() {
            return new BookingFakes.Events();
        }
    }
}
