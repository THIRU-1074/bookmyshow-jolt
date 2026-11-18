package dev.thiru.bms.docs;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The schema as the ERD can express it, read either from a live SQLite database or from the Mermaid
 * block of docs/ERD.md, plus the comparison between the two.
 *
 * <p>What the diagram encodes, and what the guard therefore checks: every table; every column with
 * its declared type; the PK, FK and UK markers; a {@code "nullable"} comment on every nullable
 * non-key column; one {@code %% unique table (a, b)} comment line per multi-column UNIQUE index
 * (Mermaid has no syntax for a column group); and one relationship per foreign key with its
 * cardinality, a non-empty verb label, and the word "cascade" in the label for ON DELETE CASCADE.
 * CHECK constraints, defaults, ordinary indexes and the prose are documentation only, because
 * SQLite does not expose CHECK clauses in a parseable form.
 */
final class ErdModel {

    /**
     * Table names (prefix match) the guard does not compare, each with the reason. Everything else
     * in the schema, including {@code schema_history}, MUST appear in the diagram.
     */
    static final Map<String, String> IGNORED_PREFIXES =
            Map.of(
                    "sqlite_",
                    "SQLite internal bookkeeping (sqlite_sequence, sqlite_stat1, ...); never part of"
                            + " the application model");

    /** Table types the guard does not compare: only ordinary tables hold application data. */
    static final Map<String, String> IGNORED_TYPES =
            Map.of(
                    "view", "views hold no data of their own",
                    "shadow", "internal tables of a virtual-table module",
                    "virtual", "virtual tables are not part of the relational model");

    record Column(String type, boolean pk, boolean fk, boolean uk, boolean nullable) {
        String keys() {
            List<String> k = new ArrayList<>();
            if (pk) k.add("PK");
            if (fk) k.add("FK");
            if (uk) k.add("UK");
            return k.isEmpty() ? "(none)" : String.join(", ", k);
        }
    }

    /** A relationship between two tables: {@code parent left--right child : label}. */
    record Relationship(
            String parent,
            String child,
            String left,
            String right,
            String label,
            boolean cascade,
            String columns) {
        String key() {
            return parent + " -> " + child;
        }

        String cardinality() {
            return left + "--" + right;
        }
    }

    static final class Table {
        final Map<String, Column> columns = new TreeMap<>();
        final Set<String> compositeUnique = new TreeSet<>();
    }

    final Map<String, Table> tables = new TreeMap<>();
    final Map<String, Relationship> relationships = new TreeMap<>();
    final List<String> parseErrors = new ArrayList<>();

    // ---------------------------------------------------------------- reading the live schema

    private record Info(String name, String type, boolean notNull, boolean pk) {
        boolean nullable() {
            return !pk && !notNull;
        }
    }

    private record Fk(String parent, List<String> from, boolean cascade) {}

