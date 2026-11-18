package dev.thiru.bms.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiru.bms.adapters.db.SchemaRunner;
import dev.thiru.bms.adapters.db.Sqlite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fails the build when docs/ERD.md no longer matches the schema the migrations produce (AGENTS.md
 * rule 10). The schema is read from a fresh SQLite database migrated by the real {@code
 * SchemaRunner}, so a new migration changes what this test expects with no other edit.
 *
 * <p>To get the expected listing after a schema change, run
 *
 * <pre>mvn -q test -Dtest=ErdDriftTest#printExpectedErd -Derd.print=true</pre>
 *
 * It prints a Mermaid skeleton and writes it to {@code target/erd-expected.mmd}. Copy the entities
 * and relationships you need into docs/ERD.md and write the verb labels and prose by hand; nothing
 * overwrites that file.
 */
class ErdDriftTest {

    static final Path ERD = Path.of("docs", "ERD.md");

    @TempDir static Path dir;
    static ErdModel schema;

    @BeforeAll
    static void migrate() throws SQLException {
        String url = Sqlite.jdbcUrl(dir.resolve("erd.db"));
        SchemaRunner.migrate(url);
        try (Connection c = Sqlite.connect(url)) {
            schema = ErdModel.fromSchema(c);
        }
    }

    /** A diagram that matches the schema exactly, for the tests that damage it on purpose. */
    private static String good() {
        return schema.render().replace("TODO", "has");
    }

    private static List<String> check(String diagram) {
        return ErdModel.diff(
                schema, ErdModel.fromMarkdown(Path.of("x.md"), "```mermaid\n" + diagram + "```"));
    }

    // ------------------------------------------------------------ the guard on the real file

    @Test
    void erdMatchesTheMigratedSchema() throws IOException {
        String md = Files.readString(ERD, StandardCharsets.UTF_8);
        List<String> diffs = ErdModel.diff(schema, ErdModel.fromMarkdown(ERD, md));
        assertTrue(
                diffs.isEmpty(),
                () ->
                        "docs/ERD.md is out of date with the migrated schema ("
                                + diffs.size()
                                + " difference(s)). Update the diagram and prose in the same commit"
                                + " as the migration (AGENTS.md rule 10). To see the expected"
                                + " listing run: mvn -q test -Dtest=ErdDriftTest#printExpectedErd"
                                + " -Derd.print=true\n  - "
                                + String.join("\n  - ", diffs));
    }

    @Test
    void ignoredTablesAreOnlyTheDocumentedOnes() {
        assertEquals(java.util.Set.of("sqlite_"), ErdModel.IGNORED_PREFIXES.keySet());
        assertEquals(
                java.util.Set.of("view", "shadow", "virtual"), ErdModel.IGNORED_TYPES.keySet());
        assertTrue(schema.tables.containsKey("schema_history"), "schema_history must be covered");
        assertTrue(schema.tables.keySet().stream().noneMatch(t -> t.startsWith("sqlite_")));
    }

    // ------------------------------------------------------------------- the guard itself

    @Test
    void aDiagramGeneratedFromTheSchemaPasses() {
        assertEquals(List.of(), check(good()));
    }

    @Test
    void missingColumnIsReported() {
        String bad = good().replace("        INTEGER capacity\n", "");
        assertEquals(
                List.of(
                        "column 'screen.capacity' (INTEGER) is in the schema but missing from the ERD"),
                check(bad));
    }

    @Test
    void extraColumnIsReported() {
        String bad = good().replaceFirst("(    movie \\{\\n)", "$1        TEXT tagline\n");
        assertEquals(
                List.of("column 'movie.tagline' is in the ERD but not in the schema"), check(bad));
    }

    @Test
    void extraTableIsReported() {
        String bad = good() + "    ghost {\n        INTEGER id PK\n    }\n";
        assertEquals(List.of("table 'ghost' is in the ERD but not in the schema"), check(bad));
    }

    @Test
    void missingTableIsReportedWithItsRelationships() {
        String bad = good().replaceAll("(?s)    payment_event \\{.*?\\n    \\}\\n", "");
        assertEquals(
                List.of("table 'payment_event' is in the schema but missing from the ERD"),
                check(bad));
    }

    @Test
    void wrongTypeIsReported() {
        String bad =
                good().replace("INTEGER amount PK", "INTEGER amount PK")
                        .replace("INTEGER duration_minutes", "TEXT duration_minutes");
        assertEquals(
                List.of("column 'movie.duration_minutes' type: schema INTEGER, ERD TEXT"),
                check(bad));
    }

