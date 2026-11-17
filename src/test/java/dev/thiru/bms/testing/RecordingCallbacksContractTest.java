package dev.thiru.bms.testing;

import dev.thiru.bms.contract.GatewayCallbackContract;
import dev.thiru.bms.ports.GatewayCallback;
import java.util.List;

class RecordingCallbacksContractTest extends GatewayCallbackContract {

    private RecordingCallbacks last;

    @Override
    protected GatewayCallback answering(int status) {
        last = new RecordingCallbacks();
        last.status = status;
        return last;
    }

    @Override
    protected GatewayCallback unreachable() {
        RecordingCallbacks c = new RecordingCallbacks();
        c.unreachable = true;
        return c;
    }

    @Override
    protected List<String> delivered() {
        return last.sent.stream().map(GatewayCallback.Event::eventId).toList();
    }
}