    static ErdModel fromSchema(Connection c) throws SQLException {
        ErdModel m = new ErdModel();
        List<String> names = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA table_list")) {
            while (rs.next()) {
                String name = rs.getString("name");
                if ("main".equals(rs.getString("schema")) && !ignored(name, rs.getString("type"))) {
                    names.add(name);
                }
            }
        }
        for (String name : names) {
            m.readTable(c, name);
        }
        return m;
    }

    private static boolean ignored(String name, String type) {
        return IGNORED_TYPES.containsKey(type)
                || IGNORED_PREFIXES.keySet().stream().anyMatch(name::startsWith);
    }

    private void readTable(Connection c, String name) throws SQLException {
        List<Info> info = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA table_info(\"" + name + "\")")) {
            while (rs.next()) {
                info.add(
                        new Info(
                                rs.getString("name"),
                                rs.getString("type").toUpperCase(),
                                rs.getInt("notnull") == 1,
                                rs.getInt("pk") > 0));
            }
        }
        Map<Integer, List<String>> fromById = new TreeMap<>();
        Map<Integer, String> parentById = new TreeMap<>();
        Map<Integer, Boolean> cascadeById = new TreeMap<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA foreign_key_list(\"" + name + "\")")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                fromById.computeIfAbsent(id, k -> new ArrayList<>()).add(rs.getString("from"));
                parentById.put(id, rs.getString("table"));
                cascadeById.put(id, "CASCADE".equalsIgnoreCase(rs.getString("on_delete")));
            }
        }
        List<Fk> fks = new ArrayList<>();
        for (int id : fromById.keySet()) {
            fks.add(new Fk(parentById.get(id), fromById.get(id), cascadeById.get(id)));
        }
        List<String> indexNames = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA index_list(\"" + name + "\")")) {
            while (rs.next()) {
                if (rs.getInt("unique") == 1 && !"pk".equals(rs.getString("origin"))) {
                    indexNames.add(rs.getString("name"));
                }
            }
        }
        List<Set<String>> uniqueSets = new ArrayList<>();
        for (String index : indexNames) {
            Set<String> cols = new TreeSet<>();
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("PRAGMA index_info(\"" + index + "\")")) {
                while (rs.next()) {
                    cols.add(rs.getString("name"));
                }
            }
            uniqueSets.add(cols);
        }

        Set<String> pkCols = new TreeSet<>();
        info.stream().filter(Info::pk).forEach(i -> pkCols.add(i.name()));
        Set<String> fkCols = new TreeSet<>();
        fks.forEach(f -> fkCols.addAll(f.from()));
        Set<String> ukCols = new TreeSet<>();
        Table table = new Table();
        for (Set<String> u : uniqueSets) {
            ukCols.addAll(u);
            if (u.size() > 1) {
                table.compositeUnique.add(String.join(", ", u));
            }
        }
        for (Info i : info) {
            table.columns.put(
                    i.name(),
                    new Column(
                            i.type(),
                            i.pk(),
                            fkCols.contains(i.name()),
                            ukCols.contains(i.name()),
                            i.nullable()));
        }
        tables.put(name, table);

        // A foreign key is one-to-one when its columns are the whole PK or a whole UNIQUE index.
        List<Set<String>> identifying = new ArrayList<>(uniqueSets);
        identifying.add(pkCols);
        for (Fk fk : fks) {
            boolean required =
                    info.stream()
                            .filter(i -> fk.from().contains(i.name()))
                            .noneMatch(Info::nullable);
            boolean one = identifying.contains(new TreeSet<>(fk.from()));
            Relationship r =
                    new Relationship(
                            fk.parent(),
                            name,
                            required ? "||" : "|o",
                            one ? "o|" : "o{",
                            "",
                            fk.cascade(),
                            name + "(" + String.join(", ", fk.from()) + ")");
            if (relationships.put(r.key(), r) != null) {
                throw new IllegalStateException(
                        "two foreign keys from "
                                + name
                                + " to "
                                + fk.parent()
                                + "; ErdModel keys relationships by table pair and must be"
                                + " extended first");
            }
        }
    }

    // ------------------------------------------------------------------ reading docs/ERD.md

    private static final Pattern FENCE = Pattern.compile("(?s)```mermaid\\s*\\n(.*?)\\n```");
    private static final Pattern ENTITY_OPEN = Pattern.compile("(\\w+)\\s*\\{");
    private static final Pattern ATTRIBUTE =
            Pattern.compile(
                    "(\\w+)\\s+(\\w+)(?:\\s+((?:PK|FK|UK)(?:\\s*,\\s*(?:PK|FK|UK))*))?"
                            + "(?:\\s+\"([^\"]*)\")?");
    private static final Pattern RELATIONSHIP =
            Pattern.compile(
                    "(\\w+)\\s+(\\|\\||\\|o|\\}o|\\}\\|)(--|\\.\\.)(\\|\\||o\\||o\\{|\\|\\{)\\s+(\\w+)"
                            + "\\s*:\\s*(.+)");
    private static final Pattern UNIQUE_COMMENT =
            Pattern.compile("%%\\s*unique\\s+(\\w+)\\s*\\(([^)]*)\\)");

    static ErdModel fromMarkdown(Path file, String markdown) {
        Matcher fence = FENCE.matcher(markdown);
        ErdModel m = new ErdModel();
        if (!fence.find()) {
            m.parseErrors.add(file + " has no ```mermaid block");
            return m;
        }
        m.parseDiagram(file.toString(), fence.group(1));
        return m;
    }

    private void parseDiagram(String where, String diagram) {
        String[] lines = diagram.split("\n", -1);
        String current = null;
        List<String[]> composites = new ArrayList<>();
        boolean sawHeader = false;
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n].strip();
            String at = where + " diagram line " + (n + 1) + ": ";
            if (line.isEmpty()) {
                continue;
            }
            if (!sawHeader) {
                if (!line.equals("erDiagram")) {
                    parseErrors.add(at + "the diagram must start with 'erDiagram'");
                }
                sawHeader = true;
                continue;
            }
            Matcher u = UNIQUE_COMMENT.matcher(line);
            if (u.matches()) {
                composites.add(new String[] {u.group(1), u.group(2)});
                continue;
            }
            if (line.startsWith("%%")) {
                continue;
            }
            if (current == null) {
                Matcher e = ENTITY_OPEN.matcher(line);
                Matcher r = RELATIONSHIP.matcher(line);
                if (e.matches()) {
                    current = e.group(1);
                    if (tables.putIfAbsent(current, new Table()) != null) {
                        parseErrors.add(at + "entity '" + current + "' is declared twice");
                    }
                } else if (r.matches()) {
                    addRelationship(at, r);
                } else {
                    parseErrors.add(at + "cannot parse '" + line + "'");
                }
            } else if (line.equals("}")) {
                current = null;
            } else {
                Matcher a = ATTRIBUTE.matcher(line);
                if (!a.matches()) {
                    parseErrors.add(at + "cannot parse attribute '" + line + "'");
                    continue;
                }
                String keys = a.group(3) == null ? "" : a.group(3);
                String comment = a.group(4);
                boolean nullable = "nullable".equals(comment);
                if (comment != null && !nullable) {
                    parseErrors.add(
                            at + "attribute comment must be exactly \"nullable\", got: " + comment);
                }
                Column col =
                        new Column(
                                a.group(1).toUpperCase(),
                                has(keys, "PK"),
                                has(keys, "FK"),
                                has(keys, "UK"),
                                nullable);
                if (tables.get(current).columns.put(a.group(2), col) != null) {
                    parseErrors.add(
                            at + "column " + current + "." + a.group(2) + " is declared twice");
                }
            }
        }
        if (current != null) {
            parseErrors.add(where + ": entity '" + current + "' is never closed with }");
        }
        for (String[] p : composites) {
            Table t = tables.get(p[0]);
            if (t == null) {
                parseErrors.add("'%% unique' names unknown entity '" + p[0] + "'");
                continue;
            }
            List<String> cols = new ArrayList<>();
            for (String s : p[1].split(",")) {
                cols.add(s.strip());
            }
            Collections.sort(cols);
            t.compositeUnique.add(String.join(", ", cols));
        }
        for (Relationship r : relationships.values()) {
            for (String end : List.of(r.parent(), r.child())) {
                if (!tables.containsKey(end)) {
                    parseErrors.add(
                            "relationship "
                                    + r.key()
                                    + " names entity '"
                                    + end
                                    + "' with no block");
                }
            }
        }
    }

    private static boolean has(String keys, String key) {
        return keys.matches(".*\\b" + key + "\\b.*");
    }

    private void addRelationship(String at, Matcher r) {
        String label = r.group(6).strip();
        if (label.startsWith("\"") && label.endsWith("\"") && label.length() >= 2) {
            label = label.substring(1, label.length() - 1);
        }
        Relationship rel =
                new Relationship(
                        r.group(1),
                        r.group(5),
                        r.group(2),
                        r.group(4),
                        label,
                        label.toLowerCase().contains("cascade"),
                        "");
        if (relationships.put(rel.key(), rel) != null) {
            parseErrors.add(at + "relationship " + rel.key() + " is declared twice");
        }
    }

    // ------------------------------------------------------------------------ comparing

    /** Every difference between the live schema and the ERD, each as one readable line. */
    static List<String> diff(ErdModel schema, ErdModel erd) {
        List<String> out = new ArrayList<>(erd.parseErrors);
        for (String t : schema.tables.keySet()) {
            if (!erd.tables.containsKey(t)) {
                out.add("table '" + t + "' is in the schema but missing from the ERD");
            }
        }
        for (String t : erd.tables.keySet()) {
            if (!schema.tables.containsKey(t)) {
                out.add("table '" + t + "' is in the ERD but not in the schema");
            }
        }
        for (var e : schema.tables.entrySet()) {
            Table got = erd.tables.get(e.getKey());
            if (got != null) {
                diffTable(e.getKey(), e.getValue(), got, out);
            }
        }
        diffRelationships(schema, erd, out);
        return out;
    }

    private static void diffTable(String t, Table schema, Table erd, List<String> out) {
        for (var c : schema.columns.entrySet()) {
            String id = "column '" + t + "." + c.getKey() + "'";
            Column want = c.getValue();
            Column got = erd.columns.get(c.getKey());
            if (got == null) {
                out.add(id + " (" + want.type() + ") is in the schema but missing from the ERD");
                continue;
            }
            if (!want.type().equals(got.type())) {
                out.add(id + " type: schema " + want.type() + ", ERD " + got.type());
            }
            if (want.pk() != got.pk() || want.fk() != got.fk() || want.uk() != got.uk()) {
                out.add(id + " key markers: schema " + want.keys() + ", ERD " + got.keys());
            }
            if (want.nullable() != got.nullable()) {
                out.add(
                        id
                                + " nullability: schema "
                                + (want.nullable() ? "nullable" : "NOT NULL")
                                + ", ERD "
                                + (got.nullable() ? "marked nullable" : "not marked nullable"));
            }
        }
        for (String c : erd.columns.keySet()) {
            if (!schema.columns.containsKey(c)) {
                out.add("column '" + t + "." + c + "' is in the ERD but not in the schema");
            }
        }
        for (String u : schema.compositeUnique) {
            if (!erd.compositeUnique.contains(u)) {
                out.add("unique index (" + u + ") on '" + t + "' is missing from the ERD");
            }
        }
        for (String u : erd.compositeUnique) {
            if (!schema.compositeUnique.contains(u)) {
                out.add(
                        "unique index ("
                                + u
                                + ") on '"
                                + t
                                + "' is in the ERD but not in the schema");
            }
        }
    }

    private static void diffRelationships(ErdModel schema, ErdModel erd, List<String> out) {
        for (Relationship want : schema.relationships.values()) {
            Relationship got = erd.relationships.get(want.key());
            String id = "relationship " + want.key() + " via " + want.columns();
            if (got == null) {
                out.add(
                        id
                                + " is in the schema but missing from the ERD (expected "
                                + want.parent()
                                + " "
                                + want.cardinality()
                                + " "
                                + want.child()
                                + ")");
                continue;
            }
            if (!want.left().equals(got.left()) || !want.right().equals(got.right())) {
                out.add(
                        id
                                + " cardinality: schema "
                                + want.cardinality()
                                + ", ERD "
                                + got.cardinality());
            }
            if (got.label().isBlank() || got.label().toUpperCase().startsWith("TODO")) {
                out.add(id + " has no verb label in the ERD");
            }
            if (want.cascade() && !got.label().toLowerCase().contains("cascade")) {
                out.add(id + " is ON DELETE CASCADE, so its ERD label must say \"cascade\"");
            }
        }
        for (Relationship got : erd.relationships.values()) {
            if (!schema.relationships.containsKey(got.key())) {
                out.add(
                        "relationship "
                                + got.key()
                                + " is in the ERD but the schema has no foreign key for it (a"
                                + " logical reference without a FOREIGN KEY belongs in the prose,"
                                + " not the diagram)");
            }
        }
    }

    // --------------------------------------------------------------------------- printing

    /**
     * A Mermaid diagram generated from this (schema) model, with {@code TODO} as every verb label.
     * It is the starting point for editing docs/ERD.md, never a replacement for the hand-written
     * sections.
     */
    String render() {
        StringBuilder sb = new StringBuilder("erDiagram\n");
        for (var t : tables.entrySet()) {
            sb.append("    ").append(t.getKey()).append(" {\n");
            for (var c : t.getValue().columns.entrySet()) {
                Column col = c.getValue();
                sb.append("        ").append(col.type()).append(' ').append(c.getKey());
                if (!col.keys().equals("(none)")) {
                    sb.append(' ').append(col.keys());
                }
                if (col.nullable()) {
                    sb.append(" \"nullable\"");
                }
                sb.append('\n');
            }
            sb.append("    }\n");
            for (String u : t.getValue().compositeUnique) {
                sb.append("    %% unique ").append(t.getKey()).append(" (").append(u).append(")\n");
            }
        }
        for (Relationship r : relationships.values()) {
            sb.append("    ")
                    .append(r.parent())
                    .append(' ')
                    .append(r.cardinality())
                    .append(' ')
                    .append(r.child())
                    .append(" : \"TODO")
                    .append(r.cascade() ? " cascade" : "")
                    .append("\"\n");
        }
        return sb.toString();
    }
}
