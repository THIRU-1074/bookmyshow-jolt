package dev.thiru.bms.adapters.live;

import dev.thiru.bms.contract.SeatEventPublisherContract;
import dev.thiru.bms.domain.port.SeatEventPublisher;

/** The production live hub as the use cases' publisher, held to the publisher contract. */
class LiveSeatHubPublisherContractTest extends SeatEventPublisherContract {

    @Override
    protected SeatEventPublisher newPublisher() {
        // Queue of 1 with a subscriber that never reads: the slow-consumer path must not throw.
        LiveSeatHub hub = new LiveSeatHub(10, 1);
        hub.subscribe(0);
        return hub;
    }
}
