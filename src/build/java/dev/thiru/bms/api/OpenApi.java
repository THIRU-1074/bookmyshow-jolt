package dev.thiru.bms.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * {@code api/openapi.yaml} loaded as plain maps, with a validator for the subset of JSON Schema the
 * file uses: {@code $ref}, {@code type}, {@code properties}, {@code required}, {@code
 * additionalProperties}, {@code items}, {@code enum}, {@code pattern}, {@code format} (date-time,
 * date, int32), {@code nullable} and {@code oneOf}. Anything else in a schema fails loudly, so the
 * file cannot quietly use a keyword the check ignores.
 */
public final class OpenApi {

    public static final Path DEFAULT = Path.of("api/openapi.yaml");

    static final Set<String> KEYWORDS =
            Set.of(
                    "$ref",
                    "type",
                    "properties",
                    "required",
                    "additionalProperties",
                    "items",
                    "enum",
                    "pattern",
                    "format",
                    "nullable",
                    "oneOf",
                    "description",
                    "example",
                    "default",
                    "minimum",
                    "maximum",
                    "minItems");

    /** One operation: method, path template, and the raw operation object. */
    public record Operation(String method, String path, Map<String, Object> node) {
        public String id() {
            return (String) node.get("operationId");
        }

        public String access() {
            return (String) node.get("x-bms-access");
        }

        /** The key {@code "GET /api/movies/{id}"}. */
        public String key() {
            return method + " " + path;
        }
    }

    private final Map<String, Object> root;

    private OpenApi(Map<String, Object> root) {
        this.root = root;
    }

    public static OpenApi load() {
        return load(DEFAULT);
    }

