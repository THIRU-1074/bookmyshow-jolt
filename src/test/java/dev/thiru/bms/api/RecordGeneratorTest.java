package dev.thiru.bms.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generated records compile, and each one has the same components, in the same order and with
 * the same types, as the hand-written web record it will replace. A schema change that the web
 * records do not follow (or the reverse) fails here before any slice switches to generated types.
 */
class RecordGeneratorTest {

    private static final String WEB = "dev.thiru.bms.web.";

    private static final String JOLT_PAGE = "in.jolt.Page$Result";

    /**
     * Schema name to every record serialised for it today. A shape shared by a moved slice and the
     * legacy web layer (City, Cinema, MovieDetail while admin keeps its Dtos copy) lists both, and
     * each is checked against the schema.
     */
    static final Map<String, List<String>> WEB_RECORDS = webRecords();

    /** Object schemas with no single web record: built as maps or rendered by hand. */
    static final Set<String> NO_WEB_RECORD =
            Set.of(
                    "Problem",
                    "Health",
                    "SeatHeldEvent",
                    "SeatReleasedEvent",
                    "SeatBookedEvent",
                    "HeartbeatEvent");

    private static Map<String, List<String>> webRecords() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        String[][] dtos = {
            {"City", "CityDto"},
            {"MovieDetail", "MovieDetail"},
            {"Cinema", "CinemaDto"},
            {"Hold", "HoldDto"},
            {"ShowInfo", "ShowInfoDto"},
            {"BookingSeat", "BookingSeatDto"},
            {"Booking", "BookingDto"},
            {"Checkout", "CheckoutDto"},
            {"LayoutCategory", "CategoryRequest"},
            {"LayoutRow", "RowRequest"},
            {"Layout", "LayoutRequest"},
            {"Screen", "ScreenDto"},
            {"Show", "ShowDto"},
            {"ShowReport", "ShowReportDto"},
            {"CategoryReport", "CategoryReportDto"},
            {"ShowReportDetail", "ShowReportDetailDto"},
            {"Stats", "StatsDto"},
            {"Account", "AccountDto"},
            {"OwnedCinema", "OwnedCinemaDto"},
        };
        for (String[] d : dtos) {
            add(m, d[0], WEB + "Dtos$" + d[1]);
        }
        String catalog = "dev.thiru.bms.features.catalog.CatalogDtos$";
        String live = "dev.thiru.bms.features.live.LiveDtos$";
        add(m, "City", catalog + "CityDto");
        add(m, "Cinema", catalog + "CinemaDto");
        add(m, "MovieDetail", catalog + "MovieDetail");
        add(m, "MovieSummary", catalog + "MovieSummary");
        add(m, "ScreenSummary", catalog + "ScreenSummary");
        add(m, "CinemaDetail", catalog + "CinemaDetailDto");
        add(m, "Slot", catalog + "SlotDto");
        add(m, "CinemaShows", catalog + "CinemaShowsDto");
        add(m, "ShowTimes", catalog + "ShowTimesDto");
        add(m, "MovieRef", catalog + "MovieRef");
        add(m, "NamedRef", catalog + "NamedRef");
        add(m, "ShowDetail", catalog + "ShowDetailDto");
        for (String r : List.of("PriceDto", "SeatDto", "SeatRowDto", "SeatMapDto")) {
            String schema = r.substring(0, r.length() - "Dto".length());
            add(m, schema, catalog + r);
            add(m, schema, live + r);
        }
        String accounts = "dev.thiru.bms.features.accounts.AccountsDtos$";
        add(m, "User", accounts + "UserDto");
        add(m, "AuthResponse", accounts + "AuthResponse");
        add(m, "TokenResponse", accounts + "TokenResponse");
        add(m, "RegisterRequest", accounts + "RegisterRequest");
        add(m, "LoginRequest", accounts + "LoginRequest");
        add(m, "HoldRequest", WEB + "BookingModule$HoldRequest");
        add(m, "BookingRequest", WEB + "BookingModule$BookingRequest");
        add(m, "WebhookEvent", WEB + "PaymentModule$WebhookBody");
        add(m, "CompleteRequest", WEB + "PaymentModule$CompleteRequest");
        for (String r :
                List.of(
                        "CityRequest",
                        "RenameRequest",
                        "CinemaRequest",
                        "ScreenRequest",
                        "MovieRequest",
                        "ShowRequest",
                        "CancelShowResult")) {
            add(m, r, WEB + "AdminModule$" + r);
        }
        for (String page :
                List.of(
                        "MoviePage",
                        "CinemaPage",
                        "BookingPage",
                        "ShowReportPage",
                        "AccountPage")) {
            // The paging envelope is Jolt's own record since v0.4.1 (web.Paging builds it).
            add(m, page, JOLT_PAGE);
        }
        return m;
    }

    private static void add(Map<String, List<String>> m, String schema, String record) {
        m.computeIfAbsent(schema, k -> new ArrayList<>()).add(record);
    }

    private final OpenApi api = OpenApi.load();
    private final Map<String, String> generated =
            new RecordGenerator(api, "dev.thiru.bms.api.generated").generate();

    @Test
    void theGeneratedRecordsCompile(@TempDir Path dir) {
        List<Path> files = new RecordGenerator(api, "dev.thiru.bms.api.generated").writeTo(dir);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        StringWriter diagnostics = new StringWriter();
        boolean ok =
                javac.getTask(
                                diagnostics,
                                null,
                                null,
                                List.of("-d", dir.resolve("classes").toString()),
                                null,
                                javac.getStandardFileManager(null, null, null)
                                        .getJavaFileObjectsFromPaths(files))
                        .call();
        assertTrue(ok, diagnostics.toString());
    }

    @Test
    void everyObjectSchemaIsMappedToAWebRecordOrListedAsHavingNone() {
        Set<String> unmapped = new TreeSet<>(generated.keySet());
        unmapped.removeAll(WEB_RECORDS.keySet());
        unmapped.removeAll(NO_WEB_RECORD);
        assertEquals(Set.of(), unmapped);
    }

    @Test
    void eachGeneratedRecordMatchesTheWebRecordItWillReplace() throws Exception {
        assertEquals(List.of(), mismatches(WEB_RECORDS, generated));
    }

    /** A record a slice might write for City, with the schema's components. */
    record SliceCity(String id, String name, String timeZone) {}

    /** A slice copy of City that dropped a component. */
    record DriftedCity(String id, String name) {}

    @Test
    void aSchemaMappedToSeveralRecordsChecksEachOfThem() throws Exception {
        String dto = WEB + "Dtos$CityDto";
        String slice = RecordGeneratorTest.class.getName() + "$SliceCity";
        String drifted = RecordGeneratorTest.class.getName() + "$DriftedCity";
        assertEquals(List.of(), mismatches(Map.of("City", List.of(dto, slice)), generated));
        List<String> found = mismatches(Map.of("City", List.of(dto, drifted, slice)), generated);
        assertEquals(1, found.size(), found.toString());
        assertTrue(found.get(0).contains("DriftedCity"), found.get(0));
    }

    /** Every record whose components differ from its schema's generated record. */
    static List<String> mismatches(
            Map<String, List<String>> records, Map<String, String> generatedSources)
            throws ClassNotFoundException {
        // Record types are named by binary name first (two slices may both have a CityDto), then
        // by simple name for the legacy Dtos records.
        Map<String, String> webToSchema = new LinkedHashMap<>();
        records.forEach(
                (schema, classes) ->
                        classes.forEach(
                                cls -> {
                                    webToSchema.putIfAbsent(cls, schema);
                                    webToSchema.putIfAbsent(simple(cls), schema);
                                }));
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : records.entrySet()) {
            for (String cls : e.getValue()) {
                List<String> want = components(generatedSources.get(e.getKey()));
                List<String> have = new ArrayList<>();
                for (RecordComponent c : Class.forName(cls).getRecordComponents()) {
                    have.add(
                            normalise(c.getGenericType().getTypeName(), webToSchema)
                                    + " "
                                    + c.getName());
                }
                if (cls.equals(JOLT_PAGE)) {
                    // Generic envelope: compare names only, the item type is the page's own.
                    want = want.stream().map(s -> s.substring(s.lastIndexOf(' ') + 1)).toList();
                    have = have.stream().map(s -> s.substring(s.lastIndexOf(' ') + 1)).toList();
                }
                if (!want.equals(have)) {
                    mismatches.add(e.getKey() + ": schema " + want + " vs " + cls + " " + have);
                }
            }
        }
        return mismatches;
    }

    private static final Pattern COMPONENT =
            Pattern.compile("^\\s{8}(.+?) (\\w+)[,)]", Pattern.MULTILINE);

    private static List<String> components(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = COMPONENT.matcher(source);
        while (m.find()) {
            out.add(m.group(1) + " " + m.group(2));
        }
        return out;
    }

    private static String simple(String binaryName) {
        return binaryName.substring(
                Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$')) + 1);
    }

    /** {@code java.util.List<dev.thiru.bms.web.Dtos$PriceDto>} becomes {@code List<Price>}. */
    private static String normalise(String type, Map<String, String> webToSchema) {
        Matcher m = Pattern.compile("[\\w.$]+").matcher(type);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group();
            String s =
                    webToSchema.containsKey(name)
                            ? webToSchema.get(name)
                            : webToSchema.getOrDefault(simple(name), simple(name));
            m.appendReplacement(sb, Matcher.quoteReplacement(s));
        }
        m.appendTail(sb);
        return sb.toString().replace(",", ", ").replace(",  ", ", ");
    }
}
