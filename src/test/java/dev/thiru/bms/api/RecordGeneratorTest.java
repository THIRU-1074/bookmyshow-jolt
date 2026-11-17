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

    /** Schema name to the web record serialised for it today. */
    static final Map<String, String> WEB_RECORDS = webRecords();

    /** Object schemas with no single web record: built as maps or rendered by hand. */
    static final Set<String> NO_WEB_RECORD =
            Set.of(
                    "Problem",
                    "Health",
                    "SeatHeldEvent",
                    "SeatReleasedEvent",
                    "SeatBookedEvent",
                    "HeartbeatEvent");

    private static Map<String, String> webRecords() {
        Map<String, String> m = new LinkedHashMap<>();
        String[][] dtos = {
            {"User", "UserDto"},
            {"AuthResponse", "AuthResponse"},
            {"TokenResponse", "TokenResponse"},
            {"City", "CityDto"},
            {"MovieSummary", "MovieSummary"},
            {"MovieDetail", "MovieDetail"},
            {"Cinema", "CinemaDto"},
            {"ScreenSummary", "ScreenSummary"},
            {"CinemaDetail", "CinemaDetailDto"},
            {"Slot", "SlotDto"},
            {"CinemaShows", "CinemaShowsDto"},
            {"ShowTimes", "ShowTimesDto"},
            {"MovieRef", "MovieRef"},
            {"NamedRef", "NamedRef"},
            {"Price", "PriceDto"},
            {"ShowDetail", "ShowDetailDto"},
            {"Seat", "SeatDto"},
            {"SeatRow", "SeatRowDto"},
            {"SeatMap", "SeatMapDto"},
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
            m.put(d[0], WEB + "Dtos$" + d[1]);
        }
        m.put("RegisterRequest", WEB + "AuthModule$RegisterRequest");
        m.put("LoginRequest", WEB + "AuthModule$LoginRequest");
        m.put("HoldRequest", WEB + "BookingModule$HoldRequest");
        m.put("BookingRequest", WEB + "BookingModule$BookingRequest");
        m.put("WebhookEvent", WEB + "PaymentModule$WebhookBody");
        m.put("CompleteRequest", WEB + "PaymentModule$CompleteRequest");
        for (String r :
                List.of(
                        "CityRequest",
                        "RenameRequest",
                        "CinemaRequest",
                        "ScreenRequest",
                        "MovieRequest",
                        "ShowRequest",
                        "CancelShowResult")) {
            m.put(r, WEB + "AdminModule$" + r);
        }
        for (String page :
                List.of(
                        "MoviePage",
                        "CinemaPage",
                        "BookingPage",
                        "ShowReportPage",
                        "AccountPage")) {
            m.put(page, WEB + "PageResult");
        }
        return m;
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
        Map<String, String> webToSchema = new LinkedHashMap<>();
        WEB_RECORDS.forEach((schema, cls) -> webToSchema.putIfAbsent(simple(cls), schema));
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> e : WEB_RECORDS.entrySet()) {
            List<String> want = components(generated.get(e.getKey()));
            List<String> have = new ArrayList<>();
            for (RecordComponent c : Class.forName(e.getValue()).getRecordComponents()) {
                have.add(
                        normalise(c.getGenericType().getTypeName(), webToSchema)
                                + " "
                                + c.getName());
            }
            if (e.getValue().endsWith("PageResult")) {
                // Generic envelope: compare names only, the item type is the page's own.
                want = want.stream().map(s -> s.substring(s.lastIndexOf(' ') + 1)).toList();
                have = have.stream().map(s -> s.substring(s.lastIndexOf(' ') + 1)).toList();
            }
            if (!want.equals(have)) {
                mismatches.add(
                        e.getKey() + ": schema " + want + " vs " + e.getValue() + " " + have);
            }
        }
        assertEquals(List.of(), mismatches);
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
            String s = simple(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(webToSchema.getOrDefault(s, s)));
        }
        m.appendTail(sb);
        return sb.toString().replace(",", ", ").replace(",  ", ", ");
    }
}
