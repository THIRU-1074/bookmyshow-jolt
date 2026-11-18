package dev.thiru.bms.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code api/openapi.yaml} in step with {@code docs/RBAC.md}, which {@code RbacMatrixTest}
 * already ties to the registered routes and the Permissions table: one operation per route, with
 * the same access level, and no dangling references.
 */
class ApiCoverageTest {

    private static final Path RBAC = Path.of("docs/RBAC.md");
    private final OpenApi api = OpenApi.load();

    /** {@code "GET /api/movies/{id}" -> "PUBLIC"} from the RBAC matrix rows. */
    static Map<String, String> documentedRoutes() throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (String line : Files.readAllLines(RBAC)) {
            String l = line.trim();
            if (!l.matches("\\| (GET|POST|PUT|PATCH|DELETE) \\|.*")) {
                continue;
            }
            String[] cells = l.substring(1, l.length() - 1).replace("`", "").split("\\|");
            String path = cells[1].trim().replaceAll(":(\\w+)", "{$1}");
            out.put(cells[0].trim() + " " + path, cells[2].trim());
        }
        return out;
    }

    @Test
    void everyRouteInTheRbacMatrixIsAnOperationWithTheSameAccess() throws IOException {
        Map<String, String> schema = new TreeMap<>();
        api.operations().forEach(op -> schema.put(op.key(), op.access()));
        assertEquals(documentedRoutes(), schema);
    }

    @Test
    void operationIdsAreUniqueAndPresent() {
        List<String> ids = api.operations().stream().map(OpenApi.Operation::id).toList();
        assertTrue(ids.stream().allMatch(id -> id != null && !id.isBlank()), ids.toString());
        assertEquals(ids.size(), new TreeSet<>(ids).size(), "duplicate operationId");
    }

    @Test
    void everyReferenceResolves() {
        List<String> refs = new ArrayList<>();
        collectRefs(api.root(), refs);
        assertTrue(refs.size() > 100, "suspiciously few refs: " + refs.size());
        for (String ref : refs) {
            api.resolve(Map.of("$ref", ref));
        }
    }

    @Test
    void everyComponentSchemaUsesOnlySupportedKeywords() {
        for (String name : api.schemaNames()) {
            // Validating null walks the top keywords; a full walk happens in ApiSchemaTest.
            api.validate(name, null);
        }
    }

    @Test
    void theValidatorRejectsWrongShapes() {
        assertEquals(List.of(), api.validate("City", city("bengaluru")));
        Map<String, Object> extra = new java.util.LinkedHashMap<>(city("x"));
        extra.put("population", 1);
        assertEquals(List.of("$: unexpected property 'population'"), api.validate("City", extra));
        assertEquals(
                List.of("$: missing required 'timeZone'"),
                api.validate("City", Map.of("id", "x", "name", "X")));
        assertEquals(
                List.of("$.status: LOST is not one of [UP, DOWN]"),
                api.validate("Health", Map.of("status", "LOST", "db", "UP")));
        assertEquals(
                List.of("$.ts: 'yesterday' is not a date-time"),
                api.validate("HeartbeatEvent", Map.of("ts", "yesterday")));
        Map<String, Object> nullId = new java.util.LinkedHashMap<>();
        nullId.put("id", null);
        nullId.put("name", "n");
        assertEquals(List.of("$.id: null is not allowed"), api.validate("NamedRef", nullId));
    }

    private static Map<String, Object> city(String id) {
        return Map.of("id", id, "name", "Name", "timeZone", "Asia/Kolkata");
    }

    private static void collectRefs(Object node, List<String> out) {
        if (node instanceof Map<?, ?> m) {
            m.forEach(
                    (k, v) -> {
                        if ("$ref".equals(k)) {
                            out.add((String) v);
                        } else {
                            collectRefs(v, out);
                        }
                    });
        } else if (node instanceof List<?> l) {
            l.forEach(v -> collectRefs(v, out));
        }
    }
}
