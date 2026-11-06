/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.tables;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The table editor's Text toggle (EDITORS.md section 2, option C): one table as one Rachana statement, and the statement read back onto the
 * table's document. Every expression in it is Rachana as the table's own (row scope over the source); the statement adds no meaning of its
 * own, so it can be printed from any table over a kind or another table and read back without loss:
 *
 * <pre>
 * table trading.trade_view from trade
 * grain $.LEGS[*]
 * select $.TRADE_ID as TRADE_ID: string key,
 *        $.MTM / 1000000 as MTM_M: decimal,
 *        related(core.desks.NAME, on = $.DESK) as DESK_NAME: string
 * where $.STATUS != 'CANCELLED' &amp;&amp; $.STATUS != null
 * </pre>
 *
 * A column's other keys (format, meaning, masks, descriptions), the rules, materialisation and the rest of the table are kept from the
 * document the statement is read onto. A source the statement cannot say (SQL, Python, a model's answer, a lake table) is refused in words:
 * such a table is edited in the visual view or the YAML.
 */
public final class TableStatement {

    /** A statement that does not read, with where (the 1-based line) and why. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int line;

        Refused(int line, String message) {
            super(message);
            this.line = line;
        }

        public int line() {
            return line;
        }
    }

    private static final Pattern PATH = Pattern.compile("(\\$|@|\\^+)(\\.[A-Za-z_][A-Za-z0-9_]*|\\[\\d+])*");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> TYPES = Set.of("string", "integer", "decimal", "boolean", "date", "timestamp");
    private static final List<String> CLAUSES = List.of("table", "from", "grain", "select", "where");
    /** The keys of a column the statement says; every other key of the column is kept as it was. */
    private static final Set<String> SAID = Set.of("path", "expr", "python", "type", "key");

    private TableStatement() {}

    // ---- printing -------------------------------------------------------------------------------------------------------------------

    /** The statement of a table's document; refused for a source the statement cannot say. */
    @SuppressWarnings("unchecked")
    public static String print(Map<String, Object> doc) {
        String name = text(doc.get("table"));
        String from = source(doc.get("from"));
        StringBuilder b = new StringBuilder();
        b.append("table ").append(name.isEmpty() ? "domain.name" : name).append(" from ").append(from).append('\n');
        String grain = text(doc.get("grain"));
        if (!grain.isEmpty()) {
            b.append("grain ").append(grain).append('\n');
        }
        Set<String> keys = keys(doc);
        Map<String, Object> columns = doc.get("columns") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<String> items = new ArrayList<>();
        for (Map.Entry<String, Object> e : columns.entrySet()) {
            Map<String, Object> c = e.getValue() instanceof Map<?, ?> cm ? (Map<String, Object>) cm : Map.of("path", text(e.getValue()));
            String what = c.get("expr") != null ? text(c.get("expr")) : c.get("path") != null ? text(c.get("path")) : "";
            if (what.isEmpty()) {
                throw new Refused(0, "the column " + e.getKey() + " is computed by Python: edit this table in the visual view or the YAML");
            }
            StringBuilder i = new StringBuilder(what).append(" as ").append(e.getKey());
            String type = text(c.get("type"));
            if (!type.isEmpty()) {
                i.append(": ").append(type);
            }
            if (Boolean.TRUE.equals(c.get("key")) || "key".equals(c.get("role")) || keys.contains(e.getKey())) {
                i.append(" key");
            }
            items.add(i.toString());
        }
        b.append("select ");
        for (int i = 0; i < items.size(); i++) {
            b.append(i == 0 ? "" : "       ").append(items.get(i)).append(i < items.size() - 1 ? ",\n" : "\n");
        }
        if (items.isEmpty()) {
            b.append('\n');
        }
        String where = text(doc.get("filter"));
        if (!where.isEmpty()) {
            b.append("where ").append(where).append('\n');
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static String source(Object from) {
        if (from instanceof String s) {
            return s;
        }
        Map<String, Object> f = from instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        for (String k : List.of("query", "python", "model", "lake")) {
            if (f.get(k) != null) {
                throw new Refused(0, "a table whose source is " + (k.equals("query") ? "SQL" : k.equals("python") ? "Python" : k.equals("model")
                        ? "a model's answer" : "a lake table") + " is edited in the visual view or the YAML");
            }
        }
        if (f.get("table") != null) {
            return "table " + text(f.get("table"));
        }
        String kind = text(f.get("kind"));
        return kind.isEmpty() ? "kind" : kind;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> keys(Map<String, Object> doc) {
        Set<String> out = new LinkedHashSet<>();
        if (doc.get("keys") instanceof List<?> l) {
            l.forEach(k -> out.add(String.valueOf(k)));
        }
        return out;
    }

    // ---- reading --------------------------------------------------------------------------------------------------------------------

    /** The document of {@code statement} read onto {@code base} (the table as it was: every key the statement does not say is kept). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(String statement, Map<String, Object> base) {
        String text = stripComments(statement == null ? "" : statement);
        Map<String, int[]> at = clauses(text);
        for (String c : List.of("table", "from", "select")) {
            if (!at.containsKey(c)) {
                throw new Refused(1, "a table statement says table <domain>.<name> from <kind> select ...: '" + c + "' is missing");
            }
        }
        List<String> order = new ArrayList<>(at.keySet());
        if (!order.equals(CLAUSES.stream().filter(at::containsKey).toList())) {
            throw new Refused(1, "the clauses come in the order table, from, grain, select, where");
        }
        Map<String, Object> doc = new LinkedHashMap<>(base == null ? Map.of() : base);
        doc.putIfAbsent("rachana", 3);
        String name = body(text, at, "table");
        if (!NAME.matcher(name.replace(".", "_")).matches()) {
            throw new Refused(line(text, at.get("table")[0]), "after table comes the table's name, <domain>.<name>: not '" + name + "'");
        }
        doc.put("table", name);
        doc.put("from", from(body(text, at, "from"), doc.get("from"), line(text, at.get("from")[0])));
        if (at.containsKey("grain")) {
            doc.put("grain", body(text, at, "grain"));
        } else {
            doc.remove("grain");
        }
        if (at.containsKey("where")) {
            doc.put("filter", body(text, at, "where"));
        } else {
            doc.remove("filter");
        }
        Map<String, Object> before = doc.get("columns") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> columns = new LinkedHashMap<>();
        List<String> keyed = new ArrayList<>();
        int selectAt = at.get("select")[1];
        for (String item : split(body(text, at, "select"))) {
            int itemLine = line(text, selectAt + Math.max(0, text.indexOf(item.strip(), selectAt) - selectAt));
            Column c = column(item.strip(), itemLine);
            if (columns.containsKey(c.name)) {
                throw new Refused(itemLine, "the column " + c.name + " is selected twice");
            }
            Map<String, Object> old = before.get(c.name) instanceof Map<?, ?> om ? new LinkedHashMap<>((Map<String, Object>) om) : new LinkedHashMap<>();
            Map<String, Object> col = new LinkedHashMap<>();
            col.put(c.path ? "path" : "expr", c.what);
            String type = c.type != null ? c.type : old.get("type") == null ? null : String.valueOf(old.get("type"));
            if (type != null) {
                col.put("type", type);
            }
            old.forEach((k, v) -> {
                if (!SAID.contains(k) && !("role".equals(k) && "key".equals(v))) {
                    col.put(k, v);
                }
            });
            if (c.key) {
                keyed.add(c.name);
            }
            columns.put(c.name, col);
        }
        if (doc.get("keys") instanceof List<?> l) {
            List<String> keys = new ArrayList<>();
            l.stream().map(String::valueOf).filter(k -> !columns.containsKey(k)).filter(k -> k.equals("business_date")).forEach(keys::add);
            keys.addAll(keyed);
            if (keys.isEmpty()) {
                doc.remove("keys");
            } else {
                doc.put("keys", keys);
            }
        } else {
            keyed.forEach(k -> ((Map<String, Object>) columns.get(k)).put("key", true));
        }
        doc.put("columns", columns);
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static Object from(String text, Object before, int line) {
        Map<String, Object> old = before instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m)
                : before instanceof String s ? new LinkedHashMap<>(Map.of("kind", s)) : new LinkedHashMap<>();
        String[] words = text.strip().split("\\s+");
        Map<String, Object> out = new LinkedHashMap<>();
        if (words.length == 2 && words[0].equals("table")) {
            out.put("table", words[1]);
            return out;
        }
        if (words.length != 1 || words[0].isEmpty()) {
            throw new Refused(line, "after from comes a kind (from trade) or a table (from table trading.trade_legs): not '" + text.strip() + "'");
        }
        out.put("kind", words[0]);
        if (words[0].equals(old.get("kind"))) {
            for (String k : List.of("pack", "connector")) {
                if (old.get(k) != null) {
                    out.put(k, old.get(k));
                }
            }
        }
        return out;
    }

    private record Column(String name, String what, boolean path, String type, boolean key) {}

    private static Column column(String item, int line) {
        int as = lastAs(item);
        String what;
        String spec;
        if (as < 0) {
            what = item;
            if (!PATH.matcher(what).matches()) {
                throw new Refused(line, "name the computed column: " + what + " as NAME");
            }
            Matcher last = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)$").matcher(what);
            if (!last.find()) {
                throw new Refused(line, "name the column: " + what + " as NAME");
            }
            spec = last.group(1);
        } else {
            what = item.substring(0, as).strip();
            spec = item.substring(as).strip().substring(2).strip();
        }
        boolean key = false;
        String s = spec;
        if (s.toLowerCase(Locale.ROOT).endsWith(" key") || s.equals("key")) {
            key = true;
            s = s.substring(0, s.length() - 3).strip();
        }
        String type = null;
        int colon = s.indexOf(':');
        if (colon >= 0) {
            type = s.substring(colon + 1).strip();
            s = s.substring(0, colon).strip();
            if (!TYPES.contains(type)) {
                throw new Refused(line, "a column's type is one of " + String.join(", ", TYPES.stream().sorted().toList()) + ": not '" + type + "'");
            }
        }
        if (!NAME.matcher(s).matches()) {
            throw new Refused(line, "a column's name is letters, digits and underscores: not '" + s + "'");
        }
        if (what.isEmpty()) {
            throw new Refused(line, "the column " + s + " says what it is before as: a path ($.MTM) or a Rachana expression");
        }
        return new Column(s, what, PATH.matcher(what).matches(), type, key);
    }

    /** The place of the last {@code as} outside quotes and brackets, or -1. */
    private static int lastAs(String item) {
        int found = -1;
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < item.length(); i++) {
            char ch = item.charAt(i);
            if (quote != 0) {
                if (ch == '\\') {
                    i++;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            switch (ch) {
                case '\'', '"' -> quote = ch;
                case '(', '[', '{' -> depth++;
                case ')', ']', '}' -> depth--;
                default -> {
                    if (depth == 0 && Character.isWhitespace(ch) && i + 3 < item.length() && item.regionMatches(true, i + 1, "as", 0, 2)
                            && Character.isWhitespace(item.charAt(i + 3))) {
                        found = i + 1;
                    }
                }
            }
        }
        return found;
    }

    /** The items of a select list: split at commas outside quotes and brackets. */
    static List<String> split(String list) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < list.length(); i++) {
            char ch = list.charAt(i);
            if (quote != 0) {
                if (ch == '\\') {
                    i++;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            switch (ch) {
                case '\'', '"' -> quote = ch;
                case '(', '[', '{' -> depth++;
                case ')', ']', '}' -> depth--;
                case ',' -> {
                    if (depth == 0) {
                        out.add(list.substring(start, i));
                        start = i + 1;
                    }
                }
                default -> { }
            }
        }
        if (!list.substring(start).isBlank()) {
            out.add(list.substring(start));
        }
        return out.stream().filter(x -> !x.isBlank()).toList();
    }

    /** Each clause's keyword: its start and the start of its body, in the order found (outside quotes and brackets, as a whole word). */
    private static Map<String, int[]> clauses(String text) {
        Map<String, int[]> out = new LinkedHashMap<>();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                if (ch == '\\') {
                    i++;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                continue;
            }
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
                continue;
            }
            if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
                continue;
            }
            if (depth != 0 || (i > 0 && !Character.isWhitespace(text.charAt(i - 1)))) {
                continue;
            }
            for (String c : CLAUSES) {
                int end = i + c.length();
                if (text.regionMatches(true, i, c, 0, c.length()) && end < text.length() && Character.isWhitespace(text.charAt(end))) {
                    if (out.containsKey(c)) {
                        throw new Refused(line(text, i), "'" + c + "' comes once in a table statement");
                    }
                    out.put(c, new int[] {i, end});
                    i = end - 1;
                    break;
                }
            }
        }
        return out;
    }

    private static String body(String text, Map<String, int[]> at, String clause) {
        int start = at.get(clause)[1];
        int end = text.length();
        for (int[] other : at.values()) {
            if (other[0] > at.get(clause)[0] && other[0] < end) {
                end = other[0];
            }
        }
        return text.substring(start, end).strip();
    }

    private static int line(String text, int offset) {
        int n = 1;
        for (int i = 0; i < Math.min(offset, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** Comment lines (# ...) blanked, the line count kept so a problem's line is the line typed. */
    private static String stripComments(String s) {
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            b.append(line.strip().startsWith("#") ? "" : line).append('\n');
        }
        return b.toString();
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o).strip();
    }
}
