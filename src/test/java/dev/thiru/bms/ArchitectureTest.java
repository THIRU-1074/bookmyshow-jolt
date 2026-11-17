package dev.thiru.bms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Fails the build when a layer imports something it must not depend on. */
class ArchitectureTest {

    private static final Path ROOT = Path.of("src/main/java/dev/thiru/bms");
    private static final String APP = "dev.thiru.bms.";

    private static List<String> violations(String layer, List<String> forbidden) {
        Path dir = ROOT.resolve(layer.replace('.', '/'));
        assertTrue(Files.isDirectory(dir), "missing layer " + dir);
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                    .flatMap(f -> importsOf(f).map(i -> f.getFileName() + ": " + i))
                    .filter(line -> forbidden.stream().anyMatch(line::contains))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Stream<String> importsOf(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .map(String::trim)
                    .filter(l -> l.startsWith("import "))
                    .map(l -> l.replace("import static ", "import "));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void kernelImportsOnlyTheJdk() {
        assertEquals(
                List.of(),
                violations(
                        "kernel",
                        List.of(
                                "import in.jolt.",
                                "import java.sql.",
                                "import javax.sql.",
                                "import " + APP + "domain.",
                                "import " + APP + "ports.",
                                "import " + APP + "application.",
                                "import " + APP + "adapters.",
                                "import " + APP + "web.",
                                "import " + APP + "features.")));
    }

    @Test
    void applicationNeverImportsAdaptersWebOrInfrastructure() {
        assertEquals(
                List.of(),
                violations(
                        "application",
                        List.of(
                                "import in.jolt.",
                                "import java.sql.",
                                "import javax.sql.",
                                "import " + APP + "adapters.",
                                "import " + APP + "web.")));
    }

    @Test
    void webNeverImportsAdapters() {
        assertEquals(List.of(), violations("web", List.of("import " + APP + "adapters.")));
    }

    @Test
    void adaptersNeverImportWeb() {
        assertEquals(List.of(), violations("adapters", List.of("import " + APP + "web.")));
    }
}
