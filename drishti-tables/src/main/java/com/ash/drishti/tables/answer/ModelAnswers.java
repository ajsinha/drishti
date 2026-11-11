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
package com.ash.drishti.tables.answer;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.bicompute.lang.Compiler;
import com.ash.drishti.bicompute.lang.Lowered;
import com.ash.drishti.bicompute.lang.RowsEvaluator;
import com.ash.drishti.bicompute.lang.SqlLowering;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.tables.catalog.Catalogue;
import com.ash.drishti.tables.def.ColumnDef;
import com.ash.drishti.tables.def.ModelDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.lang.LateBound;
import com.ash.drishti.tables.lang.Trees;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A model query answered by the table layer (HOW_IT_FITS.md §A28 section 3.5): the model's tables read for the as-of date as the reader
 * may see them (each table's rules for the reader's roles, its masks for a reader without the raw power, the model's own rules), joined
 * many-to-one from each measure's fact along the active relationships, filtered, grouped by the asked fields, and each measure compiled
 * by <b>part A's one compiler</b> ({@link Compiler}: aggregates to the engine's functions, measures of measures as shapes) and evaluated
 * over the group's rows by the engine's {@link RowsEvaluator}; {@code fx} and {@code related} are lowered by the layer ({@link LateBound}).
 *
 * <p>This is the in-memory route (a live table, a sample, a small table). The scan route (the same compiled plan over the materialised
 * Parquet or a connector's scan, {@code ScanEvaluator}) and the measures computed over an answer (context functions, windows, time
 * intelligence, {@code convert}) are refused here by name; they are the switch's.
 */
public final class ModelAnswers {

    /** The rows of a table for a business date: one object per row, its columns by name (built-ins included). */
    @FunctionalInterface
    public interface Rows {
        List<DataNode> rows(TableDef table, LocalDate date);
    }

    /**
     * Who asks.
     *
     * @param roles the reader's roles
     * @param attributes {@code user.x}: name to its values ({@code desks} to a list)
     * @param raw true when the reader has the raw power (masks do not apply)
     */
    public record Reader(Set<String> roles, Map<String, List<Object>> attributes, boolean raw) {}

    /** A filter on a field: {@code eq}, {@code ne}, {@code in}, {@code not-in}, {@code gt}, {@code ge}, {@code lt}, {@code le}, {@code null}, {@code not-null}. */
    public record Where(String field, String op, List<Object> values) {}

    /** The question: fields to group by, measures, filters, the as-of date. */
    public record Question(List<String> fields, List<String> measures, List<Where> where, LocalDate asOf) {
        public Question {
            fields = List.copyOf(fields);
            measures = List.copyOf(measures);
            where = where == null ? List.of() : List.copyOf(where);
        }
    }

    /**
     * The answer: the columns (fields, then measures), the rows sorted by their fields, the grand total of each measure, the masked
     * columns the reader saw as masks, and the aliases the reader may see none of.
     */
    public record Answer(List<String> columns, List<List<Object>> rows, List<Object> grand, List<String> masked, List<String> noAccess) {}

