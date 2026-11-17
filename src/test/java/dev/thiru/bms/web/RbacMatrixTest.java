package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.Route;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Keeps the three views of RBAC in step: the routes the app registers, the {@link Permissions}
 * table the gate enforces, and the matrix in {@code docs/RBAC.md}. Adding a route without a rule,
 * or changing a rule without the doc, fails the build.
 */
class RbacMatrixTest {

    private static final Path DOC = Path.of("docs/RBAC.md");

    private static String key(String method, String pattern) {
        return method + " " + pattern;
    }

    /** The role columns of the doc (Anonymous, AUDIENCE, THEATRE_OWNER, ADMIN). */
    static List<String> cells(Permissions.Access access) {
        return switch (access) {
            case PUBLIC -> List.of("yes", "yes", "yes", "yes");
            case SELF_AUTHENTICATED -> List.of("proof", "proof", "proof", "proof");
            case SIGNED_IN -> List.of("no", "yes", "yes", "yes");
            case OWNER -> List.of("no", "own", "own", "own");
            case OWNER_OR_ADMIN -> List.of("no", "own", "own", "any");
            case THEATRE_OWNER -> List.of("no", "no", "own", "any");
            case ADMIN -> List.of("no", "no", "no", "yes");
        };
    }

    private static String row(Permissions.Rule r) {
        return String.join(
                " | ",
                r.method(),
                r.pattern(),
                r.access().name(),
                String.join(" | ", cells(r.access())));
    }

    @Test
    void everyRegisteredRouteHasExactlyOneRuleAndEveryRuleARoute() {
        WebKit kit = new WebKit();
        Set<String> registered =
                kit.app.routes().stream()
                        .filter(
                                r ->
                                        r.path().startsWith("/api/")
                                                || r.path().startsWith("/mock-gateway/"))
                        .map((Route r) -> key(r.method(), r.path()))
                        .collect(Collectors.toCollection(TreeSet::new));
        Set<String> table =
                Permissions.TABLE.stream()
                        .map(r -> key(r.method(), r.pattern()))
                        .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(table.size(), Permissions.TABLE.size(), "duplicate rule");
        assertEquals(table, registered);
    }

    @Test
    void theDocumentedMatrixMatchesTheEnforcedTable() throws IOException {
        List<String> documented =
                Files.readAllLines(DOC).stream()
                        .map(String::trim)
                        .filter(l -> l.matches("\\| (GET|POST|PUT|PATCH|DELETE) \\|.*"))
                        .map(
                                l ->
                                        l.substring(1, l.length() - 1)
                                                .replace("`", "")
                                                .trim()
                                                .replaceAll("\\s*\\|\\s*", " | "))
                        .toList();
        List<String> enforced = Permissions.TABLE.stream().map(RbacMatrixTest::row).toList();
        assertEquals(String.join("\n", enforced), String.join("\n", documented));
    }

    @Test
    void everyRuleMatchesItsOwnPattern() {
        for (Permissions.Rule r : Permissions.TABLE) {
            String path = r.pattern().replaceAll(":\\w+", "42");
            assertEquals(r, Permissions.match(r.method(), path).orElseThrow(), path);
            assertEquals(r, Permissions.forPattern(r.method(), r.pattern()).orElseThrow());
        }
        assertEquals(
                Permissions.Access.PUBLIC,
                Permissions.match("HEAD", "/api/cities").orElseThrow().access());
        assertEquals(true, Permissions.match("GET", "/api/movies/").isPresent());
        assertEquals(false, Permissions.match("GET", "/api/movies//shows").isPresent());
    }
}
