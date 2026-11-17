package dev.thiru.bms.application.fakes;

import dev.thiru.bms.contract.SeatEventPublisherContract;
import dev.thiru.bms.ports.SeatEvents;

/** FakeWorld's recording publisher, held to the contract LiveSeatHub meets. */
class FakeWorldSeatEventPublisherContractTest extends SeatEventPublisherContract {

    @Override
    protected SeatEvents newPublisher() {
        return new FakeWorld();
    }
}
