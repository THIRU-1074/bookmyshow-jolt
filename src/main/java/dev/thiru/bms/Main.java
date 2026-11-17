package dev.thiru.bms;

import in.jolt.App;
import in.jolt.Jolt;
import in.jolt.JoltConfig;
import in.jolt.Server;
import java.util.Map;

/** Composition root: the only class that names every concrete type. */
public final class Main {

    static final int DEFAULT_PORT = 8080;

    private Main() {}

    /** Builds the application on {@code config}; tests call this without a socket. */
    public static App app(JoltConfig config) {
        App app = Jolt.create(config.problemJson(true));
        app.get("/api/health", (req, res) -> res.json(Map.of("status", "UP")));
        return app;
    }

    static int port(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        int port = Integer.parseInt(value.trim());
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("PORT out of range: " + value);
        }
        return port;
    }

    public static void main(String[] args) {
        Server server =
                app(JoltConfig.fromEnv()).listen(port(System.getenv("PORT"))).closeOnShutdown();
        System.getLogger("bms").log(System.Logger.Level.INFO, "Listening on port " + server.port());
    }
}
