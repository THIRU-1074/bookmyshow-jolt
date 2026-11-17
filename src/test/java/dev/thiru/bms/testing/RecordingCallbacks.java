package dev.thiru.bms.testing;

import dev.thiru.bms.ports.GatewayCallback;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link GatewayCallback} that records every event and answers {@link #status} (200 by default);
 * {@link #unreachable} makes a delivery throw, as a dead webhook endpoint would. Held to {@code
 * GatewayCallbackContract}.
 */
public final class RecordingCallbacks implements GatewayCallback {
    public final List<Event> sent = new CopyOnWriteArrayList<>();
    public volatile int status = 200;
    public volatile boolean unreachable;

    @Override
    public int send(Event event) throws IOException {
        if (unreachable) {
            throw new IOException("webhook endpoint unreachable");
        }
        sent.add(event);
        return status;
    }
}
