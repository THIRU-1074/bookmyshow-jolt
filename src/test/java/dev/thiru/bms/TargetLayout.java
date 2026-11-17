package dev.thiru.bms;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The dependency rules of the target layout (docs/restructure/PLAN.md): vertical slices under
 * {@code features.<slice>}, a pure {@code kernel}, I/O seams in {@code ports}, one adapter per port
 * and {@code Main} as the only composition root.
 *
 * <p>It reads source, not bytecode, so it also sees fully qualified names written inline. A rule
 * whose package does not exist yet has nothing to check and passes; that is what lets the rules go
 * in before the first slice moves.
 */
final class TargetLayout {

    static final String BASE = "dev.thiru.bms.";

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
                if (!other.equals(slice)) {
                    return "feature " + slice + " never imports feature " + other;
                }
            }
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