    /** A question the layer cannot answer, with its reason and BI's code ({@code DRS-9156} not reachable, {@code DRS-9152} unknown name ...). */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public Refused(String code, String message) {
            super(code + " " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Catalogue catalogue;
    private final Rows source;
    private final LateBound.Fx fx;

    public ModelAnswers(Catalogue catalogue, Rows source, LateBound.Fx fx) {
        this.catalogue = catalogue;
        this.source = source;
        this.fx = fx;
    }

    public Answer ask(ModelDef m, Question q, Reader who) {
        Map<String, TableDef> tables = new LinkedHashMap<>();
        for (ModelDef.Use u : m.tables().values()) {
            tables.put(u.alias(), catalogue.effective(u.table()).orElseThrow(() -> new Refused("DRS-9609", "no table " + u.table())));
        }
        Set<String> masked = new LinkedHashSet<>();
        List<String> noAccess = new ArrayList<>();
        Map<String, List<DataNode>> visible = new LinkedHashMap<>();
        for (Map.Entry<String, TableDef> e : tables.entrySet()) {
            List<DataNode> rows = readable(e.getKey(), e.getValue(), m, q.asOf(), who, masked);
            if (rows == null) {
                noAccess.add(e.getKey());
                rows = List.of();
            }
            visible.put(e.getKey(), rows);
        }
        Map<String, Lowered> lowered = new LinkedHashMap<>();
        Map<String, LateBound.Bound> bound = new LinkedHashMap<>();
        Compiler compiler = new Compiler(columns(m, tables), SqlLowering.Options.of(q.asOf()));
        List<String> order = order(m, q.measures());
        for (String name : order) {
            ModelDef.Measure measure = m.measures().get(name);
            if (measure == null || measure.expr() == null) {
                throw new Refused("DRS-9152", "no measure '" + name + "' (a Python measure is answered by its runner)");
            }
            if (measure.keys().containsKey("via") || m.extras().containsKey("bridges")) {
                throw new Refused("DRS-9129", "this model uses a bridge or via: the preview here cannot read those: open it in a report, which the query engine answers");
            }
            LateBound.Bound b = LateBound.bind(Trees.parse(measure.expr()), true);
            bound.put(name, b);
            lowered.put(name, compiler.measure(b.expr()));
        }
        for (String f : q.fields()) {
            field(f, tables);
        }
        RowsEvaluator rows = new RowsEvaluator(EvalContext.of(DataNode.missing(), null).withToday(q.asOf()));
        // each measure over the joined rows of its fact; groups are the union of every fact's groups
        Map<String, Map<List<Object>, List<DataNode>>> byMeasureGroups = new LinkedHashMap<>();
        Map<String, List<DataNode>> byMeasureAll = new LinkedHashMap<>();
        Set<List<Object>> groups = new LinkedHashSet<>();
        Map<String, List<DataNode>> joinedByFact = new LinkedHashMap<>();
        for (String name : order) {
            String fact = fact(bound.get(name).expr(), m);
            List<DataNode> joined = joinedByFact.computeIfAbsent(fact == null ? "" : fact, f -> f.isEmpty() ? List.of() : join(f, m, visible));
            for (String field : q.fields()) {
                if (fact != null && !reachable(fact, alias(field), m)) {
                    throw new Refused("DRS-9156", "the field " + field + " is not reachable many-to-one from the fact of " + name + " (" + fact + ")");
                }
            }
            List<DataNode> kept = new ArrayList<>();
            for (DataNode d : joined) {
                DataNode row = withLate(d, bound.get(name), q.asOf());
                if (q.where().stream().allMatch(w -> holds(w, row))) {
                    kept.add(row);
                }
            }
            Map<List<Object>, List<DataNode>> g = new LinkedHashMap<>();
            for (DataNode d : kept) {
                List<Object> key = new ArrayList<>();
                for (String f : q.fields()) {
                    key.add(fieldValue(f, d));
                }
                g.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
            }
            groups.addAll(g.keySet());
            byMeasureGroups.put(name, g);
            byMeasureAll.put(name, kept);
        }
        if (q.fields().isEmpty()) {
            groups.add(List.of());
        }
        List<List<Object>> sorted = new ArrayList<>(groups);
        sorted.sort(Comparator.comparing(Object::toString));
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> key : sorted) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String name : order) {
                List<DataNode> in = q.fields().isEmpty() ? byMeasureAll.get(name) : byMeasureGroups.get(name).getOrDefault(key, List.of());
                values.put(name, value(rows, lowered.get(name), in, values));
            }
            List<Object> row = new ArrayList<>(key);
            q.measures().forEach(n -> row.add(values.get(n)));
            out.add(row);
        }
        Map<String, Object> grandValues = new LinkedHashMap<>();
        for (String name : order) {
            grandValues.put(name, value(rows, lowered.get(name), byMeasureAll.get(name), grandValues));
        }
        List<String> columns = new ArrayList<>(q.fields());
        columns.addAll(q.measures());
        return new Answer(columns, out, q.measures().stream().map(grandValues::get).toList(), List.copyOf(masked), noAccess);
    }

    private static Object value(RowsEvaluator rows, Lowered l, List<DataNode> in, Map<String, Object> measures) {
        try {
            Object v = rows.measure(l, in, measures);
            return Values.masked(v) ? "•••" : v;
        } catch (IllegalArgumentException e) {
            throw new Refused("DRS-9129", e.getMessage() + " (answered over an answer by the scan route)");
        }
    }

    /** The table's rows the reader may see (null: none, no rule names the reader's roles), masks applied, the model's own rules too. */
    private List<DataNode> readable(String alias, TableDef t, ModelDef m, LocalDate date, Reader who, Set<String> maskedOut) {
        List<Expr> modelRules = new ArrayList<>();
        boolean modelNamed = !m.security().rows().containsKey(alias) || m.security().rows().get(alias).isEmpty();
        for (TableDef.Rule r : m.security().rows().getOrDefault(alias, List.of())) {
            if (!Collections.disjoint(r.roles(), who.roles())) {
                modelNamed = true;
                modelRules.add(Trees.parse(r.where()));
            }
        }
        boolean maySeeAll = m.security().maySeeAll().getOrDefault(alias, List.of()).stream().anyMatch(who.roles()::contains);
        TableAccess access = TableAccess.of(t, who, maySeeAll);
        if (access.none() || !modelNamed) {
            return null;
        }
        Set<String> masks = new HashSet<>(access.masks());
        if (!who.raw()) {
            m.security().masked().stream().filter(x -> x.startsWith(alias + ".")).forEach(x -> masks.add(x.substring(alias.length() + 1)));
        }
        masks.forEach(c -> maskedOut.add(alias + "." + c));
        DataNode user = access.user();
        List<DataNode> out = new ArrayList<>();
        for (DataNode row : source.rows(t, date)) {
            if (!access.allows(row)) {
                continue;
            }
            Map<String, DataNode> fields = new LinkedHashMap<>(((DataNode.Obj) row).fields());
            fields.put("user", user);
            Map<String, DataNode> doc = new LinkedHashMap<>();
            doc.put(alias, new DataNode.Obj(fields));
            doc.put("user", user);
            if (!modelRules.isEmpty() && modelRules.stream().noneMatch(r -> Values.truthy(r.eval(EvalContext.of(new DataNode.Obj(doc), null))))) {
                continue;
            }
            Map<String, DataNode> kept = new LinkedHashMap<>(((DataNode.Obj) row).fields());
            masks.forEach(c -> kept.put(c, DataNode.masked()));
            out.add(new DataNode.Obj(kept));
        }
        return out;
    }

    /** The fact's rows, each joined many-to-one to every alias the active relationships reach from it: {@code {fact: row, dim: row, ...}}. */
    private static List<DataNode> join(String fact, ModelDef m, Map<String, List<DataNode>> visible) {
        Map<String, Map<Object, DataNode>> index = new LinkedHashMap<>();
        List<DataNode> out = new ArrayList<>();
        for (DataNode row : visible.getOrDefault(fact, List.of())) {
            Map<String, DataNode> doc = new LinkedHashMap<>();
            doc.put(fact, row);
            Deque<String> todo = new ArrayDeque<>(List.of(fact));
            while (!todo.isEmpty()) {
                String from = todo.pop();
                for (ModelDef.Relationship r : m.relationships()) {
                    if (!r.active() || !r.from().startsWith(from + ".")) {
                        continue;
                    }
                    String to = r.to().substring(0, r.to().indexOf('.'));
                    if (doc.containsKey(to)) {
                        continue;
                    }
                    String toColumn = r.to().substring(to.length() + 1);
                    Map<Object, DataNode> byKey = index.computeIfAbsent(r.to(), k -> {
                        Map<Object, DataNode> x = new LinkedHashMap<>();
                        visible.getOrDefault(to, List.of()).forEach(d -> x.putIfAbsent(Values.simplify(d.get(toColumn)), d));
                        return x;
                    });
                    Object key = Values.simplify(doc.get(from).get(r.from().substring(from.length() + 1)));
                    DataNode match = key == null ? null : byKey.get(key);
                    doc.put(to, match == null ? DataNode.missing() : match);
                    todo.push(to);
                }
            }
            out.add(new DataNode.Obj(doc));
        }
        return out;
    }

    private static boolean reachable(String fact, String alias, ModelDef m) {
        Set<String> seen = new HashSet<>(List.of(fact));
        Deque<String> todo = new ArrayDeque<>(List.of(fact));
        while (!todo.isEmpty()) {
            String from = todo.pop();
            for (ModelDef.Relationship r : m.relationships()) {
                if (r.active() && r.from().startsWith(from + ".")) {
                    String to = r.to().substring(0, r.to().indexOf('.'));
                    if (seen.add(to)) {
                        todo.push(to);
                    }
                }
            }
        }
        return seen.contains(alias);
    }

    /** The fact a measure aggregates: the alias of its aggregates' inputs, or of {@code count(alias)}; a measure of measures has its parts' facts. */
    private static String fact(Expr expr, ModelDef m) {
        Set<String> aliases = new LinkedHashSet<>();
        Trees.walk(expr, n -> {
            String name = Trees.name(n);
            if (name != null && name.contains(".") && m.tables().containsKey(name.substring(0, name.indexOf('.')))) {
                aliases.add(name.substring(0, name.indexOf('.')));
            } else if (name != null && m.tables().containsKey(name)) {
                aliases.add(name);
            }
        });
        for (String a : aliases) {
            if ("fact".equals(m.tables().get(a).role())) {
                return a;
            }
        }
        return aliases.isEmpty() ? null : aliases.iterator().next();
    }

    /** The measures to compute, the ones a measure names first ({@code measure('x')}). */
    private static List<String> order(ModelDef m, List<String> asked) {
        List<String> out = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        for (String a : asked) {
            visit(a, m, out, visiting);
        }
        return out;
    }

    private static void visit(String name, ModelDef m, List<String> out, Set<String> visiting) {
        if (out.contains(name) || !visiting.add(name)) {
            return;
        }
        ModelDef.Measure measure = m.measures().get(name);
        if (measure != null && measure.expr() != null) {
            Trees.walk(Trees.parse(measure.expr()), n -> {
                if (n instanceof Expr.Call c && c.name().equals("measure") && c.args().size() == 1 && c.args().get(0) instanceof Expr.Lit l) {
                    visit(String.valueOf(l.value()), m, out, visiting);
                }
            });
        }
        out.add(name);
    }

    private DataNode withLate(DataNode d, LateBound.Bound b, LocalDate date) {
        if (b.none()) {
            return d;
        }
        Map<String, DataNode> fields = new LinkedHashMap<>(((DataNode.Obj) d).fields());
        fields.putAll(LateBound.values(b, EvalContext.of(d, null), date, fx, LateBound.Lookup.NONE));
        return new DataNode.Obj(fields);
    }

    private static SqlLowering.Columns columns(ModelDef m, Map<String, TableDef> tables) {
        return new SqlLowering.Columns() {
            @Override
            public String sql(List<String> parts) {
                return String.join(".", parts.stream().map(p -> "\"" + p.replace("\"", "\"\"") + "\"").toList());
            }

            @Override
            public boolean date(List<String> parts) {
                TableDef t = parts.size() == 2 ? tables.get(parts.get(0)) : null;
                ColumnDef c = t == null ? null : t.columns().get(parts.get(1));
                return c != null && ("date".equals(c.type()) || "timestamp".equals(c.type()));
            }

            @Override
            public boolean table(String name) {
                return m.tables().containsKey(name);
            }
        };
    }

    private static void field(String f, Map<String, TableDef> tables) {
        String base = withoutPart(f);
        int dot = base.indexOf('.');
        TableDef t = dot < 0 ? null : tables.get(base.substring(0, dot));
        String column = dot < 0 ? null : base.substring(dot + 1);
        if (t == null || !t.columns().containsKey(column) && !TableDef.BUILT_INS.contains(column)) {
            throw new Refused("DRS-9152", "no field " + f);
        }
    }

    private static String alias(String field) {
        return field.substring(0, field.indexOf('.'));
    }

    private static String withoutPart(String f) {
        for (String p : List.of(".year", ".quarter", ".month", ".week", ".day")) {
            if (f.endsWith(p)) {
                return f.substring(0, f.length() - p.length());
            }
        }
        return f;
    }

    private static Object fieldValue(String f, DataNode doc) {
        String base = withoutPart(f);
        Expr e = Trees.parse(base.equals(f) ? f : f.substring(base.length() + 1) + "(" + base + ")");
        Object v = e.eval(EvalContext.of(doc, null));
        return Values.masked(v) ? "•••" : Values.isNull(v) ? null : Values.simplify(v);
    }

    private static boolean holds(Where w, DataNode doc) {
        Object v = fieldValue(w.field(), doc);
        List<Object> vals = w.values();
        Object first = vals.isEmpty() ? null : vals.get(0);
        return switch (w.op()) {
            case "eq" -> v != null && same(v, first);
            case "ne" -> v != null && !same(v, first);
            case "in" -> v != null && vals.stream().anyMatch(x -> same(v, x));
            case "not-in" -> v != null && vals.stream().noneMatch(x -> same(v, x));
            case "null" -> v == null;
            case "not-null" -> v != null;
            case "gt", "ge", "lt", "le" -> {
                if (!(v instanceof Number a) || !(first instanceof Number b)) {
                    yield false;
                }
                int c = Double.compare(a.doubleValue(), b.doubleValue());
                yield switch (w.op()) {
                    case "gt" -> c > 0;
                    case "ge" -> c >= 0;
                    case "lt" -> c < 0;
                    default -> c <= 0;
                };
            }
            default -> throw new Refused("DRS-9155", "the operator " + w.op() + " is not one the layer answers");
        };
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }
        return Objects.equals(String.valueOf(a), String.valueOf(b));
    }
}
