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
package com.ash.drishti.engine.source.derived;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One derived kind: the entities of another kind ({@code from}) grouped by an expression ({@code group-by}), each group
 * one entity whose id is the group's key, with fields aggregated over its members. Declared in a pack's connector
 * settings:
 *
 * <pre>
 * book-pnl:
 *   from: trade
 *   group-by: $.book
 *   where: $.mtm != null          # optional: which members count
 *   id-field: book                # a field holding the key besides id (a link to the book, here)
 *   members: trades               # the field listing the members' ids (default: members)
 *   fields:
 *     tradeCount: count
 *     mtm: sum $.mtm
 *     largest: max $.notional
 *     currencies: distinct $.currency
 *   max-members: 1000             # members listed (and rows) at most; memberCount and the aggregates count all
 *   rows:                         # optional: one row per member, for a table (in the field `rows`, or rows-field)
 *     trade: $.tradeId
 *     mtm: $.mtm
 * </pre>
 *
 * Aggregates: {@code count}, {@code sum}, {@code avg}, {@code min}, {@code max}, {@code distinct} (sorted values),
 * {@code first} (the first member's value, members in id order). Numbers that are missing or not numbers are skipped.
 */
public final class DerivedKind {

    enum Op {
        COUNT, SUM, AVG, MIN, MAX, DISTINCT, FIRST
    }

    record Field(String name, Op op, Expr expr, String source) {}

    private final String kind;
    private final String from;
    private final Expr groupBy;
    private final String groupSource;
    private final Expr where;
    private final String idField;
    private final String membersField;
    private final List<Field> fields;
    private final Map<String, Expr> rowColumns;
    private final String rowsField;
    private final int maxMembers;
    private final Formats formats;
    /** What {@link #compact} computes from a member (source kind, fields, row columns): kinds with the same signature share it. */
    private String signature;

    private DerivedKind(String kind, String from, Expr groupBy, String groupSource, Expr where, String idField, String membersField, List<Field> fields,
            Map<String, Expr> rowColumns, String rowsField, int maxMembers, Formats formats) {
        this.kind = kind;
        this.from = from;
        this.groupBy = groupBy;
        this.groupSource = groupSource;
        this.where = where;
        this.idField = idField;
        this.membersField = membersField;
        this.fields = fields;
        this.rowColumns = rowColumns;
        this.rowsField = rowsField;
        this.maxMembers = maxMembers;
        this.formats = formats;
    }

    /** The derived kinds in a connector's settings ({@code <kind>.from} names one), each checked: a mistake fails the start. */
    static List<DerivedKind> parse(Map<String, String> settings, ElCompiler el, Formats formats) {
        List<DerivedKind> out = new ArrayList<>();
        for (String key : settings.keySet()) {
            if (!key.endsWith(".from")) {
                continue;
            }
            String kind = key.substring(0, key.length() - ".from".length());
            String p = kind + ".";
            String from = settings.get(key).trim();
            if (from.equals(kind)) {
                throw new IllegalArgumentException(kind + ": a derived kind cannot be built from itself");
            }
            String group = settings.get(p + "group-by");
            if (group == null || group.isBlank()) {
                throw new IllegalArgumentException(kind + ": group-by is required (an expression over a " + from + ", such as $.book)");
            }
            List<Field> fields = new ArrayList<>();
            String fp = p + "fields.";
            settings.forEach((k, v) -> {
                if (k.startsWith(fp)) {
                    fields.add(field(kind, k.substring(fp.length()), v, el));
                }
            });
            fields.sort(java.util.Comparator.comparing(Field::name));
            Map<String, Expr> rowColumns = new LinkedHashMap<>();
            String rp = p + "rows.";
            settings.forEach((k, v) -> {
                if (k.startsWith(rp)) {
                    rowColumns.put(k.substring(rp.length()), compile(kind, "rows." + k.substring(rp.length()), v, el));
                }
            });
            String where = settings.get(p + "where");
            DerivedKind made = new DerivedKind(kind, from, compile(kind, "group-by", group, el), group.trim(), where == null || where.isBlank() ? null : compile(kind, "where", where, el),
                    settings.getOrDefault(p + "id-field", "id"), settings.getOrDefault(p + "members", "members"), List.copyOf(fields),
                    rowColumns, settings.getOrDefault(p + "rows-field", "rows"), Integer.parseInt(settings.getOrDefault(p + "max-members", "1000")),
                    formats);
            // kinds that read the same fields and row columns of the same source share one compact copy of each member
            StringBuilder sig = new StringBuilder(from).append('|');
            fields.forEach(f -> sig.append(f.name()).append('=').append(f.source()).append(';'));
            new java.util.TreeMap<>(settings).forEach((k, v) -> {
                if (k.startsWith(rp)) {
                    sig.append(k.substring(rp.length())).append('=').append(v).append(';');
                }
            });
            made.signature = sig.toString();
            out.add(made);
        }
        return out;
    }

    private static Field field(String kind, String name, String spec, ElCompiler el) {
        String s = spec.trim();
        int space = s.indexOf(' ');
        String opName = (space < 0 ? s : s.substring(0, space)).toUpperCase(Locale.ROOT);
        Op op;
        try {
            op = Op.valueOf(opName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(kind + ".fields." + name + ": '" + s + "' must start with count, sum, avg, min, max, distinct or first");
        }
        String expr = space < 0 ? null : s.substring(space + 1).trim();
        if (op != Op.COUNT && (expr == null || expr.isEmpty())) {
            throw new IllegalArgumentException(kind + ".fields." + name + ": " + opName.toLowerCase(Locale.ROOT) + " needs an expression, such as $.mtm");
        }
        return new Field(name, op, expr == null || expr.isEmpty() ? null : compile(kind, "fields." + name, expr, el), s);
    }

    private static Expr compile(String kind, String what, String src, ElCompiler el) {
        try {
            return el.compile(src);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(kind + "." + what + ": '" + src + "' is not an expression: " + e.getMessage(), e);
        }
    }

    public String kind() {
        return kind;
    }

    String groupBySource() {
        return groupSource;
    }

    /** The formula of an aggregate field as the pack wrote it ({@code sum $.mtm}), or empty when the kind has no such field. */
    public java.util.Optional<String> formula(String field) {
        return fields.stream().filter(f -> f.name().equals(field)).map(Field::source).findFirst();
    }

    /**
     * What the field means, written from its definition: "Sum of mtm over the trades grouped into this desk", with the formula.
     * Used by the explanation of a page when no pack author wrote an entry (docs/architecture/CONTEXT_HELP.md, layer 2).
     */
    public java.util.Optional<com.ash.drishti.api.SourcePlugin.FieldNote> describe(String field) {
        return fields.stream().filter(f -> f.name().equals(field)).findFirst().map(f -> {
            String what = switch (f.op()) {
                case COUNT -> "Number of " + from + " records";
                case SUM -> "Sum of " + operand(f) + " over the " + from + " records";
                case AVG -> "Average of " + operand(f) + " over the " + from + " records";
                case MIN -> "Smallest " + operand(f) + " among the " + from + " records";
                case MAX -> "Largest " + operand(f) + " among the " + from + " records";
                case DISTINCT -> "The different values of " + operand(f) + " among the " + from + " records";
                case FIRST -> operand(f) + " of the first " + from + " record";
            };
            String means = what + " grouped into this " + kind + (where == null ? "" : " (only those where the pack's filter holds)") + ".";
            return new com.ash.drishti.api.SourcePlugin.FieldNote(means, f.source() + ", grouped by " + groupSource, "derived:" + kind + ".fields." + field);
        });
    }

    private static String operand(Field f) {
        int space = f.source().indexOf(' ');
        String e = space < 0 ? "" : f.source().substring(space + 1).trim();
        String p = plainPath(e);
        return p != null ? p : e;
    }

    public String from() {
        return from;
    }

    /** The group a member falls in, or null when it is filtered out or has no key. */
    String keyOf(DataNode member) {
        EvalContext c = EvalContext.of(member, formats);
        if (where != null && !Values.truthy(safe(where, c))) {
            return null;
        }
        Object k = Values.simplify(safe(groupBy, c));
        if (Values.isNull(k)) {
            return null;
        }
        String key = Values.text(k).trim();
        return key.isEmpty() ? null : key;
    }

    /** The derived entity for one group: its key, the members' ids (sorted) and each aggregate. */
    DataNode build(String key, Map<String, DataNode> members) {
        List<String> ids = new ArrayList<>(members.keySet());
        java.util.Collections.sort(ids);
        Map<String, Object> aggregates = new LinkedHashMap<>();
        for (Field f : fields) {
            aggregates.put(f.name(), aggregate(f, ids, id -> Values.simplify(safe(f.expr(), EvalContext.of(members.get(id), formats)))));
        }
        return assemble(key, ids, aggregates, members::get);
    }

    /**
     * A member reduced to what this kind reads of it: each field's and each row column's value, computed once when the
     * member is read, so a whole day of large documents is never held (only {@link #buildCompact} reads it).
     */
    DataNode compact(DataNode doc, java.time.LocalDate today) {
        EvalContext c = EvalContext.of(doc, formats).withToday(today);
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            if (f.expr() != null) {
                out.put(FIELD + i, plain(Values.simplify(safe(f.expr(), c))));
            }
        }
        rowColumns.forEach((col, e) -> out.put(ROW + col, plain(Values.simplify(safe(e, c)))));
        return DataNode.of(out);
    }

    // ---- pages kept on disk (store: disk): the heap holds each group's totals, the disk each member's row -------------------

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** A new, empty set of totals for one group. */
    Totals totals() {
        return new Totals();
    }

    /**
     * One group's totals, added to member by member in any order: what the page's fields need (count, sums, extremes, distinct
     * values, the first member's value by id), never the members themselves. The same values {@link #build} computes.
     */
    final class Totals {
        long count;
        private final double[] sum = new double[fields.size()];
        private final int[] n = new int[fields.size()];
        private final Double[] best = new Double[fields.size()];
        private final List<Set<String>> distinct = new ArrayList<>();
        private final String[] firstId = new String[fields.size()];
        private final Object[] first = new Object[fields.size()];

        Totals() {
            for (int i = 0; i < fields.size(); i++) {
                distinct.add(fields.get(i).op() == Op.DISTINCT ? new java.util.TreeSet<>() : null);
            }
        }

        /** Adds a member by its field values ({@link #fieldValues}, worked out once per member and shared by kinds of the same signature). */
        void add(String id, Object[] values) {
            count++;
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                if (f.op() == Op.COUNT) {
                    continue;
                }
                Object v = values[i];
                switch (f.op()) {
                    case FIRST -> {
                        if (firstId[i] == null || id.compareTo(firstId[i]) < 0) {
                            firstId[i] = id;
                            first[i] = plain(v);
                        }
                    }
                    case DISTINCT -> {
                        if (!Values.isNull(v)) {
                            distinct.get(i).add(Values.text(v));
                        }
                    }
                    default -> {
                        if (Values.isNumber(v)) {
                            double d = Values.number(v);
                            sum[i] += d;
                            n[i]++;
                            best[i] = best[i] == null ? d : f.op() == Op.MIN ? Math.min(best[i], d) : Math.max(best[i], d);
                        }
                    }
                }
            }
        }

        Map<String, Object> values() {
            Map<String, Object> out = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                out.put(f.name(), switch (f.op()) {
                    case COUNT -> count;
                    case FIRST -> first[i];
                    case DISTINCT -> List.copyOf(distinct.get(i));
                    case SUM -> Values.normalise(sum[i]);
                    case AVG -> n[i] == 0 ? null : Values.normalise(sum[i] / n[i]);
                    case MIN, MAX -> best[i] == null ? null : Values.normalise(best[i]);
                });
            }
            return out;
        }
    }

    /**
     * A member's value of each field's expression ({@code today()} is {@code today}: the computation's business date), in the
     * order of the fields; null for count.
     */
    Object[] fieldValues(DataNode doc, java.time.LocalDate today) {
        Object[] out = new Object[fields.size()];
        EvalContext c = null;
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            if (f.op() == Op.COUNT) {
                continue;
            }
            if (c == null) {
                c = EvalContext.of(doc, formats).withToday(today);
            }
            out[i] = Values.simplify(safe(f.expr(), c));
        }
        return out;
    }

    /** True when the kind's page has rows (one per listed member). */
    boolean hasRows() {
        return !rowColumns.isEmpty();
    }

    /** A member's row (the row columns' values) as JSON bytes, for the disk. */
    byte[] rowBytes(DataNode doc, java.time.LocalDate today) {
        EvalContext c = EvalContext.of(doc, formats).withToday(today);
        Map<String, Object> row = new LinkedHashMap<>();
        rowColumns.forEach((col, e) -> row.put(col, plain(Values.simplify(safe(e, c)))));
        try {
            return JSON.writeValueAsBytes(row);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("a derived row cannot be written: " + e.getMessage(), e);
        }
    }

    /** The most members a page lists (its rows and member ids). */
    int maxMembers() {
        return maxMembers;
    }

    /**
     * The group's page from its totals and its listed members' ids and rows (read from the disk, ids in order): the same
     * document {@link #build} makes.
     */
    DataNode buildStored(String key, long count, Map<String, Object> totals, List<String> listed, java.util.function.Function<String, byte[]> row) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", key);
        doc.put(idField, key);
        doc.put("derivedFrom", from);
        doc.putAll(totals);
        doc.put("memberCount", count);
        doc.put(membersField, listed);
        if (!rowColumns.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>(listed.size());
            for (String id : listed) {
                byte[] b = row.apply(id);
                Map<String, Object> r = new LinkedHashMap<>();
                if (b != null) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> read = JSON.readValue(b, LinkedHashMap.class);
                        r.putAll(read);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                } else {
                    rowColumns.keySet().forEach(col -> r.put(col, null));
                }
                rows.add(r);
            }
            doc.put(rowsField, rows);
        }
        return DataNode.of(doc);
    }

    /** Kinds with the same signature compute the same {@link #compact} member, so one copy serves them all. */
    String signature() {
        return signature == null ? kind : signature;
    }

    // ---- what Drishti*net's gathering of a group across nodes reads (GroupShares) ------------------------------------------

    /** The number of fields (aggregates) a page has, in their order. */
    int fieldCount() {
        return fields.size();
    }

    /** The name of field {@code i}. */
    String fieldName(int i) {
        return fields.get(i).name();
    }

    /** The aggregate of field {@code i}: count, sum, avg, min, max, distinct or first. */
    String fieldOp(int i) {
        return fields.get(i).op().name().toLowerCase(Locale.ROOT);
    }

    /** True when field {@code i} reads a value of each member (every aggregate but count). */
    boolean fieldReads(int i) {
        return fields.get(i).expr() != null;
    }

    /** The row columns, in their order. */
    Set<String> rowColumnNames() {
        return rowColumns.keySet();
    }

    String membersField() {
        return membersField;
    }

    String rowsField() {
        return rowsField;
    }

    String idField() {
        return idField;
    }

    /** The key a {@link #compact} member holds field {@code i}'s value under. */
    static String fieldKey(int i) {
        return FIELD + i;
    }

    /** The key a {@link #compact} member holds row column {@code col} under. */
    static String rowKey(String col) {
        return ROW + col;
    }

    /** {@link #buildCompact} as a map (Drishti*net marks masked aggregates in it). */
    Map<String, Object> buildCompactMap(String key, Map<String, DataNode> members) {
        return new LinkedHashMap<>(((DataNode.Obj) buildCompact(key, members)).fields());
    }

    /** {@link #build} over members made by {@link #compact}: the same document. */
    DataNode buildCompact(String key, Map<String, DataNode> members) {
        List<String> ids = new ArrayList<>(members.keySet());
        java.util.Collections.sort(ids);
        Map<String, Object> aggregates = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            String name = FIELD + i;
            aggregates.put(fields.get(i).name(), aggregate(fields.get(i), ids, id -> Values.simplify(members.get(id).get(name))));
        }
        Map<String, Object> doc = assembleHead(key, ids, aggregates);
        List<String> listed = listed(ids);
        if (!rowColumns.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>(listed.size());
            for (String id : listed) {
                DataNode m = members.get(id);
                Map<String, Object> row = new LinkedHashMap<>();
                rowColumns.keySet().forEach(col -> {
                    DataNode v = m.get(ROW + col);
                    row.put(col, v.isNull() ? null : v.unwrap());
                });
                rows.add(row);
            }
            doc.put(rowsField, rows);
        }
        return DataNode.of(doc);
    }

    private static final String FIELD = "f";
    private static final String ROW = "r:";

    private static Object plain(Object v) {
        return v instanceof DataNode n ? (n.isNull() ? null : n.unwrap()) : v;
    }

    /** The path a plain expression reads ({@code $.mtm} is {@code mtm}), or null for anything else. */
    static String plainPath(String source) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\$\\.([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)$")
                .matcher(source == null ? "" : source.trim());
        return m.matches() ? m.group(1) : null;
    }

    private static String fieldPath(Field f) {
        int space = f.source().indexOf(' ');
        return space < 0 ? null : plainPath(f.source().substring(space + 1));
    }

    /**
     * Every group from columns. Groups by the key column directly when the key is a plain path and there is no
     * filter; aggregates straight over the column arrays when every field reads a plain path (count, sum $.mtm …);
     * builds small documents only for the members it lists. Otherwise each member becomes a small document of the
     * columns it reads, one group at a time.
     */
    Map<String, DataNode> buildAll(com.ash.drishti.api.ColumnSet c, String groupBySource) {
        String keyPath = where == null ? plainPath(groupBySource) : null;
        List<String> keyPaths = List.copyOf(keyPaths());
        List<String> all = List.copyOf(paths().orElseThrow());
        Map<String, List<Integer>> groups = new java.util.HashMap<>();
        for (int i = 0; i < c.size(); i++) {
            String key;
            if (keyPath != null) {
                Object v = c.value(keyPath, i);
                key = v == null ? null : Values.text(v instanceof Double d ? Values.normalise(d) : v).trim();
                key = key == null || key.isEmpty() ? null : key;
            } else {
                key = keyOf(rowOf(c, i, keyPaths));
            }
            if (key != null) {
                groups.computeIfAbsent(key, x -> new ArrayList<>()).add(i);
            }
        }
        boolean direct = fields.stream().allMatch(f -> f.op() == Op.COUNT || fieldPath(f) != null);
        Map<String, DataNode> out = new java.util.HashMap<>();
        groups.forEach((g, rows) -> {
            rows.sort((a, b) -> c.ids()[a].compareTo(c.ids()[b]));
            List<String> ids = new ArrayList<>(rows.size());
            rows.forEach(i -> ids.add(c.ids()[i]));
            if (!direct) {
                Map<String, DataNode> members = new java.util.HashMap<>(rows.size() * 2);
                rows.forEach(i -> members.put(c.ids()[i], rowOf(c, i, all)));
                out.put(g, build(g, members));
                return;
            }
            Map<String, Object> aggregates = new LinkedHashMap<>();
            for (Field f : fields) {
                aggregates.put(f.name(), f.op() == Op.COUNT ? (Object) (long) rows.size() : columnAggregate(f.op(), fieldPath(f), rows, c));
            }
            Map<String, Integer> rowOfId = new java.util.HashMap<>();
            for (int k = 0; k < Math.min(rows.size(), maxMembers); k++) {
                rowOfId.put(ids.get(k), rows.get(k));
            }
            out.put(g, assemble(g, ids, aggregates, id -> rowOf(c, rowOfId.get(id), all)));
        });
        return out;
    }

    private static Object columnAggregate(Op op, String path, List<Integer> rows, com.ash.drishti.api.ColumnSet c) {
        if (op == Op.FIRST) {
            Object v = c.value(path, rows.get(0));
            return v instanceof Double d ? Values.normalise(d) : v;
        }
        if (op == Op.DISTINCT) {
            java.util.TreeSet<String> d = new java.util.TreeSet<>();
            rows.forEach(i -> {
                Object v = c.value(path, i);
                if (v != null) {
                    d.add(Values.text(v instanceof Double x ? Values.normalise(x) : v));
                }
            });
            return List.copyOf(d);
        }
        double[] nums = c.numeric(path);
        if (nums == null) {
            return op == Op.AVG || op == Op.MIN || op == Op.MAX ? null : Values.normalise(0);    // text column: no numbers
        }
        double total = 0;
        int n = 0;
        double best = Double.NaN;
        for (int i : rows) {
            double v = nums[i];
            if (Double.isNaN(v)) {
                continue;
            }
            total += v;
            n++;
            best = Double.isNaN(best) ? v : op == Op.MIN ? Math.min(best, v) : Math.max(best, v);
        }
        return switch (op) {
            case SUM -> Values.normalise(total);
            case AVG -> n == 0 ? null : Values.normalise(total / n);
            case MIN, MAX -> n == 0 ? null : Values.normalise(best);
            default -> null;
        };
    }

    /** Row {@code i} as a document holding only the given paths. */
    static DataNode rowOf(com.ash.drishti.api.ColumnSet c, int i, List<String> paths) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (String path : paths) {
            Object v = c.value(path, i);
            if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
                v = d.longValue();
            }
            String[] parts = path.split("\\.");
            Map<String, Object> at = root;
            for (int p = 0; p < parts.length - 1; p++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> next = (Map<String, Object>) at.computeIfAbsent(parts[p], x -> new LinkedHashMap<String, Object>());
                at = next;
            }
            at.put(parts[parts.length - 1], v);
        }
        return DataNode.of(root);
    }

    /** The derived document: the key, its aggregates, the member count and the members it lists (with their rows). */
    /** The group's document without its rows: id, aggregates, member count and the listed member ids. */
    private Map<String, Object> assembleHead(String key, List<String> ids, Map<String, Object> aggregates) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", key);                               // the entity's id, always (layouts title a view by it)
        doc.put(idField, key);
        doc.put("derivedFrom", from);
        doc.putAll(aggregates);
        doc.put("memberCount", (long) ids.size());
        doc.put(membersField, listed(ids));
        return doc;
    }

    private List<String> listed(List<String> ids) {
        return ids.size() > maxMembers ? ids.subList(0, maxMembers) : ids;   // a desk of 200,000 trades lists 1,000
    }

    private DataNode assemble(String key, List<String> ids, Map<String, Object> aggregates, java.util.function.Function<String, DataNode> member) {
        Map<String, Object> doc = assembleHead(key, ids, aggregates);
        List<String> listed = listed(ids);
        if (!rowColumns.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>(listed.size());
            for (String id : listed) {
                EvalContext c = EvalContext.of(member.apply(id), formats);
                Map<String, Object> row = new LinkedHashMap<>();
                rowColumns.forEach((col, e) -> {
                    Object v = Values.simplify(safe(e, c));
                    row.put(col, v instanceof DataNode n ? (n.isNull() ? null : n.unwrap()) : v);
                });
                rows.add(row);
            }
            doc.put(rowsField, rows);
        }
        return DataNode.of(doc);
    }

    /**
     * The document paths everything here reads ({@code mtm}, {@code counterparty.id}), or empty when an expression
     * reads anything but plain paths (then members are read as documents).
     */
    java.util.Optional<java.util.Set<String>> paths() {
        java.util.Set<String> raw = new java.util.LinkedHashSet<>();
        groupBy.paths(raw::add);
        if (where != null) {
            where.paths(raw::add);
        }
        fields.stream().filter(f -> f.expr() != null).forEach(f -> f.expr().paths(raw::add));
        rowColumns.values().forEach(e -> e.paths(raw::add));
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String p : raw) {
            if (!p.startsWith("$.") || p.length() < 3 || p.contains("[")) {
                return java.util.Optional.empty();
            }
            out.add(p.substring(2));
        }
        return java.util.Optional.of(out);
    }

    /**
     * The document paths the aggregates and the rows read (not the group key), when they are all plain paths: a member read as a document
     * (a key that is an expression, such as the first leg's index) is kept as just these, not as the whole document, so a large kind is
     * grouped without holding every document.
     */
    java.util.Optional<java.util.Set<String>> memberPaths() {
        java.util.Set<String> raw = new java.util.LinkedHashSet<>();
        fields.stream().filter(f -> f.expr() != null).forEach(f -> f.expr().paths(raw::add));
        rowColumns.values().forEach(e -> e.paths(raw::add));
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String p : raw) {
            if (!p.startsWith("$.") || p.length() < 3 || p.contains("[")) {
                return java.util.Optional.empty();
            }
            out.add(p.substring(2));
        }
        return java.util.Optional.of(out);
    }

    /** A member document cut down to these paths (each value as the document holds it). */
    static DataNode project(DataNode doc, java.util.Set<String> paths) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (String path : paths) {
            DataNode v = doc.at(path);
            if (v.isNull()) {
                continue;
            }
            String[] parts = path.split("\\.");
            Map<String, Object> at = root;
            for (int p = 0; p < parts.length - 1; p++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> next = (Map<String, Object>) at.computeIfAbsent(parts[p], x -> new LinkedHashMap<String, Object>());
                at = next;
            }
            at.put(parts[parts.length - 1], v.unwrap());
        }
        return DataNode.of(root);
    }

    /** The paths the group key and the filter read: enough to place a member in its group. */
    java.util.Set<String> keyPaths() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        groupBy.paths(p -> out.add(p.substring(2)));
        if (where != null) {
            where.paths(p -> out.add(p.substring(2)));
        }
        return out;
    }

    /** One field over the members, {@code value} giving each member's value of the field's expression. */
    private Object aggregate(Field f, List<String> ids, java.util.function.Function<String, Object> value) {
        if (f.op() == Op.COUNT) {
            return (long) ids.size();
        }
        double total = 0;
        int n = 0;
        Double best = null;
        Set<String> distinct = new LinkedHashSet<>();
        for (String id : ids) {
            Object v = value.apply(id);
            if (f.op() == Op.FIRST) {
                return v instanceof DataNode node ? (node.isNull() ? null : node.unwrap()) : v;
            }
            if (f.op() == Op.DISTINCT) {
                if (!Values.isNull(v)) {
                    distinct.add(Values.text(v));
                }
                continue;
            }
            if (!Values.isNumber(v)) {
                continue;
            }
            double d = Values.number(v);
            total += d;
            n++;
            best = best == null ? d : f.op() == Op.MIN ? Math.min(best, d) : Math.max(best, d);
        }
        return switch (f.op()) {
            case SUM -> Values.normalise(total);
            case AVG -> n == 0 ? null : Values.normalise(total / n);
            case MIN, MAX -> best == null ? null : Values.normalise(best);
            case DISTINCT -> distinct.stream().sorted().toList();
            default -> null;
        };
    }

    private static Object safe(Expr e, EvalContext c) {
        try {
            return e.eval(c);
        } catch (RuntimeException ex) {
            return null;                                  // one odd member never spoils the group
        }
    }
}
