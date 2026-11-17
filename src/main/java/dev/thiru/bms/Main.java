package dev.thiru.bms;

import in.jolt.App;
import in.jolt.Jolt;
import in.jolt.JoltConfig;
import in.jolt.Server;
import in.jolt.Static;
import java.nio.file.Path;
import java.util.Map;

/** Composition root: the only class that names every concrete type. */
public final class Main {

    static final int DEFAULT_PORT = 8080;
    static final String DEFAULT_STATIC_DIR = "src/main/resources/static";

    private Main() {}

    /** Builds the application on {@code config}; tests call this without a socket. */
    public static App app(JoltConfig config) {
        App app = Jolt.create(config.problemJson(true));
        app.get("/api/health", (req, res) -> res.json(Map.of("status", "UP")));
        // API routes are registered first, so only unmatched non-API paths reach the SPA fallback.
        // TODO(jolt#68): serve from the classpath once Jolt supports it; until then STATIC_DIR.
        Static files =
                Static.serve("/", staticDir(System.getenv("STATIC_DIR"))).spaFallback("index.html");
        app.use(
                (req, res, next) -> {
                    // Jolt's fallback answers any browser navigation (Accept: text/html) with
                    // index.html, so a mistyped /api URL would hide the problem+json 404.
                    if (isBackendPath(req.path())) {
                        next.run();
                    } else {
                        files.handle(req, res, next);
                    }
                });
        return app;
    }

    static boolean isBackendPath(String path) {
        return path.equals("/api") || path.startsWith("/api/") || path.startsWith("/_jolt/");
    }

    static Path staticDir(String value) {
        return Path.of(value == null || value.isBlank() ? DEFAULT_STATIC_DIR : value.trim());
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
