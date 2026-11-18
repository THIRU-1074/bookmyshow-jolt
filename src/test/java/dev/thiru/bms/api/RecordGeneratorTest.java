package dev.thiru.bms.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The slices serialise the records generated from {@code api/openapi.yaml} ({@code
 * dev.thiru.bms.api.generated}, built at {@code generate-sources}). This holds three things:
 *
 * <ul>
 *   <li>the generated records on the class path are the generator's output for the current schema;
 *   <li>every object schema is served by its generated record, or is listed with what serves it
 *       instead;
 *   <li>no slice declares a record that duplicates a generated one (same components, in the same
 *       order, with the same types, ignoring a {@code Dto} suffix), unless it is allowed below with
 *       a reason. A copy would drift from the schema without anything noticing.
 * </ul>
 */
class RecordGeneratorTest {

    static final String GENERATED = "dev.thiru.bms.api.generated";

    private static final String JOLT_PAGE = "in.jolt.Page$Result";

    /**
     * Object schemas serialised from a record that is not generated: schema to that record. The
     * paging envelope is Jolt's own generic record since v0.4.1 ({@code web.Paging} builds it), so
     * its components are compared by name only.
     */
    static final Map<String, String> NOT_GENERATED =
            Map.of(
                    "MoviePage", JOLT_PAGE,
                    "CinemaPage", JOLT_PAGE,
                    "BookingPage", JOLT_PAGE,
                    "ShowReportPage", JOLT_PAGE,
                    "AccountPage", JOLT_PAGE);

    /** Object schemas with no record at all: built as maps or rendered by hand. */
    static final Set<String> NO_RECORD =
            Set.of(
                    "Problem",
                    "Health",
                    "SeatHeldEvent",
                    "SeatReleasedEvent",
                    "SeatBookedEvent",
                    "HeartbeatEvent");

    /**
     * Slice records allowed to duplicate a generated record, binary name to reason. Internal rows
     * that happen to share a wire shape belong here; a wire type does not, it uses the generated
     * record.
     */
    static final Map<String, String> ALLOWED_DUPLICATES =
            Map.of(
                    "dev.thiru.bms.features.admin.AdminReports$CategoryStat",
                    "the report query's row, a use-case value like its sibling ShowReport (whose"
                            + " status is an enum); AdminDtos maps it to CategoryReport",
                    "dev.thiru.bms.features.booking.Checkout$Result",
                    "the checkout use case's result, so the use case does not depend on the wire"
                            + " schema; BookingRoutes maps it to the generated Checkout");

    private final OpenApi api = OpenApi.load();
    private final Map<String, String> generated = new RecordGenerator(api, GENERATED).generate();