    public static OpenApi load(Path file) {
        try {
            Object doc =
                    new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
            return new OpenApi(map(doc));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Map<String, Object> root() {
        return root;
    }

    public List<Operation> operations() {
        List<Operation> out = new ArrayList<>();
        map(root.get("paths"))
                .forEach(
                        (path, item) ->
                                map(item)
                                        .forEach(
                                                (method, op) -> {
                                                    if (!method.startsWith("x-")
                                                            && !method.equals("parameters")) {
                                                        out.add(
                                                                new Operation(
                                                                        method.toUpperCase(
                                                                                java.util.Locale
                                                                                        .ROOT),
                                                                        path,
                                                                        map(op)));
                                                    }
                                                }));
        return out;
    }

    /** The operation whose path template matches a concrete path such as /api/shows/42. */
    public Optional<Operation> find(String method, String concretePath) {
        String path = concretePath.contains("?") ? concretePath.split("\\?")[0] : concretePath;
        String[] actual = path.split("/", -1);
        for (Operation op : operations()) {
            if (!op.method().equals(method)) {
                continue;
            }
            String[] pattern = op.path().split("/", -1);
            if (pattern.length != actual.length) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < pattern.length && match; i++) {
                match =
                        pattern[i].startsWith("{")
                                ? !actual[i].isEmpty()
                                : pattern[i].equals(actual[i]);
            }
            if (match) {
                return Optional.of(op);
            }
        }
        return Optional.empty();
    }

    /**
     * The schema for a response, falling back to {@code default}; empty when the operation declares
     * that status without a body of that media type.
     */
    public Optional<Map<String, Object>> responseSchema(
            Operation op, int status, String mediaType) {
        Map<String, Object> responses = map(op.node().get("responses"));
        Object response = responses.get(Integer.toString(status));
        if (response == null) {
            response = responses.get("default");
        }
        if (response == null) {
            throw new AssertionError(op.key() + " does not declare status " + status);
        }
        Map<String, Object> r = resolve(map(response));
        Object content = r.get("content");
        if (content == null) {
            return Optional.empty();
        }
        Object media = map(content).get(mediaType);
        if (media == null) {
            throw new AssertionError(
                    op.key() + " " + status + " does not declare media type " + mediaType);
        }
        return Optional.of(map(map(media).get("schema")));
    }

    public Map<String, Object> schema(String name) {
        Object s = map(map(root.get("components")).get("schemas")).get(name);
        if (s == null) {
            throw new AssertionError("no schema " + name);
        }
        return map(s);
    }

    /** Follows a {@code $ref} chain to the node it names. */
    public Map<String, Object> resolve(Map<String, Object> node) {
        Map<String, Object> n = node;
        while (n.containsKey("$ref")) {
            String ref = (String) n.get("$ref");
            if (!ref.startsWith("#/")) {
                throw new AssertionError("only local refs: " + ref);
            }
            Object target = root;
            for (String part : ref.substring(2).split("/")) {
                target = map(target).get(part);
                if (target == null) {
                    throw new AssertionError("dangling ref " + ref);
                }
            }
            n = map(target);
        }
        return n;
    }

    /** Every problem with {@code value} against {@code schema}; empty when it conforms. */
    public List<String> validate(Map<String, Object> schema, Object value) {
        List<String> errors = new ArrayList<>();
        check(schema, value, "$", errors);
        return errors;
    }

    public List<String> validate(String schemaName, Object value) {
        return validate(schema(schemaName), value);
    }

    private void check(Map<String, Object> raw, Object value, String at, List<String> errors) {
        for (String k : raw.keySet()) {
            if (!KEYWORDS.contains(k)) {
                throw new AssertionError("unsupported schema keyword '" + k + "' at " + at);
            }
        }
        Map<String, Object> s = resolve(raw);
        if (s != raw) {
            check(s, value, at, errors);
            return;
        }
        if (value == null) {
            if (!Boolean.TRUE.equals(s.get("nullable"))) {
                errors.add(at + ": null is not allowed");
            }
            return;
        }
        if (s.containsKey("oneOf")) {
            int matches = 0;
            List<String> why = new ArrayList<>();
            for (Object alt : (List<?>) s.get("oneOf")) {
                List<String> e = new ArrayList<>();
                check(map(alt), value, at, e);
                if (e.isEmpty()) {
                    matches++;
                } else {
                    why.addAll(e);
                }
            }
            if (matches != 1) {
                errors.add(at + ": matches " + matches + " oneOf alternatives " + why);
            }
            return;
        }
        String type = (String) s.get("type");
        if (type != null && !typeMatches(type, value)) {
            errors.add(at + ": expected " + type + " but was " + describe(value));
            return;
        }
        if (s.containsKey("enum") && !((List<?>) s.get("enum")).contains(value)) {
            errors.add(at + ": " + value + " is not one of " + s.get("enum"));
        }
        if (value instanceof String str) {
            checkString(s, str, at, errors);
        }
        if ("int32".equals(s.get("format"))
                && value instanceof Number n
                && (n.longValue() > Integer.MAX_VALUE || n.longValue() < Integer.MIN_VALUE)) {
            errors.add(at + ": " + n + " does not fit int32");
        }
        if (value instanceof List<?> list) {
            Object items = s.get("items");
            for (int i = 0; i < list.size(); i++) {
                if (items != null) {
                    check(map(items), list.get(i), at + "[" + i + "]", errors);
                }
            }
        }
        if (value instanceof Map<?, ?> obj) {
            checkObject(s, obj, at, errors);
        }
    }

    private void checkString(Map<String, Object> s, String str, String at, List<String> errors) {
        Object pattern = s.get("pattern");
        if (pattern != null && !Pattern.compile((String) pattern).matcher(str).find()) {
            errors.add(at + ": '" + str + "' does not match " + pattern);
        }
        String format = (String) s.get("format");
        try {
            if ("date-time".equals(format)) {
                Instant.parse(str);
            } else if ("date".equals(format)) {
                LocalDate.parse(str);
            }
        } catch (DateTimeParseException e) {
            errors.add(at + ": '" + str + "' is not a " + format);
        }
    }

    private void checkObject(Map<String, Object> s, Map<?, ?> obj, String at, List<String> errors) {
        Map<String, Object> props =
                s.containsKey("properties") ? map(s.get("properties")) : Map.of();
        for (Object req : (List<?>) s.getOrDefault("required", List.of())) {
            if (!obj.containsKey(req)) {
                errors.add(at + ": missing required '" + req + "'");
            }
        }
        Object extra = s.get("additionalProperties");
        for (Map.Entry<?, ?> e : obj.entrySet()) {
            String key = String.valueOf(e.getKey());
            String child = at + "." + key;
            if (props.containsKey(key)) {
                check(map(props.get(key)), e.getValue(), child, errors);
            } else if (Boolean.FALSE.equals(extra)) {
                errors.add(at + ": unexpected property '" + key + "'");
            } else if (extra instanceof Map<?, ?>) {
                check(map(extra), e.getValue(), child, errors);
            }
        }
    }

    private static boolean typeMatches(String type, Object v) {
        return switch (type) {
            case "object" -> v instanceof Map;
            case "array" -> v instanceof List;
            case "string" -> v instanceof String;
            case "boolean" -> v instanceof Boolean;
            case "integer" ->
                    v instanceof Integer
                            || v instanceof Long
                            || v instanceof Short
                            || v instanceof java.math.BigInteger;
            case "number" -> v instanceof Number;
            default -> throw new AssertionError("unknown type " + type);
        };
    }

    private static String describe(Object v) {
        return v.getClass().getSimpleName() + " " + v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        if (!(o instanceof Map)) {
            throw new AssertionError("expected a mapping but was " + o);
        }
        return (Map<String, Object>) o;
    }

    /** Component schema names in file order. */
    public List<String> schemaNames() {
        return new ArrayList<>(
                new LinkedHashMap<>(map(map(root.get("components")).get("schemas"))).keySet());
    }
}
