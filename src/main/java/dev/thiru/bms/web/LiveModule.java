package dev.thiru.bms.web;

import dev.thiru.bms.application.CatalogQueries;
import dev.thiru.bms.kernel.catalog.ShowStatus;
import dev.thiru.bms.ports.LiveSeats;
import in.jolt.EventStream;
import in.jolt.JsonMapper;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.RouteModule;
import in.jolt.Router;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * {@code GET /api/shows/:id/events}: live seat updates over SSE (SPEC 7). Every connect, including
 * a reconnect that sends {@code Last-Event-ID}, starts with a full {@code snapshot}, so a browser
 * never needs a missed event; seat events then carry the hub's {@code id}.
 *
 * <p>The subscription is taken before the snapshot is read. An event racing the read is then
 * delivered after the snapshot; it may already be reflected there, which is harmless because each
 * seat event states the seats' new status rather than a change to apply.
 */
final class LiveModule implements RouteModule {

    static final Duration RETRY = Duration.ofSeconds(3);

    private final CatalogQueries catalog;
    private final LiveSeats live;
    private final Clock clock;
    private final Duration heartbeat;
    private final JsonMapper mapper;

    LiveModule(
            CatalogQueries catalog,
            LiveSeats live,
            Clock clock,
            Duration heartbeat,
            JsonMapper mapper) {
        this.catalog = catalog;
        this.live = live;
        this.clock = clock;
        this.heartbeat = heartbeat;
        this.mapper = mapper;
    }

    @Override
    public void register(Router r) {
        r.get("/api/shows/:id/events", this::stream);
    }

    private void stream(Request req, Response res) throws Exception {
        long showId = Query.id(req, "id");
        // Both reads throw NOT_FOUND before the stream opens, so a bad id gets a normal 404.
        CatalogQueries.ShowDetail show = catalog.show(showId);
        LiveSeats.Subscription sub =
                live.subscribe(showId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                503, "UNAVAILABLE", "Too many live streams"));
        try (sub) {
            String snapshot = json(Dtos.SeatMapDto.of(catalog.seatMap(showId)));
            EventStream sse = res.sse();
            sse.retry(RETRY);
            sse.event("snapshot", snapshot);
            if (show.show().status() != ShowStatus.CANCELLED) {
                pump(sse, sub, showId, show.show().startsAt());
            }
        } catch (IOException gone) {
            // The browser went away; nothing to answer.
        }
    }

    private void pump(EventStream sse, LiveSeats.Subscription sub, long showId, Instant startsAt)
            throws IOException, InterruptedException {
        while (sub.isOpen() && clock.instant().isBefore(startsAt)) {
            LiveSeats.Message m = sub.poll(heartbeat);
            if (m != null) {
                sse.event(m.id(), m.name(), m.data());
            } else if (!sub.isOpen() || cancelled(showId)) {
                return;
            } else {
                sse.event("heartbeat", json(Map.of("ts", clock.instant().toString())));
            }
        }
    }

    private boolean cancelled(long showId) {
        return catalog.show(showId).show().status() == ShowStatus.CANCELLED;
    }

    private String json(Object value) {
        return new String(mapper.write(value), StandardCharsets.UTF_8);
    }
}