    @Test
    void theGeneratedRecordsOnTheClassPathMatchTheCurrentSchema() throws Exception {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String> e : generated.entrySet()) {
            List<String> want = components(e.getValue());
            List<String> have = signature(Class.forName(GENERATED + "." + e.getKey()), false);
            if (!want.equals(have)) {
                stale.add(e.getKey() + ": schema " + want + " vs class path " + have);
            }
        }
        assertEquals(List.of(), stale);
    }

    @Test
    void everyObjectSchemaIsGeneratedOrListedWithWhatServesIt() {
        Set<String> listed = new TreeSet<>(NOT_GENERATED.keySet());
        listed.addAll(NO_RECORD);
        Set<String> unknown = new TreeSet<>(listed);
        unknown.removeAll(generated.keySet());
        assertEquals(Set.of(), unknown, "listed names that are not object schemas");
    }

    @Test
    void theRecordsServingASchemaInsteadMatchItByName() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> e : NOT_GENERATED.entrySet()) {
            List<String> want = names(components(generated.get(e.getKey())));
            List<String> have = names(signature(Class.forName(e.getValue()), false));
            if (!want.equals(have)) {
                mismatches.add(e.getKey() + ": schema " + want + " vs " + e.getValue() + have);
            }
        }
        assertEquals(List.of(), mismatches);
    }

    @Test
    void noSliceDeclaresARecordThatDuplicatesAGeneratedOne() throws Exception {
        List<Class<?>> records = sliceRecords();
        assertTrue(records.size() > 10, "found only " + records.size() + " slice records");
        Map<String, String> found = duplicates(records);
        Set<String> stale = new TreeSet<>(ALLOWED_DUPLICATES.keySet());
        stale.removeAll(found.keySet());
        found.keySet().removeAll(ALLOWED_DUPLICATES.keySet());
        assertEquals(Map.of(), found, "use the generated record, or allow it with a reason");
        assertEquals(Set.of(), stale, "allowed duplicates that no longer duplicate anything");
    }

    /** A record a slice might copy from the schema, Dto suffix and all. */
    record CityDto(String id, String name, String timeZone) {}

    /** A nested copy: its components name other copies. */
    record SeatRowDto(String row, List<SeatDto> seats, List<Integer> aisleAfter) {}

    record SeatDto(String id, int number, String category, String status) {}

    /** Same names as City, one component dropped: not a duplicate. */
    record DriftedCity(String id, String name) {}

    @Test
    void theGuardFindsCopiesUnderAnyNameAndIgnoresOtherShapes() throws Exception {
        Map<String, String> found =
                duplicates(
                        List.of(CityDto.class, SeatRowDto.class, SeatDto.class, DriftedCity.class));
        assertEquals(
                Map.of(
                        CityDto.class.getName(), "City/CityRequest",
                        SeatRowDto.class.getName(), "SeatRow",
                        SeatDto.class.getName(), "Seat"),
                found);
    }

    /** Each record whose signature equals a generated record's, to that record's name. */
    private Map<String, String> duplicates(List<Class<?>> records) throws ClassNotFoundException {
        // Several schemas can share a shape (City and CityRequest): name them all.
        Map<List<String>, String> bySignature = new LinkedHashMap<>();
        for (String name : generated.keySet()) {
            bySignature.merge(
                    signature(Class.forName(GENERATED + "." + name), true),
                    name,
                    (a, b) -> a + "/" + b);
        }
        Map<String, String> found = new LinkedHashMap<>();
        for (Class<?> r : records) {
            String twin = bySignature.get(signature(r, true));
            if (twin != null) {
                found.put(r.getName(), twin);
            }
        }
        return found;
    }

    /** Every record class compiled from {@code dev.thiru.bms.features} in main. */
    private static List<Class<?>> sliceRecords()
            throws IOException, URISyntaxException, ClassNotFoundException {
        // The main output directory, not the first "dev/thiru/bms/features" on the class path,
        // which is the test tree's.
        Path base =
                Path.of(
                        Class.forName(GENERATED + ".City")
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        Path dir = base.resolve("dev/thiru/bms/features");
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String rel = base.relativize(f).toString().replace('\\', '/');
                String name = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                Class<?> c = Class.forName(name, false, RecordGeneratorTest.class.getClassLoader());
                if (c.isRecord()) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /**
     * {@code type name} per component, types by simple name ({@code List<Price>}). With {@code
     * dropDto}, a trailing {@code Dto} is dropped from each type name too, so a copy named {@code
     * SeatDto} reads as {@code Seat}.
     */
    private static List<String> signature(Class<?> record, boolean dropDto) {
        List<String> out = new ArrayList<>();
        for (RecordComponent c : record.getRecordComponents()) {
            out.add(simpleTypes(c.getGenericType().getTypeName(), dropDto) + " " + c.getName());
        }
        return out;
    }

    private static String simpleTypes(String type, boolean dropDto) {
        Matcher m = Pattern.compile("[\\w.$]+").matcher(type);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group();
            String simple =
                    name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
            if (dropDto && simple.endsWith("Dto")) {
                simple = simple.substring(0, simple.length() - "Dto".length());
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(simple));
        }
        m.appendTail(sb);
        return sb.toString().replace(",", ", ").replace(",  ", ", ");
    }

    private static List<String> names(List<String> components) {
        return components.stream().map(s -> s.substring(s.lastIndexOf(' ') + 1)).toList();
    }

    private static final Pattern COMPONENT =
            Pattern.compile("^\\s{8}(.+?) (\\w+)[,)]", Pattern.MULTILINE);

    /** The components of a generated source, as {@code type name}. */
    private static List<String> components(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = COMPONENT.matcher(source);
        while (m.find()) {
            out.add(m.group(1) + " " + m.group(2));
        }
        return out;
    }
}
