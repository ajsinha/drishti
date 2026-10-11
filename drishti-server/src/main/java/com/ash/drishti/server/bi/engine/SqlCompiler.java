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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.ExprParser;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.dataset.Resolver;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles a visual query to one parameterised SELECT (RUPAKA.md section 7). Names come from the dataset, quoted; values are bound; the
 * reader's row rules are added as predicates the query cannot remove; masked columns are replaced by the mask and never grouped on. Two steps:
 * {@link #plan} resolves and checks everything against the model (and is what the layer router looks at), {@link #compile} writes the SQL.
 *
 * <p>Joins: the base table is the one the measures aggregate; every other table the query touches must be reachable from it through
 * many-to-one relationships (a trade to its desk), so a measure is never counted twice. A query with no measure (a list of values) may join
 * either way and returns distinct rows. Measures aggregating two unrelated tables in one query are refused (DRS-9020).
 */
public final class SqlCompiler {

    /** Who is asking: the reader's roles and attributes for row rules, and which columns are masked for them. */
    public record Reader(boolean enforceRows, Set<String> roles, Map<String, List<Object>> user, Masks masks) {}

    /** Whether a column reads masked for the reader. */
    @FunctionalInterface
    public interface Masks {
        boolean masked(String table, String column);

        Masks NONE = (t, c) -> false;
    }

    /** The FROM item of one table: SQL, including its alias, and the parameters inside it. */
    public record Source(String sql, List<Object> params) {}

    /** One output column. */
    public record Out(String name, boolean measure, boolean masked, String table) {}

    public record Field(String text, Resolver.ColRef ref, boolean masked) {}

    public record Use(String name, Node tree, boolean masked) {}

    public record FilterUse(QueryRequest.Filter filter, Resolver.ColRef ref, Dataset.Measure measure) {}

    /** A relationship followed from one table to another: {@code from} the table already in the query. */
    public record Join(String fromTable, String fromColumn, String toTable, String toColumn) {}

    /** The query resolved against the model. */
    public record Plan(Dataset dataset, QueryRequest query, List<Field> fields, List<Use> measures, List<FilterUse> filters, List<Node> rules, boolean denied,
            Set<String> tables, String base, List<Join> joins) {

        /** The distinct columns of {@code table} the query reads (dimensions, filters, rules, measures), for rollup routing. */
        public Set<String> columnsOf(String table) {
            Set<String> cols = new LinkedHashSet<>();
            fields.forEach(f -> take(f.ref(), table, cols));
            filters.forEach(f -> take(f.ref(), table, cols));
            return cols;
        }

        private static void take(Resolver.ColRef r, String table, Set<String> into) {
            if (r != null && r.table().equals(table) && r.column() != null) {
                into.add(r.column());
            }
        }

        public List<String> measureNames() {
            return measures.stream().map(Use::name).toList();
        }

        public List<String> ruleColumns(Dataset ds) {
            List<String> cols = new ArrayList<>();
            Resolver r = new Resolver(ds);
            for (Node rule : rules) {
                Node.walk(rule, n -> {
                    if (n instanceof Node.Ref ref) {
                        Resolver.ColRef c = r.resolve(ref.parts(), null);
                        cols.add(c.table() + "." + c.column());
                    }
                });
            }
            return cols;
        }
    }

    /** The SQL, its parameters in order, and the output columns. */
    public record Compiled(String sql, List<Object> params, List<Out> columns, Set<String> tables, String base, List<String> notes) {}

    private final Dataset ds;
    private final Resolver resolver;
    private final Dialect dialect;

    public SqlCompiler(Dataset ds, Dialect dialect) {
        this.ds = ds;
        this.resolver = new Resolver(ds);
        this.dialect = dialect;
    }

    // ---- plan --------------------------------------------------------------------------------------------------------

    public Plan plan(QueryRequest q, Reader reader) {
        List<Field> fields = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String f : q.fields()) {
            if (!seen.add(f)) {
                throw invalid("field '" + f + "' is listed twice");
            }
            Resolver.ColRef ref = resolve(f);
            if (ref.column() == null) {
                throw invalid("'" + f + "' is a table; a field is table.column");
            }
            fields.add(new Field(f, ref, reader.masks().masked(ref.table(), ref.column())));
        }
        List<Use> measures = new ArrayList<>();
        Set<String> tables = new LinkedHashSet<>();
        Set<String> aggTables = new LinkedHashSet<>();
        fields.forEach(f -> tables.add(f.ref().table()));
        for (String name : q.measures()) {
            Dataset.Measure m = ds.measures().get(name);
            if (m == null) {
                throw invalid("no measure '" + name + "' in this dataset (measures: " + String.join(", ", ds.measures().keySet()) + ")");
            }
            if (m.expr() == null) {
                throw new DrishtiException(ErrorCode.DATASET_NOT_BUILT, "measure '" + name + "' is a Python measure (" + m.python() + "); Python measures are stored and not run yet");
            }
            SqlEmitter probe = new SqlEmitter(ds, dialect, "", null, reader.user());
            probe.emit(new Node.MeasureRef(name), false);
            aggTables.addAll(probe.tables());
            boolean masked = columnsOfMeasure(name).stream().anyMatch(c -> c.column() != null && reader.masks().masked(c.table(), c.column()));
            measures.add(new Use(name, new Node.MeasureRef(name), masked));
        }
        tables.addAll(aggTables);
        List<FilterUse> filters = new ArrayList<>();
        for (QueryRequest.Filter f : q.filters()) {
            Dataset.Measure m = ds.measures().get(f.field());
            if (m != null && m.expr() != null && !f.field().contains(".")) {
                filters.add(new FilterUse(f, null, m));
                SqlEmitter probe = new SqlEmitter(ds, dialect, "", null, reader.user());
                probe.emit(new Node.MeasureRef(f.field()), false);
                aggTables.addAll(probe.tables());
                tables.addAll(probe.tables());
                continue;
            }
            Resolver.ColRef ref = resolve(f.field());
            if (ref.column() == null) {
                throw invalid("'" + f.field() + "' is a table; filter on table.column");
            }
            if (reader.masks().masked(ref.table(), ref.column())) {
                throw new DrishtiException(ErrorCode.QUERY_MASKED, "'" + f.field() + "' is masked for you: it cannot be filtered on");
            }
            tables.add(ref.table());
            filters.add(new FilterUse(f, ref, null));
        }
        List<Node> rules = new ArrayList<>();
        boolean denied = false;
        if (reader.enforceRows() && !ds.security().rows().isEmpty()) {
            for (Dataset.RowRule r : ds.security().rows()) {
                if (reader.roles().contains(r.role())) {
                    Node n = ExprParser.parse(r.where());
                    rules.add(n);
                    Node.walk(n, x -> {
                        if (x instanceof Node.Ref ref) {
                            tables.add(resolver.resolve(ref.parts(), null).table());
                        }
                    });
                }
            }
            denied = rules.isEmpty();
        }
        if (tables.isEmpty()) {
            throw invalid("the query names no table");
        }
        return join(new Plan(ds, q, fields, measures, filters, rules, denied, tables, null, List.of()), aggTables);
    }

    private List<Resolver.ColRef> columnsOfMeasure(String name) {
        List<Resolver.ColRef> out = new ArrayList<>();
        collect(new Node.MeasureRef(name), out, new LinkedHashSet<>());
        return out;
    }

    private void collect(Node n, List<Resolver.ColRef> out, Set<String> trail) {
        Node.walk(n, x -> {
            if (x instanceof Node.Ref r) {
                out.add(resolver.resolve(r.parts(), null));
            } else if (x instanceof Node.MeasureRef m && trail.add(m.name())) {
                Dataset.Measure meas = ds.measures().get(m.name());
                if (meas != null && meas.expr() != null) {
                    collect(ExprParser.parse(meas.expr()), out, trail);
                }
            }
        });
    }

    private Resolver.ColRef resolve(String text) {
        try {
            return resolver.resolve(List.of(text.split("\\.", -1)), null);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
    }

    // ---- join planning -----------------------------------------------------------------------------------------------

    /** A relationship as an edge the query may follow: from the many side to the one side. */
    private record Edge(String from, String fromColumn, String to, String toColumn, boolean oneToOne) {}

    private List<Edge> edges() {
        List<Edge> out = new ArrayList<>();
        for (Dataset.Relationship r : ds.relationships()) {
            if (!r.active()) {
                continue;
            }
            if ("one-to-many".equals(r.cardinality())) {
                out.add(new Edge(r.toTable(), r.toColumn(), r.fromTable(), r.fromColumn(), false));
            } else if ("one-to-one".equals(r.cardinality())) {
                out.add(new Edge(r.fromTable(), r.fromColumn(), r.toTable(), r.toColumn(), true));
                out.add(new Edge(r.toTable(), r.toColumn(), r.fromTable(), r.fromColumn(), true));
            } else {
                out.add(new Edge(r.fromTable(), r.fromColumn(), r.toTable(), r.toColumn(), false));
            }
        }
        return out;
    }

    private Plan join(Plan p, Set<String> aggTables) {
        Set<String> tables = p.tables();
        List<Edge> edges = edges();
        boolean measures = !p.measures().isEmpty() || p.filters().stream().anyMatch(f -> f.measure() != null);
        List<String> candidates = new ArrayList<>(measures ? aggTables : tables);
        if (candidates.isEmpty()) {
            candidates.addAll(tables);
        }
        for (String base : candidates) {
            List<Join> joins = tree(base, tables, edges, true);
            if (joins != null) {
                return new Plan(p.dataset(), p.query(), p.fields(), p.measures(), p.filters(), p.rules(), p.denied(), allOf(base, joins), base, joins);
            }
        }
        if (!measures) {
            String base = tables.iterator().next();
            List<Join> joins = tree(base, tables, edges, false);
            if (joins != null) {
                return new Plan(p.dataset(), p.query(), p.fields(), p.measures(), p.filters(), p.rules(), p.denied(), allOf(base, joins), base, joins);
            }
        }
        Set<String> ruleTables = new LinkedHashSet<>();
        for (Node rule : p.rules()) {
            Node.walk(rule, x -> {
                if (x instanceof Node.Ref ref) {
                    ruleTables.add(resolver.resolve(ref.parts(), null).table());
                }
            });
        }
        String rules = ruleTables.isEmpty() ? "" : "; your row rule reads " + ruleTables + " and is applied to every query you run, so the table the measures aggregate must reach it many-to-one";
        throw invalid(measures && aggTables.size() > 1 && candidates.stream().noneMatch(c -> tree(c, aggTables, edges, true) != null)
                ? "the measures aggregate " + aggTables + ", which no many-to-one path connects: ask for them in separate queries"
                : "the model has no relationship path joining " + tables + (measures ? " to the measures' table without repeating its rows (each join must go many-to-one)" : "") + rules);
    }

    private static Set<String> allOf(String base, List<Join> joins) {
        Set<String> all = new LinkedHashSet<>();
        all.add(base);
        joins.forEach(j -> all.add(j.toTable()));
        return all;
    }

    /** A tree of joins from {@code base} reaching every needed table; null when some table is unreachable. Intermediate tables are added as needed. */
    private static List<Join> tree(String base, Set<String> needed, List<Edge> edges, boolean forwardOnly) {
        Set<String> inTree = new LinkedHashSet<>(List.of(base));
        List<Join> joins = new ArrayList<>();
        for (String target : needed) {
            if (inTree.contains(target)) {
                continue;
            }
            List<Join> path = path(base, target, edges, forwardOnly, inTree);
            if (path == null) {
                return null;
            }
            for (Join j : path) {
                if (inTree.add(j.toTable())) {
                    joins.add(j);
                }
            }
        }
        return joins;
    }

    private static List<Join> path(String base, String target, List<Edge> edges, boolean forwardOnly, Set<String> inTree) {
        // breadth first from every table already in the tree
        Map<String, Join> via = new LinkedHashMap<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(inTree);
        Set<String> seen = new LinkedHashSet<>(inTree);
        while (!queue.isEmpty()) {
            String at = queue.poll();
            for (Edge e : edges) {
                String next = null;
                Join j = null;
                if (e.from().equals(at)) {
                    next = e.to();
                    j = new Join(e.from(), e.fromColumn(), e.to(), e.toColumn());
                } else if (!forwardOnly && e.to().equals(at)) {
                    next = e.from();
                    j = new Join(e.to(), e.toColumn(), e.from(), e.fromColumn());
                }
                if (next != null && seen.add(next)) {
                    via.put(next, j);
                    if (next.equals(target)) {
                        java.util.LinkedList<Join> path = new java.util.LinkedList<>();
                        String cur = target;
                        while (!inTree.contains(cur)) {
                            Join step = via.get(cur);
                            path.addFirst(step);
                            cur = step.fromTable();
                        }
                        return path;
                    }
                    queue.add(next);
                }
            }
        }
        return null;
    }

    // ---- compile -----------------------------------------------------------------------------------------------------

    /**
     * Writes the SELECT.
     *
     * @param sources the FROM item of each table of the plan
     * @param rowCap the most rows to ask for (the caller passes one more than its limit to see an overflow)
     * @param components for a rollup, the stored column of each component; null otherwise
     */
    public Compiled compile(Plan p, Reader reader, Map<String, Source> sources, int rowCap, Map<String, String> components) {
        QueryRequest q = p.query();
        SqlEmitter select = new SqlEmitter(ds, dialect, p.base(), components, reader.user());
        SqlEmitter where = new SqlEmitter(ds, dialect, p.base(), null, reader.user());
        SqlEmitter having = new SqlEmitter(ds, dialect, p.base(), components, reader.user());
        List<String> items = new ArrayList<>();
        List<String> group = new ArrayList<>();
        List<Out> outs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, String> aliasSql = new LinkedHashMap<>();
        for (Field f : p.fields()) {
            Out out = new Out(f.text(), false, f.masked(), f.ref().table());
            outs.add(out);
            String expr;
            if (f.masked()) {
                select.params().add(DataNode.MASK);
                expr = "CAST(? AS " + dialect.textType() + ")";
                notes.add(f.text() + " is masked");
            } else {
                expr = select.column(f.ref());
                group.add(expr);
            }
            items.add(expr + " AS " + Dialect.quote(f.text()));
            aliasSql.put(f.text(), f.masked() ? null : expr);
        }
        for (Use m : p.measures()) {
            outs.add(new Out(m.name(), true, m.masked(), null));
            if (m.masked()) {
                items.add("CAST(NULL AS " + dialect.doubleType() + ") AS " + Dialect.quote(m.name()));
                notes.add("measure " + m.name() + " is masked");
            } else {
                items.add(select.emit(m.tree(), false) + " AS " + Dialect.quote(m.name()));
            }
        }
        // FROM and joins
        List<Object> fromParams = new ArrayList<>();
        StringBuilder from = new StringBuilder();
        Source base = sources.get(p.base());
        from.append(base.sql());
        fromParams.addAll(base.params());
        for (Join j : p.joins()) {
            Source s = sources.get(j.toTable());
            from.append(" LEFT JOIN ").append(s.sql()).append(" ON ").append(Dialect.quote(j.fromTable())).append('.').append(Dialect.quote(j.fromColumn()))
                    .append(" = ").append(Dialect.quote(j.toTable())).append('.').append(Dialect.quote(j.toColumn()));
            fromParams.addAll(s.params());
        }
        // WHERE: filters, then the row rules
        List<String> preds = new ArrayList<>();
        for (FilterUse f : p.filters()) {
            if (f.measure() != null) {
                continue;
            }
            preds.add(filter(where, f.filter(), f.ref()));
        }
        if (p.denied()) {
            preds.add("1 = 0");
            notes.add("no row rule of this dataset covers your roles: no rows");
        } else if (!p.rules().isEmpty()) {
            List<String> ors = new ArrayList<>();
            for (Node rule : p.rules()) {
                ors.add(where.emit(rule, false));
            }
            preds.add("(" + String.join(" OR ", ors) + ")");
        }
        List<String> havings = new ArrayList<>();
        for (FilterUse f : p.filters()) {
            if (f.measure() != null) {
                havings.add(measureFilter(having, f.filter()));
            }
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        if (p.measures().isEmpty() && !p.fields().isEmpty()) {
            sql.append("DISTINCT ");
        }
        sql.append(String.join(", ", items)).append(" FROM ").append(from);
        if (!preds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", preds));
        }
        if (!p.measures().isEmpty() && !group.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", group));
        }
        if (!havings.isEmpty()) {
            sql.append(" HAVING ").append(String.join(" AND ", havings));
        }
        List<String> order = new ArrayList<>();
        for (QueryRequest.Sort s : q.orderBy()) {
            Out o = outs.stream().filter(x -> x.name().equals(s.by())).findFirst().orElseThrow(
                    () -> invalid("cannot sort by '" + s.by() + "': it is not one of the query's fields or measures"));
            if (o.masked()) {
                throw new DrishtiException(ErrorCode.QUERY_MASKED, "'" + s.by() + "' is masked for you: it cannot be sorted on");
            }
            order.add(Dialect.quote(o.name()) + (s.desc() ? " DESC" : " ASC") + " NULLS LAST");
        }
        if (!order.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", order));
        } else {
            List<String> defaults = p.fields().stream().filter(f -> !f.masked()).map(f -> Dialect.quote(f.text()) + " ASC").toList();
            if (!defaults.isEmpty()) {
                sql.append(" ORDER BY ").append(String.join(", ", defaults));
            }
        }
        sql.append(" LIMIT ").append(Math.max(1, rowCap));
        List<Object> params = new ArrayList<>(select.params());
        params.addAll(fromParams);
        params.addAll(where.params());
        params.addAll(having.params());
        return new Compiled(sql.toString(), params, outs, p.tables(), p.base(), notes);
    }

    private String filter(SqlEmitter e, QueryRequest.Filter f, Resolver.ColRef ref) {
        String col = e.column(ref);
        String type = ref.part() != null ? "integer" : resolver.column(ref.table(), ref.column()).type();
        List<Object> v = f.values();
        switch (f.op()) {
            case "is-null" -> {
                return "(" + col + " IS NULL)";
            }
            case "not-null" -> {
                return "(" + col + " IS NOT NULL)";
            }
            case "in", "not-in" -> {
                if (v.isEmpty()) {
                    return "in".equals(f.op()) ? "1 = 0" : "1 = 1";
                }
                List<String> marks = new ArrayList<>();
                for (Object x : v) {
                    marks.add(bind(e, type, x, f.field()));
                }
                return "(" + col + ("in".equals(f.op()) ? " IN (" : " NOT IN (") + String.join(", ", marks) + "))";
            }
            case "between" -> {
                if (v.size() != 2) {
                    throw invalid("between takes two values: [low, high]");
                }
                String lo = bind(e, type, v.get(0), f.field());
                return "(" + col + " BETWEEN " + lo + " AND " + bind(e, type, v.get(1), f.field()) + ")";
            }
            case "contains", "starts-with" -> {
                if (v.size() != 1 || !"string".equals(type)) {
                    throw invalid(f.op() + " takes one text value on a text column");
                }
                e.params().add(String.valueOf(v.get(0)));
                return "contains".equals(f.op()) ? "(position(lower(?) IN lower(" + col + ")) > 0)" : "starts_with(lower(" + col + "), lower(?))";
            }
            default -> {
                if (v.size() != 1) {
                    throw invalid(f.op() + " takes one value");
                }
                String op = switch (f.op()) {
                    case "eq" -> "=";
                    case "ne" -> "<>";
                    case "lt" -> "<";
                    case "le" -> "<=";
                    case "gt" -> ">";
                    default -> ">=";
                };
                return "(" + col + " " + op + " " + bind(e, type, v.get(0), f.field()) + ")";
            }
        }
    }

    /** Binds a value, converted to the column's type; the SQL text is only ever {@code ?} or {@code CAST(? AS DATE)}. */
    private String bind(SqlEmitter e, String type, Object value, String field) {
        switch (type) {
            case "integer", "decimal" -> {
                if (value instanceof Number n) {
                    e.params().add(value instanceof Double ? (Object) n.doubleValue() : (Object) n.longValue());
                } else {
                    try {
                        e.params().add(Double.parseDouble(String.valueOf(value)));
                    } catch (NumberFormatException x) {
                        throw invalid("'" + value + "' is not a number, and '" + field + "' is " + type);
                    }
                }
                return "?";
            }
            case "date" -> {
                try {
                    e.params().add(LocalDate.parse(String.valueOf(value)).toString());
                } catch (RuntimeException x) {
                    throw invalid("'" + value + "' is not a date (yyyy-mm-dd), and '" + field + "' is a date");
                }
                return "CAST(? AS DATE)";
            }
            case "timestamp" -> {
                e.params().add(String.valueOf(value));
                return "CAST(? AS TIMESTAMP)";
            }
            case "boolean" -> {
                e.params().add(value instanceof Boolean ? value : Boolean.parseBoolean(String.valueOf(value)));
                return "?";
            }
            default -> {
                e.params().add(String.valueOf(value));
                return "?";
            }
        }
    }

    private String measureFilter(SqlEmitter e, QueryRequest.Filter f) {
        String expr = e.emit(new Node.MeasureRef(f.field()), false);
        List<Object> v = f.values();
        if (v.size() != 1 || !(v.get(0) instanceof Number n)) {
            throw invalid("a filter on a measure compares it to one number");
        }
        String op = switch (f.op()) {
            case "eq" -> "=";
            case "ne" -> "<>";
            case "lt" -> "<";
            case "le" -> "<=";
            case "gt" -> ">";
            case "ge" -> ">=";
            default -> throw invalid("a filter on a measure uses eq, ne, lt, le, gt or ge");
        };
        e.params().add(n.doubleValue());
        return "(" + expr + " " + op + " ?)";
    }

    private static DrishtiException invalid(String message) {
        return new DrishtiException(ErrorCode.QUERY_INVALID, message);
    }
}
