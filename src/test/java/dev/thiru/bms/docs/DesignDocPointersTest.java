package dev.thiru.bms.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keeps {@code docs/SYSTEM_DESIGN.md} honest. The document names the code and the tests that back
 * each claim, and this test fails when a name no longer resolves, so the document cannot rot
 * silently.
 *
 * <h2>Pointer convention</h2>
 *
 * Only text between single backticks outside fenced code blocks is looked at. A backticked token is
 * a pointer when it has one of these shapes; any other backticked text is ordinary code and is
 * ignored.
 *
 * <ul>
 *   <li><b>File</b>: a repo-relative path that starts with {@code src/}, {@code docs/}, {@code
 *       api/}, {@code web/} or {@code .github/}, or one of the root files {@code pom.xml}, {@code
 *       README.md}, {@code AGENTS.md}, {@code Dockerfile}, {@code render.yaml}. It must exist (a
 *       trailing slash means a directory).
 *   <li><b>Member</b>: {@code Owner#member}. {@code Owner} is a Java class written as a simple name
 *       or with its package tail, such as {@code features.booking.BookingSql}, which must match
 *       exactly one file under {@code src/main/java}, {@code src/test/java} or {@code
 *       src/build/java}; or a repo-relative file path such as {@code
 *       src/main/resources/db/migrations/V1__schema.sql}. For a Java owner, {@code member} must be
 *       declared in that file (method, field or enum constant). For any other file it must occur
 *       there as a whole word, which is how a table or index name is pointed at.
 *   <li><b>Test</b>: a member pointer whose owner lives under {@code src/test/java}. The member
 *       must be a test method, that is, one of the six lines above its declaration holds
 *       {@code @Test}, {@code @ParameterizedTest} or {@code @RepeatedTest}. These are the
 *       document's proofs.
 * </ul>
 */
class DesignDocPointersTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path DOC = ROOT.resolve("docs/SYSTEM_DESIGN.md");

    private static final List<String> JAVA_ROOTS =
            List.of("src/main/java", "src/test/java", "src/build/java");

    private static final Pattern TICKS = Pattern.compile("`([^`\\r\\n]+)`");
    private static final Pattern FILE =
            Pattern.compile(
                    "(?:(?:src|docs|api|web|\\.github)/[\\w./$@-]*"
                            + "|pom\\.xml|README\\.md|AGENTS\\.md|Dockerfile|render\\.yaml)");
    private static final Pattern MEMBER = Pattern.compile("([\\w./-]+)#(\\w+)");
    private static final Pattern TEST_ANNOTATION =
            Pattern.compile("@(?:Test|ParameterizedTest|RepeatedTest)\\b");

    /** One backticked pointer and the document line it sits on. */
    record Pointer(int line, String token) {}

    // ---- the real document ----

    @Test
    void everyPointerInTheDesignDocumentResolves() throws IOException {
        String markdown = Files.readString(DOC);
        List<String> problems = problems(ROOT, markdown);
        assertTrue(problems.isEmpty(), "dangling pointers in docs/SYSTEM_DESIGN.md:\n" + problems);
    }

    @Test
    void theDocumentNamesManyProofsSoTheConventionIsNotVacuous() throws IOException {
        List<Pointer> all = pointers(Files.readString(DOC));
        long tests =
                all.stream()
                        .filter(p -> p.token().contains("#") && p.token().contains("Test#"))
                        .count();
        assertTrue(all.size() >= 150, "pointers found: " + all.size());
        assertTrue(tests >= 60, "proof tests named: " + tests);
    }

    @Test
    void everyProblemSectionHasTheSixPartsAndAtLeastOneProofTest() throws IOException {
        String markdown = Files.readString(DOC);
        List<String> problems = new ArrayList<>();
        int sections = 0;
        for (String[] section : sections(markdown)) {
            String heading = section[0];
            String body = section[1];
            if (!heading.matches("\\d+\\. .*")) {
                continue;
            }
            sections++;
            for (String label :
                    List.of(
                            "**The problem.**",
                            "**Why the naive approach fails.**",
                            "**The solution.**",
                            "**Where it lives.**",
                            "**Proof.**",
                            "**Trade-offs and limits.**")) {
                if (!body.contains(label)) {
                    problems.add("section \"" + heading + "\" lacks " + label);
                }
            }
            int proof = body.indexOf("**Proof.**");
            int end = body.indexOf("**Trade-offs and limits.**");
            if (proof >= 0 && end > proof) {
                boolean namesATest =
                        pointers(body.substring(proof, end)).stream()
                                .anyMatch(p -> p.token().matches("[\\w.]+Test#\\w+"));
                if (!namesATest) {
                    problems.add("section \"" + heading + "\" names no proof test");
                }
            }
        }
        assertTrue(sections >= 16, "numbered problem sections: " + sections);
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void theDocumentKeepsItsRequiredSections() throws IOException {
        String markdown = Files.readString(DOC);
        for (String heading :
                List.of(
                        "How the pieces fit",
                        "Scenario: two people click the last seat",
                        "Not solved or out of scope today",
                        "Scaling beyond one node",
                        "Pointer convention")) {
            assertTrue(
                    sections(markdown).stream().anyMatch(s -> s[0].contains(heading)),
                    "missing section: " + heading);
        }
    }

    @Test
    void mermaidBlocksAreBalancedAndStartWithAKnownDiagramType() throws IOException {
        List<String> lines = Files.readAllLines(DOC);
        boolean open = false;
        boolean mermaid = false;
        int blocks = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("```")) {
                if (!open) {
                    open = true;
                    mermaid = line.equals("```mermaid");
                    if (mermaid) {
                        blocks++;
                        String first = firstContentLine(lines, i + 1);
                        assertTrue(
                                first.startsWith("sequenceDiagram")
                                        || first.startsWith("stateDiagram-v2")
                                        || first.startsWith("flowchart "),
                                "line " + (i + 1) + ": unknown diagram type: " + first);
                    }
                } else {
                    open = false;
                    mermaid = false;
                }
            }
        }
        assertFalse(open, "an unclosed code fence");
        assertTrue(blocks >= 5, "mermaid diagrams: " + blocks);
    }

    // ---- the guard itself ----

    @Test
    void aMissingFileIsReportedByName(@TempDir Path repo) throws IOException {
        fakeRepo(repo);
        List<String> problems =
                problems(repo, "See `src/main/java/x/Gone.java` and `docs/also-gone.md`.");
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("src/main/java/x/Gone.java"), problems.get(0));
        assertTrue(problems.get(0).contains("line 1"), problems.get(0));
        assertTrue(problems.get(1).contains("docs/also-gone.md"), problems.get(1));
    }

    @Test
    void aMissingTestMethodIsReportedByName(@TempDir Path repo) throws IOException {
        fakeRepo(repo);
        List<String> problems = problems(repo, "Proof: `x.FooTest#removedLongAgo`.");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("x.FooTest#removedLongAgo"), problems.get(0));
        assertTrue(problems.get(0).contains("not declared"), problems.get(0));
    }

    @Test
    void aMissingClassAndAMissingMainMethodAreReported(@TempDir Path repo) throws IOException {
        fakeRepo(repo);
        List<String> problems = problems(repo, "`x.Nowhere#run` and `x.Foo#gone`.");
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("no class or file matches"), problems.get(0));
        assertTrue(problems.get(1).contains("x.Foo#gone"), problems.get(1));
    }

    @Test
    void aHelperThatIsNotATestDoesNotCountAsProof(@TempDir Path repo) throws IOException {
        fakeRepo(repo);
        List<String> problems = problems(repo, "`x.FooTest#helper`");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("not a test method"), problems.get(0));
    }

    @Test
    void anAmbiguousSimpleNameMustBeQualified(@TempDir Path repo) throws IOException {
        fakeRepo(repo);
        Files.createDirectories(repo.resolve("src/main/java/y"));
        Files.writeString(
                repo.resolve("src/main/java/y/Foo.java"),
                "package y;\nclass Foo {\n    void run() {}\n}\n");
        List<String> problems = problems(repo, "`Foo#run`");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("ambiguous"), problems.get(0));
        assertTrue(problems(repo, "`x.Foo#run` and `y.Foo#run`").isEmpty());
    }

    @Test
    void pointersThatExistPassAndOrdinaryCodeIsIgnored(@TempDir Path repo) throws IOException {
        fakeRepo(repo);
        String md =
                """
                Real: `src/main/java/x/Foo.java`, `x.Foo#run`, `x.FooTest#works`,
                `src/main/resources/db/schema.sql#seat_lock`, `docs/`.
                Not pointers: `seat_lock`, `BEGIN IMMEDIATE`, `409`, `a/b/c`.

                ```
                `src/main/java/x/InsideAFence.java` is skipped
                ```
                """;
        assertEquals(List.of(), problems(repo, md));
        assertEquals(5, pointers(md).stream().filter(p -> isPointer(p.token())).count());
    }

    @Test
    void aTableNameThatLeftTheMigrationIsReported(@TempDir Path repo) throws IOException {
        fakeRepo(repo);
        List<String> problems =
                problems(repo, "`src/main/resources/db/schema.sql#seat_lock_renamed`");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("seat_lock_renamed"), problems.get(0));
    }

    // ---- implementation ----

    private static void fakeRepo(Path repo) throws IOException {
        Files.createDirectories(repo.resolve("src/main/java/x"));
        Files.createDirectories(repo.resolve("src/test/java/x"));
        Files.createDirectories(repo.resolve("src/main/resources/db"));
        Files.createDirectories(repo.resolve("docs"));
        Files.writeString(
                repo.resolve("src/main/java/x/Foo.java"),
                "package x;\nclass Foo {\n    int run() {\n        return helper(1);\n    }\n}\n");
        Files.writeString(
                repo.resolve("src/test/java/x/FooTest.java"),
                """
                package x;
                class FooTest {
                    @Test
                    void works() {}

                    void helper() {}
                }
                """);
        Files.writeString(
                repo.resolve("src/main/resources/db/schema.sql"),
                "CREATE TABLE seat_lock (show_id INTEGER);\n");
    }

    /** Every backticked token outside code fences, with its 1-based line. */
    static List<Pointer> pointers(String markdown) {
        List<Pointer> out = new ArrayList<>();
        boolean fenced = false;
        String[] lines = markdown.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].strip().startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                continue;
            }
            Matcher m = TICKS.matcher(lines[i]);
            while (m.find()) {
                out.add(new Pointer(i + 1, m.group(1)));
            }
        }
        return out;
    }

    static boolean isPointer(String token) {
        return FILE.matcher(token).matches() || MEMBER.matcher(token).matches();
    }

    /** One message per pointer that does not resolve under {@code root}; empty when all do. */
    static List<String> problems(Path root, String markdown) throws IOException {
        List<Path> index = javaIndex(root);
        List<String> problems = new ArrayList<>();
        for (Pointer p : pointers(markdown)) {
            String problem = check(root, index, p.token());
            if (problem != null) {
                problems.add("line " + p.line() + ": `" + p.token() + "` " + problem);
            }
        }
        return problems;
    }

    /** Null when the pointer resolves (or is not a pointer), else why it does not. */
    private static String check(Path root, List<Path> index, String token) throws IOException {
        Matcher member = MEMBER.matcher(token);
        if (member.matches()) {
            return checkMember(root, index, member.group(1), member.group(2));
        }
        if (FILE.matcher(token).matches()) {
            Path file = root.resolve(token);
            boolean ok = token.endsWith("/") ? Files.isDirectory(file) : Files.exists(file);
            return ok ? null : "does not exist";
        }
        return null;
    }

    private static String checkMember(Path root, List<Path> index, String owner, String name)
            throws IOException {
        Path file;
        if (owner.contains("/")) {
            file = root.resolve(owner);
            if (!Files.isRegularFile(file)) {
                return "owner file does not exist";
            }
        } else {
            List<Path> found = matching(root, index, owner);
            if (found.isEmpty()) {
                return "no class or file matches " + owner;
            }
            if (found.size() > 1) {
                return "is ambiguous, qualify the class with its package tail: " + found;
            }
            file = found.get(0);
        }
        String text = Files.readString(file);
        if (!file.getFileName().toString().endsWith(".java")) {
            return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text).find()
                    ? null
                    : "is not declared in " + root.relativize(file).toString().replace('\\', '/');
        }
        Matcher decl = declaration(name).matcher(text);
        if (!decl.find()) {
            return "is not declared in " + root.relativize(file).toString().replace('\\', '/');
        }
        boolean underTests =
                root.relativize(file).toString().replace('\\', '/').startsWith("src/test/java/");
        if (underTests && !hasTestAnnotation(text, decl.start())) {
            return "is not a test method (no @Test within six lines above it)";
        }
        return null;
    }

    /** Every Java file under the source roots, walked once per check. */
    private static List<Path> javaIndex(Path root) throws IOException {
        List<Path> all = new ArrayList<>();
        for (String base : JAVA_ROOTS) {
            Path dir = root.resolve(base);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile).forEach(all::add);
                }
            }
        }
        return all;
    }

    private static List<Path> matching(Path root, List<Path> index, String owner) {
        String tail = "/" + owner.replace('.', '/') + ".java";
        return index.stream()
                .filter(
                        f ->
                                ("/" + root.relativize(f).toString().replace('\\', '/'))
                                        .endsWith(tail))
                .toList();
    }

    /** A method, field or enum constant declaration of {@code name}; calls do not match. */
    private static Pattern declaration(String name) {
        String n = Pattern.quote(name);
        String modifiers =
                "(?:(?:public|protected|private|static|final|abstract|synchronized|default)[ \\t]+)*";
        return Pattern.compile(
                "(?m)^[ \\t]*(?!return\\b|throw\\b|new\\b|else\\b|assert\\b)"
                        + "(?:@\\w+(?:\\([^)\\n]*\\))?[ \\t]+)*"
                        + modifiers
                        + "(?:<[^>\\n]+>[ \\t]+)?[\\w.<>\\[\\],? ]+?[ \\t]+"
                        + n
                        + "[ \\t]*[(=;]"
                        + "|(?m)^[ \\t]*"
                        + n
                        + "[ \\t]*[,;({]");
    }

    private static boolean hasTestAnnotation(String text, int declarationStart) {
        String[] lines = text.substring(0, declarationStart).split("\\R", -1);
        // The last element is the empty text before the declaration. Walk up through the
        // annotation lines directly above it and stop at a blank line or the end of the previous
        // member, so a neighbouring test's annotation is never mistaken for this method's.
        int visited = 0;
        for (int i = lines.length - 2; i >= 0 && visited < 6; i--, visited++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.endsWith(";") || line.endsWith("}") || line.endsWith("{")) {
                return false;
            }
            if (TEST_ANNOTATION.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /** Level-two sections: {heading text, body text}. */
    static List<String[]> sections(String markdown) {
        List<String[]> out = new ArrayList<>();
        String heading = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\\R", -1)) {
            if (line.startsWith("## ")) {
                if (heading != null) {
                    out.add(new String[] {heading, body.toString()});
                }
                heading = line.substring(3).strip();
                body = new StringBuilder();
            } else if (heading != null) {
                body.append(line).append('\n');
            }
        }
        if (heading != null) {
            out.add(new String[] {heading, body.toString()});
        }
        return out;
    }

    private static String firstContentLine(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                return lines.get(i).strip();
            }
        }
        return "";
    }
}
