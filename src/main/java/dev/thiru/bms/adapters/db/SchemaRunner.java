package dev.thiru.bms.adapters.db;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies classpath migrations named {@code V<n>__<name>.sql} once each, in numeric order, and
 * records them in {@code schema_history} together with a checksum so an edited migration is
 * detected instead of silently skipped.
 *
 * <p>Each migration runs in one transaction, so a failure leaves the database at the previous
 * version. Scripts must not contain trigger bodies ({@code BEGIN ... END}); the splitter only
 * understands quotes and comments.
 *
 * <p>Kept instead of Jolt's {@code Migrations.runClasspath} (0.4.0): that one records history in
 * {@code jolt_schema_history} with its own checksums, so on a database this runner already migrated
 * it would find no history and run V1 again over the existing tables. Render starts from an empty
 * disk on each deploy, but local databases and the Docker {@code /data} volume do not. See
 * docs/JOLT_GAPS.md.
 *
 * <p>TODO(jolt#86): switch to {@code Migrations.runClasspath} once it can baseline a database whose
 * history this runner recorded.
 */
public final class SchemaRunner {

    /** Default classpath directory holding the application migrations. */
    public static final String DEFAULT_LOCATION = "db/migrations";

    private static final Pattern NAME = Pattern.compile("V(\\d+)__.+\\.sql");

    private SchemaRunner() {}

    /** Applies the application migrations; returns the versions applied by this call. */
    public static List<Integer> migrate(String jdbcUrl, Clock clock) {
        return migrate(jdbcUrl, DEFAULT_LOCATION, clock);
    }

    /** Applies the migrations under {@code location}; returns the versions applied by this call. */
    public static List<Integer> migrate(String jdbcUrl, String location, Clock clock) {
        Map<Integer, Script> scripts = discover(location);
        try (Connection c = Sqlite.connect(jdbcUrl)) {
            return migrate(c, scripts, clock);
        } catch (SQLException e) {
            throw new IllegalStateException("migration failed: " + e.getMessage(), e);
        }
    }

    /** Applies the migrations under {@code location} on {@code c}, which the caller closes. */
    static List<Integer> migrate(Connection c, String location, Clock clock) throws SQLException {
        return migrate(c, discover(location), clock);
    }

    private static List<Integer> migrate(Connection c, Map<Integer, Script> scripts, Clock clock)
            throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(
                    "CREATE TABLE IF NOT EXISTS schema_history (version INTEGER PRIMARY KEY,"
                            + " name TEXT NOT NULL, checksum TEXT NOT NULL,"
                            + " applied_at INTEGER NOT NULL) STRICT");
        }
        List<Integer> applied = new ArrayList<>();
        for (Script s : scripts.values()) {
            if (apply(c, s, clock)) {
                applied.add(s.version());
            }
        }
        return applied;
    }

    /**
     * Runs {@code s} in one transaction. A failure of the cleanup (rollback, back to auto-commit)
     * after the migration failed is attached to the migration's failure as suppressed, never thrown
     * in its place: SQLite may already have ended the transaction, and the cleanup error would then
     * hide the real cause.
     */
    private static boolean apply(Connection c, Script s, Clock clock) throws SQLException {
        c.setAutoCommit(false);
        RuntimeException failure = null;
        try {
            // Checked inside the transaction (which is IMMEDIATE) so two starting processes
            // cannot both apply the same version.
            String recorded = checksumOf(c, s.version());
            if (recorded != null) {
                if (!recorded.equals(s.checksum())) {
                    throw new IllegalStateException(
                            "migration V" + s.version() + " changed after it was applied");
                }
                c.rollback();
                return false;
            }
            try (Statement st = c.createStatement()) {
                for (String sql : split(s.sql())) {
                    st.execute(sql);
                }
            }
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "INSERT INTO schema_history (version, name, checksum, applied_at)"
                                    + " VALUES (?, ?, ?, ?)")) {
                ps.setInt(1, s.version());
                ps.setString(2, s.name());
                ps.setString(3, s.checksum());
                ps.setLong(4, clock.millis());
                ps.executeUpdate();
            }
            c.commit();
            return true;
        } catch (SQLException | RuntimeException e) {
            failure =
                    e instanceof RuntimeException r
                            ? r
                            : new IllegalStateException(
                                    "migration V" + s.version() + " failed: " + e.getMessage(), e);
            try {
                c.rollback();
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            try {
                c.setAutoCommit(true);
            } catch (SQLException cleanup) {
                if (failure == null) {
                    throw cleanup;
                }
                failure.addSuppressed(cleanup);
            }
        }
    }

    private static String checksumOf(Connection c, int version) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT checksum FROM schema_history WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Splits a script on semicolons outside quotes and comments; blank statements are dropped. */
    static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = script.length();
        for (int i = 0; i < n; i++) {
            char ch = script.charAt(i);
            if (ch == '\'' || ch == '"') {
                int j = i + 1;
                while (j < n) {
                    if (script.charAt(j) == ch) {
                        if (j + 1 < n && script.charAt(j + 1) == ch) {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                cur.append(script, i, Math.min(j + 1, n));
                i = j;
            } else if (ch == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                while (i < n && script.charAt(i) != '\n') {
                    i++;
                }
                cur.append('\n');
            } else if (ch == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 1;
                cur.append(' ');
            } else if (ch == ';') {
                flush(out, cur);
            } else {
                cur.append(ch);
            }
        }
        flush(out, cur);
        return out;
    }

    private static void flush(List<String> out, StringBuilder cur) {
        String sql = cur.toString().strip();
        if (!sql.isEmpty()) {
            out.add(sql);
        }
        cur.setLength(0);
    }

    private record Script(int version, String name, String sql, String checksum) {}

    private static Map<Integer, Script> discover(String location) {
        Map<Integer, Script> found = new TreeMap<>();
        try {
            Enumeration<URL> roots = SchemaRunner.class.getClassLoader().getResources(location);
            for (URL root : Collections.list(roots)) {
                collect(root.toURI(), found);
            }
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("cannot read migrations at " + location, e);
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("no migrations found at classpath:" + location);
        }
        return found;
    }

    private static void collect(URI root, Map<Integer, Script> found) throws IOException {
        if ("jar".equals(root.getScheme())) {
            FileSystem fs;
            try {
                fs = FileSystems.newFileSystem(root, Map.of());
            } catch (FileSystemAlreadyExistsException e) {
                fs = FileSystems.getFileSystem(root);
            }
            String inner = root.getSchemeSpecificPart();
            read(fs.getPath(inner.substring(inner.indexOf("!/") + 1)), found);
        } else {
            read(Path.of(root), found);
        }
    }

    private static void read(Path dir, Map<Integer, Script> found) throws IOException {
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                Matcher m = NAME.matcher(p.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                int version = Integer.parseInt(m.group(1));
                // Normalised so a checkout with CRLF endings keeps the same checksum.
                String sql = Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
                Script previous =
                        found.put(
                                version,
                                new Script(version, p.getFileName().toString(), sql, sha256(sql)));
                if (previous != null) {
                    throw new IllegalStateException("duplicate migration version " + version);
                }
            }
        }
    }

    private static String sha256(String text) {
        try {
            byte[] d =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