    @Test
    void wrongKeyMarkerIsReported() {
        String bad = good().replace("TEXT code UK", "TEXT code");
        assertEquals(
                List.of("column 'booking.code' key markers: schema UK, ERD (none)"), check(bad));
    }

    @Test
    void wrongNullabilityIsReported() {
        String bad = good().replace(" \"nullable\"", "");
        List<String> diffs = check(bad);
        assertTrue(
                diffs.contains(
                        "column 'seat_lock.booking_id' nullability: schema nullable, ERD not marked"
                                + " nullable"),
                diffs.toString());
    }

    @Test
    void missingForeignKeyRelationshipIsReported() {
        String bad = good().replaceAll("    seat_hold \\|\\|--o\\{ seat_lock : .*\\n", "");
        assertEquals(
                List.of(
                        "relationship seat_hold -> seat_lock via seat_lock(hold_id) is in the schema"
                                + " but missing from the ERD (expected seat_hold ||--o{ seat_lock)"),
                check(bad));
    }

    @Test
    void wrongCardinalityIsReported() {
        // booking.hold_id is UNIQUE, so the booking side is zero-or-one, not many.
        String bad = good().replace("seat_hold ||--o| booking", "seat_hold ||--o{ booking");
        assertEquals(
                List.of(
                        "relationship seat_hold -> booking via booking(hold_id) cardinality: schema"
                                + " ||--o|, ERD ||--o{"),
                check(bad));
    }

    @Test
    void optionalForeignKeyMustBeZeroOrOneOnTheParentSide() {
        String bad = good().replace("booking |o--o{ seat_lock", "booking ||--o{ seat_lock");
        assertEquals(
                List.of(
                        "relationship booking -> seat_lock via seat_lock(booking_id) cardinality:"
                                + " schema |o--o{, ERD ||--o{"),
                check(bad));
    }

    @Test
    void extraRelationshipWithoutAForeignKeyIsReported() {
        String bad = good() + "    payment ||--o{ payment_event : \"logs\"\n";
        List<String> diffs = check(bad);
        assertEquals(1, diffs.size(), diffs.toString());
        assertTrue(
                diffs.get(0)
                        .startsWith(
                                "relationship payment -> payment_event is in the ERD but the schema"
                                        + " has no foreign key for it"),
                diffs.get(0));
    }

    @Test
    void missingCompositeUniqueIsReported() {
        String bad = good().replace("    %% unique cinema (city_id, name)\n", "");
        assertEquals(
                List.of("unique index (city_id, name) on 'cinema' is missing from the ERD"),
                check(bad));
    }

    @Test
    void cascadeMustBeSaidInTheLabel() {
        String bad =
                good().replace(
                                "movie ||--o{ movie_genre : \"has cascade\"",
                                "movie ||--o{ movie_genre : \"has\"");
        assertEquals(
                List.of(
                        "relationship movie -> movie_genre via movie_genre(movie_id) is ON DELETE"
                                + " CASCADE, so its ERD label must say \"cascade\""),
                check(bad));
    }

    @Test
    void todoLabelIsRejected() {
        String bad =
                good().replace("city ||--o{ cinema : \"has\"", "city ||--o{ cinema : \"TODO\"");
        assertEquals(
                List.of(
                        "relationship city -> cinema via cinema(city_id) has no verb label in the ERD"),
                check(bad));
    }

    @Test
    void unparsableLinesAreReportedNotSkipped() {
        List<String> diffs = check(good() + "    this is nonsense\n");
        assertEquals(1, diffs.size(), diffs.toString());
        assertTrue(diffs.get(0).contains("cannot parse 'this is nonsense'"), diffs.get(0));
    }

    @Test
    void aFileWithoutAMermaidBlockFails() {
        List<String> diffs =
                ErdModel.diff(schema, ErdModel.fromMarkdown(Path.of("x.md"), "no diagram here"));
        assertTrue(diffs.get(0).contains("has no ```mermaid block"), diffs.toString());
    }

    // ----------------------------------------------------------------------- update helper

    @Test
    void printExpectedErd() throws IOException {
        Assumptions.assumeTrue(
                Boolean.getBoolean("erd.print"),
                "helper: run with -Derd.print=true to print the expected listing");
        String listing = schema.render();
        Path out = Path.of("target", "erd-expected.mmd");
        Files.createDirectories(out.getParent());
        Files.writeString(out, listing, StandardCharsets.UTF_8);
        System.out.println("---- expected ERD listing (also in " + out + ") ----\n" + listing);
    }
}
