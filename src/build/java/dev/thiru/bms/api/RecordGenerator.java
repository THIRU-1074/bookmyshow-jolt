package dev.thiru.bms.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Generates one Java record per object schema in {@code api/openapi.yaml}, so the web records can
 * later be replaced by types that cannot drift from the schema. Mainstream generators emit mutable
 * beans with annotation dependencies; this emits plain records with only {@code java.*} types,
 * shaped like the hand-written ones in {@code web.Dtos}:
 *
 * <ul>
 *   <li>closed response schemas ({@code additionalProperties: false}) use primitives for required
 *       numbers and booleans; open request schemas box them so a missing field reads as null;
 *   <li>string enums become {@code String} (values such as {@code 2D} are not identifiers);
 *   <li>{@code date-time} is {@code Instant}, {@code date} is {@code LocalDate}, the {@code
 *       LocalTime} schema is {@code LocalTime};
 *   <li>{@code oneOf} schemas are skipped: Java has no tagged union a record could express.
 * </ul>
 *
 * <p>Lives in the build-time source set ({@code src/build/java}) and runs in every build at {@code
 * generate-sources}; output goes to {@code target/generated-sources/openapi}, a main source root.
 * Arguments: schema file, output directory, package.
 */
public final class RecordGenerator {

    private final OpenApi api;
    private final String pkg;

    public RecordGenerator(OpenApi api, String pkg) {
        this.api = api;
        this.pkg = pkg;
    }

    public static void main(String[] args) {
        Path schema = Path.of(args.length > 0 ? args[0] : "api/openapi.yaml");
        Path out = Path.of(args.length > 1 ? args[1] : "target/generated-sources/openapi");
        String pkg = args.length > 2 ? args[2] : "dev.thiru.bms.api.generated";
        List<Path> files = new RecordGenerator(OpenApi.load(schema), pkg).writeTo(out);
        System.out.println("Generated " + files.size() + " records into " + out);
    }

    /**
     * Writes every record under {@code root} in package directories and returns the files. A file
     * whose content is unchanged is left alone, so the compiler's stale check does not recompile
     * the module on every build; a record whose schema is gone is deleted.
     */
    public List<Path> writeTo(Path root) {
        Path dir = root.resolve(pkg.replace('.', '/'));
        List<Path> written = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, String> e : generate().entrySet()) {
                Path file = dir.resolve(e.getKey() + ".java");
                if (!Files.exists(file) || !Files.readString(file).equals(e.getValue())) {
                    Files.writeString(file, e.getValue());
                }
                written.add(file);
            }
            try (var stale = Files.list(dir)) {
                for (Path file : stale.filter(f -> !written.contains(f)).toList()) {
                    Files.delete(file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return written;
    }

    /** Record name to source, in schema order. */
    public Map<String, String> generate() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String name : api.schemaNames()) {
            Map<String, Object> s = api.schema(name);
            if ("object".equals(s.get("type")) && s.containsKey("properties")) {
                out.put(name, record(name, s));
            }
        }
        return out;
    }

    private String record(String name, Map<String, Object> s) {
        boolean closed = Boolean.FALSE.equals(s.get("additionalProperties"));
        Set<String> required = new TreeSet<>();
        for (Object r : (List<?>) s.getOrDefault("required", List.of())) {
            required.add((String) r);
        }
        Set<String> imports = new TreeSet<>();
        List<String> components = new ArrayList<>();
        OpenApi.map(s.get("properties"))
                .forEach(
                        (prop, schema) -> {
                            boolean primitive = closed && required.contains(prop);
                            String type = javaType(OpenApi.map(schema), primitive, imports);
                            components.add(type + " " + prop);
                        });
        StringBuilder src = new StringBuilder();
        src.append("// Generated from api/openapi.yaml by RecordGenerator. Do not edit.\n");
        src.append("package ").append(pkg).append(";\n\n");
        for (String i : imports) {
            src.append("import ").append(i).append(";\n");
        }
        if (!imports.isEmpty()) {
            src.append('\n');
        }
        Object doc = s.get("description");
        if (doc != null) {
            src.append("/** ").append(doc.toString().strip().replace("*/", "* /")).append(" */\n");
        }
        src.append("public record ").append(name).append("(\n");
        for (int i = 0; i < components.size(); i++) {
            src.append("        ").append(components.get(i));
            src.append(i < components.size() - 1 ? ",\n" : ") {}\n");
        }
        return src.toString();
    }

    private String javaType(Map<String, Object> schema, boolean primitive, Set<String> imports) {
        if (schema.containsKey("$ref")) {
            String ref = (String) schema.get("$ref");
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            if (name.equals("LocalTime")) {
                imports.add("java.time.LocalTime");
                return "LocalTime";
            }
            Map<String, Object> target = api.resolve(schema);
            if (!"object".equals(target.get("type"))) {
                return javaType(target, primitive, imports);
            }
            return name;
        }
        String type = (String) schema.get("type");
        String format = (String) schema.get("format");
        return switch (type) {
            case "string" -> {
                if ("date-time".equals(format)) {
                    imports.add("java.time.Instant");
                    yield "Instant";
                }
                if ("date".equals(format)) {
                    imports.add("java.time.LocalDate");
                    yield "LocalDate";
                }
                yield "String";
            }
            case "integer" -> {
                boolean wide = !"int32".equals(format);
                yield primitive ? (wide ? "long" : "int") : (wide ? "Long" : "Integer");
            }
            case "number" -> primitive ? "double" : "Double";
            case "boolean" -> primitive ? "boolean" : "Boolean";
            case "array" -> {
                imports.add("java.util.List");
                yield "List<" + javaType(OpenApi.map(schema.get("items")), false, imports) + ">";
            }
            case "object" -> {
                Object values = schema.get("additionalProperties");
                if (values instanceof Map<?, ?> v) {
                    imports.add("java.util.Map");
                    yield "Map<String, " + javaType(OpenApi.map(v), false, imports) + ">";
                }
                imports.add("java.util.Map");
                yield "Map<String, Object>";
            }
            default -> throw new IllegalArgumentException("unsupported type " + type);
        };
    }
}
