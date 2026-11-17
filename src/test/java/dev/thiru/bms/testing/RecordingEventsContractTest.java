package dev.thiru.bms.testing;

import dev.thiru.bms.contract.SeatEventPublisherContract;
import dev.thiru.bms.ports.SeatEvents;

class RecordingEventsContractTest extends SeatEventPublisherContract {

    @Override
    protected SeatEvents newPublisher() {
        return new RecordingEvents();
    }
}
