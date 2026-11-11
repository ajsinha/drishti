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
 * Compiles a visual query to one parameterised SELECT (HOW_IT_FITS.md §A28 › Rūpaka: the original design and every phase as built, section 7). Names come from the dataset, quoted; values are bound; the
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

    /** The FROM item of a table the main query does not read, or of any table at other dates: what a filter context's sub-query reads (wave B). */
    @FunctionalInterface
    public interface MoreSources {
        Source of(String table, LocalDate from, LocalDate to);
    }

    /** How deep filter-context sub-queries may nest (HOW_IT_FITS.md §A28 section 3.3.5, rule 2). */
    public static final int MAX_CONTEXT_DEPTH = 3;

    /** One output column. */
    public record Out(String name, boolean measure, boolean masked, String table) {}

    public record Field(String text, Resolver.ColRef ref, boolean masked) {}

    public record Use(String name, Node tree, boolean masked) {}

    public record FilterUse(QueryRequest.Filter filter, Resolver.ColRef ref, Dataset.Measure measure) {}

    /** A relationship followed from one table to another: {@code from} the table already in the query. */
    public record Join(String fromTable, String fromColumn, String toTable, String toColumn, Semi semi) {
        public Join(String fromTable, String fromColumn, String toTable, String toColumn) {
            this(fromTable, fromColumn, toTable, toColumn, null);
        }
    }

    /**
     * A join that must not repeat the base table: {@code through} (a bridge table, null for a both-ways relationship read directly), joined on {@code throughTo},
     * gives the distinct keys {@code throughFrom}, each paired with the far table's asked-for values. Each base row meets one row per distinct value, so a group's
     * sum holds a row once and a total asked without the far table holds it once.
     */
    public record Semi(String through, String throughFrom, String throughTo) {}

    /** Limits a compile to the groups a sub-query keeps: {@code calc(..., by =, having =)}. */
    record Restriction(List<String> fields, String sql, List<Object> params) {
        String predicate(List<Object> out, java.util.function.Function<String, String> column) {
            List<String> on = new ArrayList<>();
            for (String f : fields) {
                on.add(column.apply(f) + " IS NOT DISTINCT FROM " + Dialect.quote("hv") + "." + Dialect.quote(f));
            }
            out.addAll(params);
            return "EXISTS (SELECT 1 FROM (" + sql + ") AS " + Dialect.quote("hv") + " WHERE " + String.join(" AND ", on) + ")";
        }
    }

    /** The query resolved against the model. */
    public record Plan(Dataset dataset, QueryRequest query, List<Field> fields, List<Use> measures, List<FilterUse> filters, List<Node> rules, boolean denied,
            Set<String> tables, String base, List<Join> joins, List<Plan> parts, Map<String, Node> derived) {

        /** A plan with no measure that is worked out over the joined parts. */
        public Plan(Dataset dataset, QueryRequest query, List<Field> fields, List<Use> measures, List<FilterUse> filters, List<Node> rules, boolean denied,
                Set<String> tables, String base, List<Join> joins, List<Plan> parts) {
            this(dataset, query, fields, measures, filters, rules, denied, tables, base, joins, parts, Map.of());
        }

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

        /** True for a query whose measures aggregate unrelated fact tables: each fact is a part, aggregated alone at the shared grain, and the parts are joined. */
        public boolean multiFact() {
            return parts != null;
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
                    } else if (n instanceof Node.Call c && RelatedRows.FUNCTION.equals(c.function()) && c.args().size() == 2
                            && c.args().get(0) instanceof Node.Str self && c.args().get(1) instanceof Node.Str other && ds.tables().containsKey(self.value())
                            && ds.tables().containsKey(other.value())) {
                        RelatedRows.keys(ds, self.value(), other.value()).forEach(k -> cols.add(self.value() + "." + k[0]));   // the keys the rule reads
                    }
                });
            }
            return cols;
        }
    }

    /** The SQL, its parameters in order, and the output columns. */
    public record Compiled(String sql, List<Object> params, List<Out> columns, Set<String> tables, String base, List<String> notes) {}

    private static final String SEMI_KEY = "__semi_key";
    private static final String SEMI_DATE = "__semi_date";

    private final Dataset ds;
    private final Resolver resolver;
    private final Dialect dialect;

    private int depth;
    private Restriction restriction;

    public SqlCompiler(Dataset ds, Dialect dialect) {
        this.ds = ds;
        this.resolver = new Resolver(ds);
        this.dialect = dialect;
    }

    /** This compiler writes a filter context's sub-query {@code d} levels down. */
    SqlCompiler depth(int d) {
        this.depth = d;
        return this;
    }

    /** Restricts this compile's rows to the groups of {@code calc(..., by =, having =)}; the {@code by} fields' tables are planned in. */
    SqlCompiler restrict(Restriction r) {
        this.restriction = r;
        return this;
    }

    /** True when a measure (or one it names) needs a filter context, a balance or a date of wave B. */
    public static boolean usesContext(Dataset ds, String measure) {
        return Contexts.uses(ds, measure);
    }

    /** True when one of the plan's measures needs a filter context or a balance (wave B): no rollup answers it. */
    public static boolean usesContext(Dataset ds, Plan p) {
        return p.measureNames().stream().anyMatch(m -> Contexts.uses(ds, m)) || p.filters().stream().anyMatch(f -> f.measure() != null && Contexts.uses(ds, f.filter().field()));
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
        Map<String, Set<String>> measureTables = new LinkedHashMap<>();
        Set<String> dimTables = new LinkedHashSet<>();
        fields.forEach(f -> {
            tables.add(f.ref().table());
            dimTables.add(f.ref().table());
        });
        for (String name : q.measures()) {
            Dataset.Measure m = ds.measures().get(name);
            if (m == null) {
                throw invalid("no measure '" + name + "' in this dataset (measures: " + String.join(", ", ds.measures().keySet()) + ")");
            }
            if (m.expr() == null) {
                throw new DrishtiException(ErrorCode.DATASET_NOT_BUILT, "measure '" + name + "' is a Python measure (" + m.python() + "); Python measures are run on the server's compute pool python, shown by a python visual, never by a query");
            }
            SqlEmitter probe = new SqlEmitter(ds, dialect, "", null, reader.user()).planning();
            probe.emit(new Node.MeasureRef(name), false);
            aggTables.addAll(probe.tables());
            measureTables.put(name, new LinkedHashSet<>(probe.tables()));
            boolean masked = columnsOfMeasure(name).stream().anyMatch(c -> c.column() != null && reader.masks().masked(c.table(), c.column()));
            measures.add(new Use(name, new Node.MeasureRef(name), masked));
        }
        tables.addAll(aggTables);
        if (restriction != null) {
            restriction.fields().forEach(f -> tables.add(resolve(f).table()));
        }
        Map<String, Node> derived = new LinkedHashMap<>();
        expandMixed(measureTables, derived, reader);
        List<FilterUse> filters = new ArrayList<>();
        for (QueryRequest.Filter f : q.filters()) {
            Dataset.Measure m = ds.measures().get(f.field());
            if (m != null && m.expr() != null && !f.field().contains(".")) {
                filters.add(new FilterUse(f, null, m));
                SqlEmitter probe = new SqlEmitter(ds, dialect, "", null, reader.user()).planning();
                probe.emit(new Node.MeasureRef(f.field()), false);
                aggTables.addAll(probe.tables());
                measureTables.put(f.field(), new LinkedHashSet<>(probe.tables()));
                tables.addAll(probe.tables());
                expandMixed(measureTables, derived, reader);
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
            dimTables.add(ref.table());
            filters.add(new FilterUse(f, ref, null));
        }
        List<Node> rules = new ArrayList<>();
        boolean denied = false;
        if (reader.enforceRows() && !ds.security().rows().isEmpty()) {
            for (Dataset.RowRule r : ds.security().rows()) {
                if (reader.roles().contains(r.role())) {
                    Node n = com.ash.drishti.server.bi.expr.Expressions.node(r.where());
                    rules.add(n);
                    Node.walk(n, x -> {
                        if (x instanceof Node.Ref ref) {
                            tables.add(resolver.resolve(ref.parts(), null).table());
                            dimTables.add(resolver.resolve(ref.parts(), null).table());
                        }
                    });
                }
            }
            denied = rules.isEmpty();
        }
        if (ds.security().perTable()) {
            // a data model: each table's rules bind to that table; a dimension's rules reach the facts that reference it (HOW_IT_FITS.md §A28 2.6)
            tables.addAll(TableRules.reached(ds, tables, reader));
            TableRules.Bound bound = TableRules.bind(ds, tables, reader);
            rules = new ArrayList<>(bound.rules());
            denied = bound.denied();
        }
        if (tables.isEmpty()) {
            throw invalid("the query names no table");
        }
        Plan whole = new Plan(ds, q, fields, measures, filters, rules, denied, tables, null, List.of(), null, derived);
        Plan joined;
        try {
            joined = join(whole, aggTables, reader);
        } catch (DrishtiException e) {
            if (e.errorCode() == ErrorCode.QUERY_INVALID && measureTables.size() > 1) {
                Plan several = factParts(whole, measureTables, dimTables, reader);
                if (several != null) {
                    return several;
                }
            }
            throw e;
        }
        if (measureTables.size() > 1 && grains(measureTables).size() > 1) {
            // measures of different tables: each is aggregated at its own table's grain, so a one-side measure is never repeated by the many side
            Plan several = factParts(whole, measureTables, dimTables, reader);
            if (several != null) {
                return several;
            }
            throw invalid("the measures " + measureTables.keySet() + " aggregate different tables, and each must be computed at its own table's grain so that none is repeated by "
                    + "another's rows; but a table the query groups, filters or applies a row rule on is not reachable many-to-one from each of them: ask for them in separate queries");
        }
        return joined;
    }


    // ---- a measure that mixes grains --------------------------------------------------------------------------------------

    /**
     * A measure whose expression only combines other measures ({@code [MTM] / [Exposure]}) and whose components have different grains cannot be one aggregate of
     * one base table (the one-side figure would be repeated by the many side). Each such measure in {@code measureTables} is replaced by the measures it is made of
     * (those with aggregates of their own), which are then computed at their own grains and joined; the expression is kept in {@code derived} and worked out over
     * the joined results.
     */
    private void expandMixed(Map<String, Set<String>> measureTables, Map<String, Node> derived, Reader reader) {
        for (String name : new ArrayList<>(measureTables.keySet())) {
            if (pureParts(name) == null) {
                continue;
            }
            Map<String, Set<String>> leaves = new LinkedHashMap<>();
            Set<String> pure = new LinkedHashSet<>();
            leavesOf(name, leaves, pure, reader);
            if (grains(leaves).size() < 2) {
                continue;
            }
            measureTables.remove(name);
            measureTables.putAll(leaves);
            for (String p : pure) {
                derived.put(p, com.ash.drishti.server.bi.expr.Expressions.node(ds.measures().get(p).expr()));
            }
        }
    }

    private void leavesOf(String name, Map<String, Set<String>> leaves, Set<String> pure, Reader reader) {
        List<String> parts = pureParts(name);
        if (parts == null) {
            SqlEmitter probe = new SqlEmitter(ds, dialect, "", null, reader.user()).planning();
            probe.emit(new Node.MeasureRef(name), false);
            leaves.put(name, new LinkedHashSet<>(probe.tables()));
            return;
        }
        if (!pure.add(name)) {
            return;
        }
        parts.forEach(p -> leavesOf(p, leaves, pure, reader));
    }

    /** The measures a measure is made of when its expression has no aggregate, column or window of its own, only arithmetic over other measures; null otherwise. */
    private List<String> pureParts(String name) {
        Dataset.Measure m = ds.measures().get(name);
        if (m == null || m.expr() == null) {
            return null;
        }
        Set<String> refs = new LinkedHashSet<>();
        boolean[] own = {false};
        Node.walk(com.ash.drishti.server.bi.expr.Expressions.node(m.expr()), n -> {
            if (n instanceof Node.MeasureRef r) {
                refs.add(r.name());
            } else if (n instanceof Node.Ref) {
                own[0] = true;
            } else if (n instanceof Node.Call c && (Node.AGGREGATES.contains(c.function()) && !"ratio".equals(c.function()) || Node.TIME.contains(c.function())
                    || Node.isWindow(c.function()) || Node.CONTEXT.contains(c.function()))) {
                own[0] = true;
            }
        });
        return own[0] || refs.isEmpty() ? null : new ArrayList<>(refs);
    }

    /** The table each measure is aggregated at (its own grain): the first of its tables that reaches the rest many-to-one; the distinct grains of the query. */
    private Set<String> grains(Map<String, Set<String>> measureTables) {
        List<Edge> edges = edges();
        Set<String> out = new LinkedHashSet<>();
        for (Set<String> tables : measureTables.values()) {
            String g = grainOf(tables, edges);
            out.add(g == null ? String.join("+", tables) : g);
        }
        return out;
    }

    private static String grainOf(Set<String> tables, List<Edge> edges) {
        for (String t : tables) {
            if (tree(t, tables, edges, true) != null) {
                return t;
            }
        }
        return null;
    }

    // ---- several fact tables ------------------------------------------------------------------------------------------

    /**
     * Measures that aggregate unrelated fact tables (trades and a table of limits that share only the desk): the measures are grouped by the fact they
     * aggregate, each group becomes a part that is aggregated alone at the query's grain (so no fact is repeated by another's rows), and
     * {@link #compileMulti} joins the parts on the grain. Returns null when the measures cannot be grouped that way, so the caller reports the original problem.
     */
    private Plan factParts(Plan whole, Map<String, Set<String>> measureTables, Set<String> dimTables, Reader reader) {
        for (String name : measureTables.keySet()) {
            if (usesWindow(name, new LinkedHashSet<>())) {
                throw invalid("'" + name + "' is a window measure (a running total, rank or lag): it is worked out inside one fact table, so it cannot be asked together with measures of another fact table in one query");
            }
        }
        List<Edge> edges = edges();
        Map<String, Set<String>> groupTablesOf = new LinkedHashMap<>();
        Map<String, List<String>> groupOf = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : measureTables.entrySet()) {
            String grain = grainOf(e.getValue(), edges);
            if (grain == null || tree(grain, union(e.getValue(), dimTables), edges, true) == null) {
                return null;
            }
            groupTablesOf.computeIfAbsent(grain, k -> new LinkedHashSet<>()).addAll(e.getValue());
            groupOf.computeIfAbsent(grain, k -> new ArrayList<>()).add(e.getKey());
        }
        List<Set<String>> groupTables = new ArrayList<>(groupTablesOf.values());
        List<List<String>> groups = new ArrayList<>(groupOf.values());
        List<String> bases = new ArrayList<>(groupOf.keySet());
        if (groups.size() < 2) {
            return null;
        }
        List<Plan> parts = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>();
        QueryRequest q = whole.query();
        List<FilterUse> dims = whole.filters().stream().filter(f -> f.measure() == null).toList();
        for (int i = 0; i < groups.size(); i++) {
            String base = bases.get(i);
            List<Join> joins = tree(base, union(groupTables.get(i), dimTables), edges, true);
            List<Use> uses = new ArrayList<>();
            for (String name : groups.get(i)) {
                Use u = whole.measures().stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
                uses.add(u != null ? u : new Use(name, new Node.MeasureRef(name), columnsOfMeasure(name).stream().anyMatch(c -> c.column() != null && reader.masks().masked(c.table(), c.column()))));
            }
            QueryRequest pq = new QueryRequest(q.fields(), groups.get(i), dims.stream().map(FilterUse::filter).toList(), q.from(), q.to(), List.of(), null, q.layer());
            List<Node> rules = whole.rules();
            boolean denied = whole.denied();
            if (ds.security().perTable()) {
                // each fact part reads its own tables, so only their rules (and those of the dimensions this fact reaches) apply to it
                Set<String> partTables = union(union(groupTables.get(i), dimTables), Set.of(base));
                partTables.addAll(TableRules.reached(ds, partTables, reader));
                List<Join> more = tree(base, partTables, edges, true);
                if (more == null) {
                    return null;
                }
                joins = more;
                checkSemi(joins, groupTables.get(i));
                Set<String> outer = new LinkedHashSet<>(allOf(base, joins));
                outer.removeAll(semiTables(joins));
                TableRules.Bound bound = TableRules.bind(ds, outer, reader);
                rules = bound.rules();
                denied = bound.denied();
            }
            parts.add(new Plan(ds, pq, whole.fields(), uses, dims, rules, denied, allOf(base, joins), base, joins, null));
            all.addAll(allOf(base, joins));
        }
        return new Plan(ds, q, whole.fields(), whole.measures(), whole.filters(), whole.rules(), whole.denied(), all, parts.get(0).base(), List.of(), parts, whole.derived());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    /** True when the measure reads a column that is masked: the measure then reads as masked too. */
    public boolean measureMasked(String name, Masks masks) {
        return columnsOfMeasure(name).stream().anyMatch(c -> c.column() != null && masks.masked(c.table(), c.column()));
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
                    collect(com.ash.drishti.server.bi.expr.Expressions.node(meas.expr()), out, trail);
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
    private record Edge(String from, String fromColumn, String to, String toColumn, boolean oneToOne, Semi semi) {
        Edge(String from, String fromColumn, String to, String toColumn, boolean oneToOne) {
            this(from, fromColumn, to, toColumn, oneToOne, null);
        }
    }

    private List<Edge> edges() {
        List<Edge> out = new ArrayList<>();
        for (Dataset.Relationship r : ds.relationships()) {
            if (r.bridge() != null) {
                Dataset.Bridge b = r.bridge();
                if (b.leftBoth()) {                                                 // a filter on the right reaches the left: a query at the left's grain may cross
                    out.add(new Edge(r.fromTable(), r.fromColumn(), r.toTable(), r.toColumn(), false, new Semi(b.through(), b.leftKey(), b.rightKey())));
                }
                if (b.rightBoth()) {
                    out.add(new Edge(r.toTable(), r.toColumn(), r.fromTable(), r.fromColumn(), false, new Semi(b.through(), b.rightKey(), b.leftKey())));
                }
                continue;
            }
            if (!r.active()) {
                continue;
            }
            if ("both".equals(r.direction()) && !"one-to-one".equals(r.cardinality())) {     // a filter on the many side reaches the one side: a semi-join, never a repeat
                boolean oneToMany = "one-to-many".equals(r.cardinality());
                out.add(new Edge(oneToMany ? r.fromTable() : r.toTable(), oneToMany ? r.fromColumn() : r.toColumn(), oneToMany ? r.toTable() : r.fromTable(),
                        oneToMany ? r.toColumn() : r.fromColumn(), false, new Semi(null, null, null)));
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

    private Plan join(Plan p, Set<String> aggTables, Reader reader) {
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
                checkSemi(joins, aggTables);
                Set<String> semi = semiTables(joins);
                if (!semi.isEmpty() && ds.security().perTable()) {
                    Set<String> outer = new LinkedHashSet<>(allOf(base, joins));
                    outer.removeAll(semi);                                     // a semi-joined table's rules bind inside the semi-join, before any join
                    TableRules.Bound bound = TableRules.bind(ds, outer, reader);
                    return new Plan(p.dataset(), p.query(), p.fields(), p.measures(), p.filters(), bound.rules(), bound.denied(), allOf(base, joins), base, joins, null, p.derived());
                }
                if (!semi.isEmpty() && !p.rules().isEmpty()) {
                    throw invalid("this dataset's row rules are written over its joined columns and cannot be applied across a many-to-many: write them per table");
                }
                return new Plan(p.dataset(), p.query(), p.fields(), p.measures(), p.filters(), p.rules(), p.denied(), allOf(base, joins), base, joins, null, p.derived());
            }
        }
        if (!measures) {
            String base = tables.iterator().next();
            List<Join> joins = tree(base, tables, edges, false);
            if (joins != null) {
                return new Plan(p.dataset(), p.query(), p.fields(), p.measures(), p.filters(), p.rules(), p.denied(), allOf(base, joins), base, joins, null, p.derived());
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
        joins.forEach(j -> {
            all.add(j.toTable());
            if (j.semi() != null && j.semi().through() != null) {
                all.add(j.semi().through());
            }
        });
        return all;
    }

    /** The tables a plan reads only inside a semi-join (the far table and the bridge): their filters and rules apply there, before any join. */
    static Set<String> semiTables(List<Join> joins) {
        Set<String> out = new LinkedHashSet<>();
        for (Join j : joins) {
            if (j.semi() != null) {
                out.add(j.toTable());
                if (j.semi().through() != null) {
                    out.add(j.semi().through());
                }
            }
        }
        return out;
    }

    /** Refuses a plan where a measure reads a semi-joined table (it would be repeated) or a further join leaves one (its rows would not stay unique). */
    private void checkSemi(List<Join> joins, Set<String> aggTables) {
        Set<String> semi = semiTables(joins);
        for (Join j : joins) {
            if (semi.contains(j.fromTable())) {
                throw invalid("'" + j.toTable() + "' lies beyond the many-to-many at '" + j.fromTable() + "': a query crosses one many-to-many and reads its far table's own columns; "
                        + "bring the column onto '" + j.fromTable() + "' with a look-up, or relate the two directly");
            }
            if (j.semi() != null && aggTables.contains(j.toTable())) {
                throw invalid("a measure reads '" + j.toTable() + "' across a many-to-many, where one row of the base table meets several: aggregate '" + j.toTable() + "' on its own, or filter by it");
            }
        }
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
        // a plain path when there is one: a many-to-many or a both-ways relationship is crossed only where nothing else reaches the table
        List<Join> plain = search(target, edges.stream().filter(e -> e.semi() == null).toList(), forwardOnly, inTree);
        return plain != null ? plain : search(target, edges, forwardOnly, inTree);
    }

    private static List<Join> search(String target, List<Edge> edges, boolean forwardOnly, Set<String> inTree) {
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
                    j = new Join(e.from(), e.fromColumn(), e.to(), e.toColumn(), e.semi());
                } else if (!forwardOnly && e.to().equals(at) && e.semi() == null) {
                    next = e.from();
                    j = new Join(e.to(), e.toColumn(), e.from(), e.fromColumn(), null);
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
    /**
     * True when a join of a query over more than one business date also matches the business date: both tables are snapshots read per date (not live, not a
     * history), so without it each fact row would meet its dimension row once per date of the window and be counted that many times.
     */
    private String semiFrom(Join j, Plan p, QueryRequest q, Map<String, Source> sources, Reader reader, MoreSources more, List<Object> fromParams) {
        Semi s = j.semi();
        Set<String> inside = new LinkedHashSet<>(Set.of(j.toTable()));
        if (s.through() != null) {
            inside.add(s.through());
        }
        String near = s.through() == null ? j.toTable() : s.through();
        String key = Dialect.quote(near) + "." + Dialect.quote(s.through() == null ? j.toColumn() : s.throughFrom());
        List<String> cols = new ArrayList<>(List.of(key + " AS " + Dialect.quote(SEMI_KEY)));
        Set<String> projected = new LinkedHashSet<>();
        for (Field f : p.fields()) {
            if (!f.masked() && j.toTable().equals(f.ref().table()) && projected.add(f.ref().column())) {
                cols.add(Dialect.quote(j.toTable()) + "." + Dialect.quote(f.ref().column()));
            }
        }
        boolean dated = overDates(q, new Join(j.fromTable(), j.fromColumn(), near, j.fromColumn()));
        if (dated) {
            cols.add(Dialect.quote(near) + "." + Dialect.quote(Dataset.DATE) + " AS " + Dialect.quote(SEMI_DATE));
        }
        Source to = sources.get(j.toTable());
        StringBuilder inner = new StringBuilder("SELECT DISTINCT ").append(String.join(", ", cols)).append(" FROM ");
        if (s.through() != null) {
            Source bridge = sources.get(s.through());
            inner.append(bridge.sql()).append(" JOIN ").append(to.sql()).append(" ON ").append(Dialect.quote(s.through())).append('.').append(Dialect.quote(s.throughTo()))
                    .append(" = ").append(Dialect.quote(j.toTable())).append('.').append(Dialect.quote(j.toColumn()));
            if (overDates(q, new Join(s.through(), s.throughTo(), j.toTable(), j.toColumn()))) {
                inner.append(" AND ").append(Dialect.quote(s.through())).append('.').append(Dialect.quote(Dataset.DATE)).append(" = ").append(Dialect.quote(j.toTable())).append('.').append(Dialect.quote(Dataset.DATE));
            }
            fromParams.addAll(bridge.params());
        } else {
            inner.append(to.sql());
        }
        fromParams.addAll(to.params());
        SqlEmitter where = new SqlEmitter(ds, dialect, j.toTable(), null, reader.user());
        where.relatedRows(RelatedRows.writer(ds, reader, more, q.from(), q.to()));
        List<String> preds = new ArrayList<>();
        for (FilterUse f : p.filters()) {
            if (f.measure() == null && f.ref() != null && inside.contains(f.ref().table())) {
                preds.add(filter(where, f.filter(), f.ref()));
            }
        }
        TableRules.Bound bound = ds.security().perTable() ? TableRules.bind(ds, inside, reader) : new TableRules.Bound(List.of(), false);
        if (bound.denied()) {
            preds.add("1 = 0");
        }
        boolean restricted = bound.denied() || !bound.rules().isEmpty();
        for (Node rule : bound.rules()) {
            preds.add(where.emit(rule, false));
        }
        if (!preds.isEmpty()) {
            inner.append(" WHERE ").append(String.join(" AND ", preds));
        }
        fromParams.addAll(where.params());
        boolean filtered = !preds.isEmpty() || restricted;                     // inner: a filtered or row-ruled far table drops the base rows it does not match
        StringBuilder out = new StringBuilder(filtered ? " JOIN (" : " LEFT JOIN (").append(inner).append(") AS ").append(Dialect.quote(j.toTable())).append(" ON ")
                .append(Dialect.quote(j.fromTable())).append('.').append(Dialect.quote(j.fromColumn())).append(" = ").append(Dialect.quote(j.toTable())).append('.').append(Dialect.quote(SEMI_KEY));
        if (dated) {
            out.append(" AND ").append(Dialect.quote(j.fromTable())).append('.').append(Dialect.quote(Dataset.DATE)).append(" = ").append(Dialect.quote(j.toTable())).append('.').append(Dialect.quote(SEMI_DATE));
        }
        return out.toString();
    }

    private boolean overDates(QueryRequest q, Join j) {
        if (q.from() == null || q.to() == null || q.from().equals(q.to())) {
            return false;
        }
        Dataset.Table a = ds.tables().get(j.fromTable());
        Dataset.Table b = ds.tables().get(j.toTable());
        return a != null && b != null && !a.isLive() && !b.isLive() && !a.isHistory() && !b.isHistory() && !Dataset.FRAME.equals(a.mode()) && !Dataset.FRAME.equals(b.mode());
    }

    public Compiled compile(Plan p, Reader reader, Map<String, Source> sources, int rowCap, Map<String, String> components) {
        return compile(p, reader, sources, rowCap, components, TimeWindows.Context.weekends(java.time.LocalDate.now(java.time.ZoneOffset.UTC)));
    }

    /** As {@link #compile(Plan, Reader, Map, int, Map)}, with the as-of date and calendar the time-intelligence measures are worked out from. */
    public Compiled compile(Plan p, Reader reader, Map<String, Source> sources, int rowCap, Map<String, String> components, TimeWindows.Context time) {
        return compile(p, reader, sources, rowCap, components, time, null);
    }

    /**
     * As {@link #compile(Plan, Reader, Map, int, Map, TimeWindows.Context)}; {@code more} gives the FROM item of a table a filter context reads beyond the
     * plan's tables or at another date (null: a context may read only the plan's tables at the query's dates).
     */
    public Compiled compile(Plan p, Reader reader, Map<String, Source> sources, int rowCap, Map<String, String> components, TimeWindows.Context time, MoreSources more) {
        if (p.multiFact()) {
            return compileMulti(p, reader, sources, rowCap, time, more);
        }
        QueryRequest q = p.query();
        SqlEmitter select = new SqlEmitter(ds, dialect, p.base(), components, reader.user()).time(time);
        SqlEmitter where = new SqlEmitter(ds, dialect, p.base(), null, reader.user());
        SqlEmitter having = new SqlEmitter(ds, dialect, p.base(), components, reader.user()).time(time);
        Restriction restriction = this.restriction;
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
            select.windowField(f.ref(), expr, f.masked());
            having.windowField(f.ref(), expr, f.masked());
        }
        Contexts contexts = components == null ? new Contexts(ds, dialect, reader, sources, more, q, time, p.fields(), aliasSql, depth) : null;
        select.contexts(contexts);
        having.contexts(contexts);
        for (Use m : p.measures()) {
            outs.add(new Out(m.name(), true, m.masked(), null));
            if (m.masked()) {
                items.add("CAST(NULL AS " + dialect.doubleType() + ") AS " + Dialect.quote(m.name()));
                notes.add("measure " + m.name() + " is masked");
            } else {
                items.add(select.emit(m.tree(), false) + " AS " + Dialect.quote(m.name()));
            }
        }
        List<String> havings = new ArrayList<>();
        for (FilterUse f : p.filters()) {
            if (f.measure() != null) {
                havings.add(measureFilter(having, f.filter()));
            }
        }
        // FROM and joins
        List<Object> fromParams = new ArrayList<>();
        StringBuilder from = new StringBuilder();
        Source base = sources.get(p.base());
        from.append(base.sql());
        fromParams.addAll(base.params());
        for (Join j : p.joins()) {
            if (j.semi() != null) {
                from.append(semiFrom(j, p, q, sources, reader, more, fromParams));
                continue;
            }
            Source s = sources.get(j.toTable());
            from.append(" LEFT JOIN ").append(s.sql()).append(" ON ").append(Dialect.quote(j.fromTable())).append('.').append(Dialect.quote(j.fromColumn()))
                    .append(" = ").append(Dialect.quote(j.toTable())).append('.').append(Dialect.quote(j.toColumn()));
            if (overDates(q, j)) {                                              // a row of one day meets the other table's row of the same day, never each day's
                from.append(" AND ").append(Dialect.quote(j.fromTable())).append('.').append(Dialect.quote(Dataset.DATE)).append(" = ").append(Dialect.quote(j.toTable()))
                        .append('.').append(Dialect.quote(Dataset.DATE));
            }
            fromParams.addAll(s.params());
        }
        if (contexts != null && !contexts.joined().isEmpty()) {
            from.append(contexts.from(fromParams));
            notes.add(contexts.joined().size() + " filter context" + (contexts.joined().size() == 1 ? "" : "s") + " joined on the answer's fields");
        }
        // WHERE: filters, then the row rules
        List<String> preds = new ArrayList<>();
        Set<String> semi = semiTables(p.joins());
        for (FilterUse f : p.filters()) {
            if (f.measure() != null || f.ref() != null && semi.contains(f.ref().table())) {
                continue;                                                      // a filter on a semi-joined table is applied inside the semi-join
            }
            preds.add(filter(where, f.filter(), f.ref()));
        }
        if (restriction != null) {
            preds.add(restriction.predicate(where.params(), r -> where.column(resolve(r))));
        }
        if (p.denied()) {
            preds.add("1 = 0");
            List<String> shut = ds.security().perTable() ? TableRules.deniedTables(ds, p.tables(), reader) : List.of();
            notes.add(shut.isEmpty() ? "no row rule of this dataset covers your roles: no rows"
                    : "you have no access to rows of " + String.join(", ", shut) + " in this model: no row rule names your roles");
        } else if (!p.rules().isEmpty()) {
            where.relatedRows(RelatedRows.writer(ds, reader, more, q.from(), q.to()));
            List<String> ors = new ArrayList<>();
            for (Node rule : p.rules()) {
                ors.add(where.emit(rule, false));
            }
            preds.add("(" + String.join(" OR ", ors) + ")");
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        boolean grouped = !p.measures().isEmpty() || !havings.isEmpty();                     // a measure filter on fields alone (a slicer under a report's
        if (!grouped && !p.fields().isEmpty() && !q.detail()) {                              // `Trades > 0`) groups them; shape rows keeps every row (no DISTINCT)
            sql.append("DISTINCT ");
        }
        sql.append(String.join(", ", items)).append(" FROM ").append(from);
        if (!preds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", preds));
        }
        if (grouped && !group.isEmpty()) {
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

    /**
     * The several-facts query: every part is the ordinary single-fact SELECT (its own row rules, filters and masks), aggregated at the query's fields; the parts are
     * full-outer-joined on those fields (null equals null, so a group one fact lacks still shows with the other's figures), the measures are picked from their part,
     * and the measure filters, sort and limit are applied to the joined rows.
     */
    private Compiled compileMulti(Plan p, Reader reader, Map<String, Source> sources, int rowCap, TimeWindows.Context time, MoreSources more) {
        List<Compiled> cs = new ArrayList<>();
        for (Plan part : p.parts()) {
            cs.add(compile(part, reader, sources, Integer.MAX_VALUE, null, time, more));
        }
        Map<String, Integer> holder = new LinkedHashMap<>();
        Map<String, Out> outOf = new LinkedHashMap<>();
        for (int i = 0; i < cs.size(); i++) {
            for (Out o : cs.get(i).columns()) {
                if (o.measure() && holder.putIfAbsent(o.name(), i) == null) {
                    outOf.put(o.name(), o);
                }
            }
        }
        List<Object> params = new ArrayList<>();
        List<Object> selectParams = new ArrayList<>();
        StringBuilder from = new StringBuilder();
        List<Out> outs = new ArrayList<>();
        for (int i = 0; i < cs.size(); i++) {
            if (i > 0) {
                List<String> on = new ArrayList<>();
                for (Field f : p.fields()) {
                    List<String> left = new ArrayList<>();
                    for (int j = 0; j < i; j++) {
                        left.add(alias(j, f.text()));
                    }
                    on.add((left.size() == 1 ? left.get(0) : "coalesce(" + String.join(", ", left) + ")") + " IS NOT DISTINCT FROM " + alias(i, f.text()));
                }
                from.append(" FULL OUTER JOIN (").append(cs.get(i).sql()).append(") AS ").append(Dialect.quote("p" + i)).append(" ON ")
                        .append(on.isEmpty() ? "1 = 1" : String.join(" AND ", on));
            } else {
                from.append("(").append(cs.get(0).sql()).append(") AS ").append(Dialect.quote("p0"));
            }
            params.addAll(cs.get(i).params());
        }
        List<String> inner = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Field f : p.fields()) {
            List<String> all = new ArrayList<>();
            for (int j = 0; j < cs.size(); j++) {
                all.add(alias(j, f.text()));
            }
            inner.add("coalesce(" + String.join(", ", all) + ") AS " + Dialect.quote(f.text()));
            names.add(f.text());
            outs.add(cs.get(0).columns().stream().filter(o -> !o.measure() && o.name().equals(f.text())).findFirst().orElseThrow());
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (Use m : p.measures()) {
            wanted.add(m.name());
            boolean derived = p.derived().containsKey(m.name());
            inner.add((derived ? joinedExpr(p, m.name(), holder, reader, selectParams) : alias(holder.get(m.name()), m.name())) + " AS " + Dialect.quote(m.name()));
            names.add(m.name());
            outs.add(derived ? new Out(m.name(), true, m.masked(), null) : outOf.get(m.name()));
        }
        List<String> preds = new ArrayList<>();
        for (FilterUse f : p.filters()) {
            if (f.measure() == null) {
                continue;
            }
            String name = f.filter().field();
            if (wanted.add(name)) {
                inner.add((p.derived().containsKey(name) ? joinedExpr(p, name, holder, reader, selectParams) : alias(holder.get(name), name)) + " AS " + Dialect.quote(name));
            }
            preds.add(measureCompare(Dialect.quote(name), f.filter(), params));
        }
        List<String> order = new ArrayList<>();
        for (QueryRequest.Sort s : p.query().orderBy()) {
            Out o = outs.stream().filter(x -> x.name().equals(s.by())).findFirst().orElseThrow(
                    () -> invalid("cannot sort by '" + s.by() + "': it is not one of the query's fields or measures"));
            if (o.masked()) {
                throw new DrishtiException(ErrorCode.QUERY_MASKED, "'" + s.by() + "' is masked for you: it cannot be sorted on");
            }
            order.add(Dialect.quote(o.name()) + (s.desc() ? " DESC" : " ASC") + " NULLS LAST");
        }
        if (order.isEmpty()) {
            p.fields().stream().filter(f -> !f.masked()).forEach(f -> order.add(Dialect.quote(f.text()) + " ASC"));
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", names.stream().map(Dialect::quote).toList())).append(" FROM (SELECT ")
                .append(String.join(", ", inner)).append(" FROM ").append(from).append(") AS ").append(Dialect.quote("q"));
        if (!preds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", preds));
        }
        if (!order.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", order));
        }
        sql.append(" LIMIT ").append(Math.max(1, rowCap));
        List<String> notes = new ArrayList<>();
        cs.forEach(c -> notes.addAll(c.notes()));
        notes.add(p.parts().size() + " fact tables, each aggregated alone at the query's fields and then joined: " + p.tables());
        if (!p.derived().isEmpty()) {
            notes.add("a measure that combines measures of different grains is worked out over the joined results: " + String.join(", ", p.derived().keySet()));
        }
        selectParams.addAll(params);
        params = selectParams;
        return new Compiled(sql.toString(), params, outs, p.tables(), p.base(), notes.stream().distinct().toList());
    }

    /** A measure that combines other measures, written over the joined parts: each measure it names is the column its own part computed at its own grain. */
    private String joinedExpr(Plan p, String name, Map<String, Integer> holder, Reader reader, List<Object> params) {
        SqlEmitter e = new SqlEmitter(ds, dialect, p.base(), null, reader.user()).overJoined(n -> p.derived().containsKey(n) ? null : alias(holder.get(n), n));
        String sql = e.emit(new Node.MeasureRef(name), false);
        params.addAll(e.params());
        return sql;
    }

    private static String alias(int part, String column) {
        return Dialect.quote("p" + part) + "." + Dialect.quote(column);
    }

    private String filter(SqlEmitter e, QueryRequest.Filter f, Resolver.ColRef ref) {
        String col = e.column(ref);
        String type = ref.part() != null ? Resolver.partType(ref.part()) : resolver.column(ref.table(), ref.column()).type();
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
        if (usesWindow(f.field(), new java.util.HashSet<>())) {
            throw invalid("'" + f.field() + "' is a running total, which is worked out along the visual's date field: it cannot be filtered on");
        }
        return measureCompare(e.emit(new Node.MeasureRef(f.field()), false), f, e.params());
    }

    /** A measure compared to one number: the comparison text, with the number added to {@code params}. */
    private String measureCompare(String expr, QueryRequest.Filter f, List<Object> params) {
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
        params.add(n.doubleValue());
        return "(" + expr + " " + op + " ?)";
    }

    private boolean usesWindow(String measure, Set<String> seen) {
        Dataset.Measure m = ds.measures().get(measure);
        if (m == null || m.expr() == null || !seen.add(measure)) {
            return false;
        }
        boolean[] hit = {false};
        Node.walk(com.ash.drishti.server.bi.expr.Expressions.node(m.expr()), n -> {
            if (n instanceof Node.Call c && Node.isWindow(c.function())) {
                hit[0] = true;
            } else if (n instanceof Node.MeasureRef r && usesWindow(r.name(), seen)) {
                hit[0] = true;
            }
        });
        return hit[0];
    }

    private static DrishtiException invalid(String message) {
        return new DrishtiException(ErrorCode.QUERY_INVALID, message);
    }
}
