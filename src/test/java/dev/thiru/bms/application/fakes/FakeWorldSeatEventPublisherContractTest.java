package dev.thiru.bms.application.fakes;

import dev.thiru.bms.contract.SeatEventPublisherContract;
import dev.thiru.bms.domain.port.SeatEventPublisher;

/** FakeWorld's recording publisher, held to the contract LiveSeatHub meets. */
class FakeWorldSeatEventPublisherContractTest extends SeatEventPublisherContract {

    @Override
    protected SeatEventPublisher newPublisher() {
        return new FakeWorld();
    }
}
