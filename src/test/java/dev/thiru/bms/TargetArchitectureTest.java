package dev.thiru.bms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The target layout's dependency rules on the real sources, plus one fixture tree per rule that
 * breaks it, so a rule that silently stopped matching would fail here too. The legacy layer rules
 * stay in {@link ArchitectureTest} until the last slice has moved.
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
