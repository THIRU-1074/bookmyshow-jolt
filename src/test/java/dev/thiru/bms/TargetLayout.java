package dev.thiru.bms;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The dependency rules of the layout (SPEC section 10, docs/restructure/PLAN.md): vertical slices
 * under {@code features.<slice>}, a pure {@code kernel}, I/O seams in {@code ports}, one adapter
 * per port, the shared HTTP kit in {@code web} and {@code Main} as the only composition root.
 *
 * <ol>
 *   <li>{@code kernel} imports only the JDK, and never JDBC.
 *   <li>{@code ports} import only the kernel and the JDK.
 *   <li>{@code features.<slice>} never imports another slice or an adapter, and otherwise imports
 *       only {@link #FEATURE_IMPORTS}.
 *   <li>{@code adapters} never import {@code web} or {@code features}.
 *   <li>{@code web} imports features only in {@code WebModules}, the route table.
 *   <li>Only {@code Main} names adapters.
 *   <li>Time comes from the injected {@code Clock}: production code never reads the wall clock
 *       ({@link #WALL_CLOCK}), except {@link #CLOCK_FACTORY}, which creates the one system clock.
 * </ol>
 *
 * <p>It reads source, not bytecode, so it also sees fully qualified names written inline.
 */
final class TargetLayout {

    static final String BASE = "dev.thiru.bms.";

    /** What a slice may import besides its own slice. */
    static final List<String> FEATURE_IMPORTS =
            List.of(
                    "java.",
                    "in.jolt.",
                    BASE + "kernel.",
                    BASE + "ports.",
                    BASE + "web.",
                    BASE + "api.generated.");

    /**
     * The one file that may read the wall clock: {@code Main} creates {@code Clock.systemUTC()} and
     * hands it to everything else (sweeper, use cases, stores, the migration stamp). Any other call
     * would make time untestable and let two parts of the app disagree about "now".
     */
    static final String CLOCK_FACTORY = "Main.java";

    /**
     * Wall-clock reads: {@code X.now()} on the java.time types (an argument named like a clock is
     * the injected one and fine), {@code System.currentTimeMillis()}, {@code Clock.system...()},
     * {@code new Date()} and {@code Calendar.getInstance()}. {@code System.nanoTime()} is a
     * monotonic stopwatch, not the time of day, so it is not matched.
     */
    static final Pattern WALL_CLOCK =
            Pattern.compile(
                    "\\b(?:Instant|LocalDateTime|LocalDate|LocalTime|ZonedDateTime|OffsetDateTime"
                            + "|OffsetTime|Year|YearMonth|MonthDay)\\s*\\.\\s*now\\s*\\("
                            + "(?!\\s*\\w*[cC]lock\\b)"
                            + "|\\bSystem\\s*\\.\\s*currentTimeMillis\\s*\\("
                            + "|\\bClock\\s*\\.\\s*system\\w*\\s*\\("
                            + "|\\bnew\\s+(?:java\\.util\\.)?Date\\s*\\(\\s*\\)"
                            + "|\\bCalendar\\s*\\.\\s*getInstance\\s*\\(");

    private static final Pattern IMPORT =
            Pattern.compile(
                    "^\\s*import\\s+(?:static\\s+)?([\\w.]+)(?:\\.\\*)?\\s*;", Pattern.MULTILINE);
    // Lower-case segments then one upper-case type name: the shape of a qualified type reference.
    private static final Pattern QUALIFIED = Pattern.compile("\\b((?:[a-z_][\\w]*\\.)+[A-Z]\\w*)");
    private static final Pattern PACKAGE =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private TargetLayout() {}

    /** Every rule broken under {@code root}, the directory of package {@code dev.thiru.bms}. */
    static List<String> violations(Path root) {
        List<String> out = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String source = read(file);
            String pkg = packageOf(source);
            if (!rel.equals(CLOCK_FACTORY)) {
                Matcher wall = WALL_CLOCK.matcher(stripCommentsAndStrings(source));
                while (wall.find()) {
                    out.add(
                            rel
                                    + ": time comes from the injected Clock, never the wall clock ("
                                    + wall.group().replaceAll("\\s+", "")
                                    + ")");
                }
            }
            for (String dep : dependencies(source)) {
                String broken = check(rel, pkg, dep);
                if (broken != null) {
                    out.add(rel + ": " + broken + " (" + dep + ")");
                }
            }
        }
        return out;
    }

    private static String check(String rel, String pkg, String dep) {
        boolean jdk = dep.startsWith("java.");
        if (inPackage(pkg, "kernel")) {
            if (!jdk && !dep.startsWith(BASE + "kernel.")) {
                return "kernel imports only the JDK";
            }
            if (dep.startsWith("java.sql.")) {
                return "kernel never touches JDBC";
            }
            return null;
        }
        if (inPackage(pkg, "ports")) {
            if (!jdk && !dep.startsWith(BASE + "kernel.") && !dep.startsWith(BASE + "ports.")) {
                return "ports import only the kernel and the JDK";
            }
            return null;
        }
        if (inPackage(pkg, "features")) {
            String slice = sliceOf(pkg);
            if (dep.startsWith(BASE + "adapters.")) {
                return "features never import adapters";
            }
            if (dep.startsWith(BASE + "features.")) {
                String other = sliceOf(dep);
                return other.equals(slice)
                        ? null
                        : "feature " + slice + " never imports feature " + other;
            }
            if (FEATURE_IMPORTS.stream().noneMatch(dep::startsWith)) {
                return "features import only the kernel, ports, web, api.generated, Jolt and the JDK";
            }
            return null;
        }
        if (inPackage(pkg, "adapters")
                && (dep.startsWith(BASE + "web.") || dep.startsWith(BASE + "features."))) {
            return "adapters never import web or features";
        }
        if (inPackage(pkg, "web")
                && dep.startsWith(BASE + "features.")
                && !rel.equals("web/WebModules.java")) {
            return "web imports features only in WebModules";
        }
        if (dep.startsWith(BASE + "adapters.")
                && !inPackage(pkg, "adapters")
                && !rel.equals("Main.java")) {
            return "only Main names adapters";
        }
        return null;
    }

    private static boolean inPackage(String pkg, String top) {
        String p = BASE + top;
        return pkg.equals(p) || pkg.startsWith(p + ".");
    }

    /** {@code dev.thiru.bms.features.booking.x.Y} gives {@code booking}. */
    private static String sliceOf(String name) {
        String rest = name.substring((BASE + "features").length());
        if (rest.isEmpty()) {
            return "";
        }
        rest = rest.substring(1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /** Imports plus inline qualified names, with comments and string literals removed first. */
    static Set<String> dependencies(String source) {
        String code = stripCommentsAndStrings(source);
        Set<String> deps = new LinkedHashSet<>();
        Matcher m = IMPORT.matcher(code);
        while (m.find()) {
            deps.add(m.group(1));
        }
        String body = PACKAGE.matcher(IMPORT.matcher(code).replaceAll("")).replaceAll("");
        Matcher q = QUALIFIED.matcher(body);
        while (q.find()) {
            String name = q.group(1);
            if (name.startsWith(BASE)
                    || name.startsWith("in.jolt.")
                    || name.startsWith("java.")
                    || name.startsWith("javax.")
                    || name.startsWith("org.")
                    || name.startsWith("com.")) {
                deps.add(name);
            }
        }
        return deps;
    }

    static String packageOf(String source) {
        Matcher m = PACKAGE.matcher(stripCommentsAndStrings(source));
        return m.find() ? m.group(1) : "";
    }

    static String stripCommentsAndStrings(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                i = end < 0 ? s.length() : end + 3;
                out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != c) {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
                out.append(c).append(c);
            } else if (s.startsWith("//", i)) {
                int end = s.indexOf('\n', i);
                i = end < 0 ? s.length() : end;
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static final Pattern OPENS_TRANSACTION = Pattern.compile("\\binTransaction\\(");
    private static final Pattern ON_SQLITE = Pattern.compile("\\b(TestDb|SliceKit)\\b");

    /**
     * Every class under {@code mainFeatures} that opens a transaction and is named by no test under
     * {@code testFeatures} that runs on SQLite, as {@code <slice>/<Class>}. A test runs on SQLite
     * when it names {@code TestDb} or {@code SliceKit}, or a helper of its slice that does. This is
     * the guard that replaced the repository contract on SQLite, which caught {@code
     * catalogWrites()} shipping unimplemented while its use cases were tested only on a fake.
     */
    static List<String> sqlWithoutSqliteTests(Path mainFeatures, Path testFeatures) {
        List<String> out = new ArrayList<>();
        for (Path file : javaFiles(mainFeatures)) {
            if (!OPENS_TRANSACTION.matcher(stripCommentsAndStrings(read(file))).find()) {
                continue;
            }
            String rel = mainFeatures.relativize(file).toString().replace('\\', '/');
            String slice = rel.substring(0, rel.indexOf('/'));
            String name = file.getFileName().toString().replace(".java", "");
            Path tests = testFeatures.resolve(slice);
            Pattern named = Pattern.compile("\\b" + name + "\\b");
            boolean tested =
                    Files.isDirectory(tests)
                            && sqliteTests(tests).stream().anyMatch(t -> named.matcher(t).find());
            if (!tested) {
                out.add(slice + "/" + name);
            }
        }
        return out;
    }

    /** The code of the tests under {@code dir} that reach SQLite, directly or via a helper. */
    private static List<String> sqliteTests(Path dir) {
        Map<String, String> code = new LinkedHashMap<>();
        for (Path f : javaFiles(dir)) {
            code.put(
                    f.getFileName().toString().replace(".java", ""),
                    stripCommentsAndStrings(read(f)));
        }
        Set<String> onSqlite = new LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, String> e : code.entrySet()) {
                if (onSqlite.contains(e.getKey())) {
                    continue;
                }
                boolean direct = ON_SQLITE.matcher(e.getValue()).find();
                boolean viaHelper =
                        onSqlite.stream()
                                .anyMatch(
                                        h ->
                                                Pattern.compile("\\b" + h + "\\b")
                                                        .matcher(e.getValue())
                                                        .find());
                if (direct || viaHelper) {
                    onSqlite.add(e.getKey());
                    grew = true;
                }
            }
        }
        return onSqlite.stream().map(code::get).toList();
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
