package dev.thiru.bms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The single architecture guard: the layout's dependency rules ({@link TargetLayout}) on the real
 * sources, plus one fixture tree per rule that breaks it, so a rule that silently stopped matching
 * would fail here too. A copy of the real tree with one bad import per rule proves the rules bite
 * on the real packages, not only on fixtures.
 */
class TargetArchitectureTest {

    private static final Path SOURCES = Path.of("src/main/java/dev/thiru/bms");
    private static final Path FIXTURES = Path.of("src/test/resources/arch-fixtures");

    private static List<String> fixture(String name) {
        Path root = FIXTURES.resolve(name);
        assertTrue(Files.isDirectory(root), "missing fixture " + root);
        return TargetLayout.violations(root);
    }

    @Test
    void productionSourcesFollowTheTargetRules() {
        assertEquals(List.of(), TargetLayout.violations(SOURCES));
    }

    @Test
    void theSourcesHoldOnlyTheRootKernelPortsAdaptersWebAndFeatures() throws IOException {
        try (Stream<Path> top = Files.list(SOURCES)) {
            assertEquals(
                    List.of("adapters", "features", "kernel", "ports", "web"),
                    top.filter(Files::isDirectory)
                            .map(d -> d.getFileName().toString())
                            .sorted()
                            .toList(),
                    "the legacy domain and application layers are gone");
        }
        try (Stream<Path> slices = Files.list(SOURCES.resolve("features"))) {
            assertEquals(
                    List.of("accounts", "admin", "booking", "catalog", "live", "payments"),
                    slices.map(d -> d.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void everyRuleHasRealSourcesToCheck() throws IOException {
        assertTrue(javaFiles(SOURCES.resolve("kernel")) >= 25, "kernel classes");
        assertTrue(javaFiles(SOURCES.resolve("ports")) >= 8, "port interfaces");
        assertTrue(javaFiles(SOURCES.resolve("features")) >= 30, "slice classes");
        assertTrue(javaFiles(SOURCES.resolve("adapters")) >= 15, "adapters");
        assertTrue(javaFiles(SOURCES.resolve("web")) >= 10, "web kit");
        assertTrue(Files.isRegularFile(SOURCES.resolve("Main.java")), "Main");
    }

    @Test
    void theRulesFireOnTheRealSources(@TempDir Path copy) throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Path to = copy.resolve(SOURCES.relativize(f).toString());
                Files.createDirectories(to.getParent());
                Files.copy(f, to);
            }
        }
        addImport(copy.resolve("kernel/booking/Booking.java"), "in.jolt.App");
        addImport(copy.resolve("kernel/Money.java"), "dev.thiru.bms.ports.Ids");
        addImport(copy.resolve("ports/Ids.java"), "dev.thiru.bms.adapters.ids.SecureIds");
        addImport(copy.resolve("ports/LiveSeats.java"), "dev.thiru.bms.web.Rbac");
        addImport(copy.resolve("kernel/SeatId.java"), "java.sql.ResultSet");
        addImport(
                copy.resolve("features/booking/BookingSql.java"),
                "dev.thiru.bms.features.catalog.CatalogSql");
        addImport(copy.resolve("features/live/LiveSql.java"), "dev.thiru.bms.adapters.db.SqliteDb");
        addImport(copy.resolve("features/admin/AdminSql.java"), "dev.thiru.bms.AppConfig");
        addImport(copy.resolve("adapters/db/SqliteDb.java"), "dev.thiru.bms.web.Paging");
        addImport(copy.resolve("web/Problems.java"), "dev.thiru.bms.features.booking.BookingSql");
        addImport(copy.resolve("AppConfig.java"), "dev.thiru.bms.adapters.ids.SecureIds");
        assertEquals(
                Stream.of(
                                "AppConfig.java: only Main names adapters"
                                        + " (dev.thiru.bms.adapters.ids.SecureIds)",
                                "adapters/db/SqliteDb.java: adapters never import web or features"
                                        + " (dev.thiru.bms.web.Paging)",
                                "features/admin/AdminSql.java: features import only the kernel, ports,"
                                        + " web, api.generated, Jolt and the JDK (dev.thiru.bms.AppConfig)",
                                "features/booking/BookingSql.java: feature booking never imports"
                                        + " feature catalog (dev.thiru.bms.features.catalog.CatalogSql)",
                                "features/live/LiveSql.java: features never import adapters"
                                        + " (dev.thiru.bms.adapters.db.SqliteDb)",
                                "kernel/booking/Booking.java: kernel imports only the JDK (in.jolt.App)",
                                "kernel/Money.java: kernel imports only the JDK (dev.thiru.bms.ports.Ids)",
                                "ports/Ids.java: ports import only the kernel and the JDK"
                                        + " (dev.thiru.bms.adapters.ids.SecureIds)",
                                "kernel/SeatId.java: kernel never touches JDBC (java.sql.ResultSet)",
                                "ports/LiveSeats.java: ports import only the kernel and the JDK"
                                        + " (dev.thiru.bms.web.Rbac)",
                                "web/Problems.java: web imports features only in WebModules"
                                        + " (dev.thiru.bms.features.booking.BookingSql)")
                        .sorted()
                        .toList(),
                // Sorted: the walk order of upper- and lower-case names differs by platform.
                TargetLayout.violations(copy).stream().sorted().toList());
    }

    private static long javaFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.toString().endsWith(".java")).count();
        }
    }

    private static void addImport(Path file, String name) throws IOException {
        String src = Files.readString(file);
        int end = src.indexOf(';', src.indexOf("package ")) + 1;
        Files.writeString(
                file, src.substring(0, end) + "\nimport " + name + ";" + src.substring(end));
    }

    @Test
    void aLayoutWithEveryPackageAndOnlyLegalDependenciesPasses() {
        assertEquals(List.of(), fixture("clean"));
    }

    @Test
    void kernelImportingJoltIsCaught() {
        assertEquals(
                List.of(
                        "kernel/BookingCode.java: kernel imports only the JDK (in.jolt.JsonMapper)"),
                fixture("kernel-imports-jolt"));
    }

    @Test
    void kernelNamingAPortInlineIsCaught() {
        assertEquals(
                List.of(
                        "kernel/RefundPolicy.java: kernel imports only the JDK"
                                + " (dev.thiru.bms.ports.Clock)"),
                fixture("kernel-names-port-inline"));
    }

    @Test
    void featureImportingAnotherFeatureIsCaught() {
        assertEquals(
                List.of(
                        "features/booking/CreateBooking.java: feature booking never imports"
                                + " feature catalog (dev.thiru.bms.features.catalog.ShowSql)"),
                fixture("feature-imports-feature"));
    }

    @Test
    void featureImportingAnAdapterIsCaught() {
        assertEquals(
                List.of(
                        "features/payments/Webhook.java: features never import adapters"
                                + " (dev.thiru.bms.adapters.payment.HmacVerifier)"),
                fixture("feature-imports-adapter"));
    }

    @Test
    void featureImportingAnythingOffItsListIsCaught() {
        String rule =
                "features/catalog/Movies.java: features import only the kernel, ports, web,"
                        + " api.generated, Jolt and the JDK";
        assertEquals(
                List.of(
                        rule + " (dev.thiru.bms.domain.port.Repositories)",
                        // The generator is build-time code; only its output is importable.
                        rule + " (dev.thiru.bms.api.OpenApi)",
                        rule + " (org.yaml.snakeyaml.Yaml)",
                        rule + " (dev.thiru.bms.AppConfig)"),
                fixture("feature-imports-unlisted"));
    }

    @Test
    void kernelUsingJdbcIsCaught() {
        assertEquals(
                List.of(
                        "kernel/catalog/ShowRow.java: kernel never touches JDBC"
                                + " (java.sql.ResultSet)"),
                fixture("kernel-imports-jdbc"));
    }

    @Test
    void adapterImportingWebOrAFeatureIsCaught() {
        String rule = "adapters/db/CachedCatalog.java: adapters never import web or features";
        assertEquals(
                List.of(
                        rule + " (dev.thiru.bms.web.CatalogCache)",
                        rule + " (dev.thiru.bms.features.catalog.CatalogSql)"),
                fixture("adapter-imports-web"));
    }

    @Test
    void webImportingAFeatureOutsideTheRouteTableIsCaught() {
        assertEquals(
                List.of(
                        "web/Problems.java: web imports features only in WebModules"
                                + " (dev.thiru.bms.features.booking.BookingRoutes)"),
                fixture("web-imports-feature"));
    }

    @Test
    void portImportingJoltIsCaught() {
        assertEquals(
                List.of("ports/Db.java: ports import only the kernel and the JDK (in.jolt.db.Sql)"),
                fixture("port-imports-jolt"));
    }

    @Test
    void portImportingAFeatureIsCaught() {
        assertEquals(
                List.of(
                        "ports/SeatEvents.java: ports import only the kernel and the JDK"
                                + " (dev.thiru.bms.features.live.SeatEvent)"),
                fixture("port-imports-feature"));
    }

    @Test
    void aClassOtherThanMainNamingAnAdapterIsCaught() {
        assertEquals(
                List.of(
                        "Wiring.java: only Main names adapters"
                                + " (dev.thiru.bms.adapters.db.SqliteDb)"),
                fixture("non-main-names-adapter"));
    }

    private static final Path FEATURE_TESTS = Path.of("src/test/java/dev/thiru/bms/features");

    @Test
    void everySliceClassThatRunsSqlIsTestedOnSqlite() {
        assertEquals(
                List.of(),
                TargetLayout.sqlWithoutSqliteTests(SOURCES.resolve("features"), FEATURE_TESTS));
    }

    @Test
    void sqlTestedOnlyOnAFakeIsCaught(@TempDir Path root) throws IOException {
        Path main = root.resolve("main");
        Path test = root.resolve("test");
        write(
                main.resolve("catalog/CatalogWrites.java"),
                "class CatalogWrites { void save() { db.inTransaction(tx -> 1); } }");
        write(main.resolve("catalog/Pure.java"), "class Pure { /* inTransaction( */ }");
        write(
                main.resolve("booking/Holds.java"),
                "class Holds { void hold() { db.inTransaction(tx -> 1); } }");
        write(
                test.resolve("catalog/CatalogWritesTest.java"),
                "class CatalogWritesTest { CatalogWrites w = new CatalogWrites(fakeWorld); }");
        write(test.resolve("booking/World.java"), "class World { TestDb db = TestDb.open(dir); }");
        write(test.resolve("booking/HoldsTest.java"), "class HoldsTest { World w; Holds h; }");
        assertEquals(
                List.of("catalog/CatalogWrites"), TargetLayout.sqlWithoutSqliteTests(main, test));
    }

    private static void write(Path file, String src) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, src);
    }

    @Test
    void commentsAndStringsAreNotDependencies() {
        String src =
                "package dev.thiru.bms.kernel;\n"
                        + "// import in.jolt.App;\n"
                        + "/* dev.thiru.bms.adapters.db.SqlDb */\n"
                        + "class A { String s = \"in.jolt.App\"; char c = '\"'; java.util.List l; }\n";
        assertEquals(java.util.Set.of("java.util.List"), TargetLayout.dependencies(src));
    }
}
