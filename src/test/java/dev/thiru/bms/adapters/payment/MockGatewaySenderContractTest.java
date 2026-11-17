package dev.thiru.bms.adapters.payment;

import com.sun.net.httpserver.HttpServer;
import dev.thiru.bms.contract.GatewayCallbackContract;
import dev.thiru.bms.ports.GatewayCallback;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;

/** The production sender over real HTTP, against a local stub webhook endpoint. */
class MockGatewaySenderContractTest extends GatewayCallbackContract {

    private static final Pattern EVENT_ID = Pattern.compile("\"eventId\":\"([^\"]*)\"");

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    @Override
    protected GatewayCallback answering(int status) {
        delivered.clear();
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/api/payments/webhook",
                    ex -> {
                        String body =
                                new String(
                                        ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        Matcher m = EVENT_ID.matcher(body);
                        if (m.find()) {
                            delivered.add(m.group(1));
                        }
                        ex.sendResponseHeaders(status, -1);
                        ex.close();
                    });
            server.start();
            servers.add(server);
            return sender(server.getAddress().getPort());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected GatewayCallback unreachable() {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sender(port); // nothing listens on the port once it is closed
    }

    @Override
    protected List<String> delivered() {
        return delivered;
    }

    private MockGatewaySender sender(int port) {
        URI url = URI.create("http://127.0.0.1:" + port + "/api/payments/webhook");
        return new MockGatewaySender(
                new MockGatewayClient(http, new WebhookSigner("s3cret"), Clock.systemUTC(), url));
    }
}
