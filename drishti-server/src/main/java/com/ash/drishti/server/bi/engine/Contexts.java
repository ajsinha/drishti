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

import com.ash.drishti.bicompute.context.FilterContext;
import com.ash.drishti.bicompute.context.FilterContext.Kind;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.dataset.Resolver;
import com.ash.drishti.server.bi.dataset.NodeText;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The filter context of one SELECT (docs/architecture/BI_DESIGN.md section 3.3.5, the {@code Context} node's lowering of section 4.2): every function that
 * needs its aggregate in another context ({@code calc} with {@code ignore=}, {@code keep=} or {@code via=}, {@code share_of}, {@code parent_of},
 * {@code avg_by}, {@code avg_over}, {@code as_of}, {@code last}, {@code first}) becomes a <em>context sub-query</em>: the same compiler, as the same reader
 * (so the row rules and masks apply: security is never in scope), over the edited question (fields and the query's filters dropped or kept, another
 * relationship, other dates), grouped at the coarser grain, then left-joined to the rows of the main query on the fields both group by. Each main row meets at
 * most one row of a context (the context is unique on those fields), so no row is repeated and the main aggregates stay what they were; the context's value
 * is read as {@code max(cK.v)} in the grouped SELECT, constant within a group. One instance per compiled SELECT; not thread-safe.
 */
final class Contexts {

    /** The value column of every context sub-query (the engine's). */
    static final String VALUE = FilterContext.VALUE;
    /** The measure the context sub-query computes (the edited aggregate, printed as DEL). */
    static final String MEASURE = FilterContext.MEASURE;

    /** A joined context: its alias, the SQL of the sub-query, its parameters and the main fields it is joined on. */
    record Joined(String alias, String sql, List<Object> params, List<String> on) {}

    private final Dataset ds;
    private final Dialect dialect;
    private final SqlCompiler.Reader reader;
    private final Map<String, SqlCompiler.Source> sources;
    private final SqlCompiler.MoreSources more;
    private final QueryRequest query;
    private final TimeWindows.Context time;
    private final List<SqlCompiler.Field> fields;
    private final Map<String, String> fieldSql;
    private final List<Joined> joined = new ArrayList<>();
    private final Map<String, String> byKey = new LinkedHashMap<>();
    private final int depth;

    Contexts(Dataset ds, Dialect dialect, SqlCompiler.Reader reader, Map<String, SqlCompiler.Source> sources, SqlCompiler.MoreSources more, QueryRequest query,
            TimeWindows.Context time, List<SqlCompiler.Field> fields, Map<String, String> fieldSql, int depth) {
        this.ds = ds;
        this.dialect = dialect;
        this.reader = reader;
        this.sources = sources;
        this.more = more;
        this.query = query;
        this.time = time;
        this.fields = fields;
        this.fieldSql = fieldSql;
        this.depth = depth;
    }

    List<Joined> joined() {
        return joined;
    }

    /** The fields of the main query (in order), for {@code parent_of} and the joins. */
    List<SqlCompiler.Field> fields() {
        return fields;
    }

    TimeWindows.Context time() {
        return time;
    }

    QueryRequest query() {
        return query;
    }

    Dataset dataset() {
        return ds;
    }

    /**
     * Registers a context and returns the SQL that reads its value in the main SELECT ({@code max("c0"."__v")}; for {@link Kind#EDGE}, the bare column, read
     * per row inside an aggregate's filter).
     *
     * @param inner the aggregate the context computes (a DEL tree)
     * @param ignore fields whose query and group filters are dropped (null: none; {@code ALL}: every one)
     * @param keep when not null, the only fields whose filters and groups stay
     * @param via a relationship id or label to use instead of the active one for the same pair, or null
     * @param by the extra fields of an {@link Kind#AVERAGE}
     * @param date the business date of a {@link Kind#DATED} context
     * @param edge the date column of an {@link Kind#EDGE} context, whose {@code edgeFn} ({@code max} or {@code min}) per group it computes
     */
    String register(Kind kind, Node inner, List<Node> ignore, List<Node> keep, String via, List<Node> by, LocalDate date, Node edge, String edgeFn) {
        try {
            FilterContext.checkDepth(depth);                                   // the engine's rule 2 (BI_DESIGN 3.3.5)
        } catch (IllegalArgumentException x) {
            throw new DrishtiException(ErrorCode.MQ_CONTEXT, x.getMessage());
        }
        boolean all = ignore != null && ignore.size() == 1 && ignore.get(0) instanceof Node.Ref r && r.parts().equals(List.of("ALL"));
        FilterContext.Edit edit = new FilterContext.Edit(kind, all, columns(all ? List.of() : ignore), keep == null ? null : columns(keep), via,
                by == null ? List.of() : by.stream().map(b -> ((Node.Ref) b).text()).toList(), date);
        List<FilterContext.Field> answer = new ArrayList<>();
        for (SqlCompiler.Field f : fields) {
            answer.add(new FilterContext.Field(f.text(), f.ref().table() + "." + f.ref().column(), f.masked()));
        }
        List<FilterContext.Filter> asked = new ArrayList<>();
        Resolver resolver = new Resolver(ds);
        for (QueryRequest.Filter f : query.filters()) {
            boolean onMeasure = ds.measures().containsKey(f.field()) && !f.field().contains(".");
            asked.add(new FilterContext.Filter(onMeasure ? null : column(resolver, f.field()), onMeasure));
        }
        FilterContext.Question question = FilterContext.plan(edit, answer, asked);    // the engine decides the edited question
        List<String> subFields = question.on();
        List<String> on = question.on();
        List<QueryRequest.Filter> filters = new ArrayList<>();
        question.filters().forEach(i -> filters.add(query.filters().get(i)));
        Dataset sub = via == null ? ds : switched(via);
        Node measure = inner;
        if (kind == Kind.EDGE) {
            measure = new Node.Call(edgeFn, List.of(edge));
        }
        List<String> grouped = question.groups();
        String key = FilterContext.key(edit, NodeText.print(measure), question, filters);
        String alias = byKey.get(key);
        if (alias == null) {
            alias = FilterContext.alias(depth, joined.size());
            Map<String, Dataset.Measure> measures = new LinkedHashMap<>(sub.measures());
            measures.put(MEASURE, new Dataset.Measure(MEASURE, NodeText.print(measure), null, "a filter context", null));
            sub = sub.withMeasures(measures);
            LocalDate from = date != null ? date : query.from();
            LocalDate to = date != null ? date : query.to();
            QueryRequest q = new QueryRequest(grouped, List.of(MEASURE), filters, from, to, List.of(), null, "columns");
            SqlCompiler compiler = new SqlCompiler(sub, dialect).depth(depth + 1);
            SqlCompiler.Plan plan = compiler.plan(q, reader);
            Map<String, SqlCompiler.Source> from0 = new LinkedHashMap<>();
            for (String t : plan.tables()) {
                SqlCompiler.Source s = date == null ? sources.get(t) : null;
                if (s == null) {
                    if (more == null) {
                        throw new DrishtiException(ErrorCode.MQ_CONTEXT, "a filter-context function needs table '" + t + "'" + (date == null ? "" : " at " + date)
                                + ", which this query cannot read here");
                    }
                    s = more.of(t, from, to);
                }
                from0.put(t, s);
            }
            TimeWindows.Context subTime = date == null ? time : new TimeWindows.Context(date, date, time.calendar(), time.fiscalYearStart());
            SqlCompiler.Compiled c = compiler.compile(plan, reader, from0, Integer.MAX_VALUE - 1, null, subTime, more);
            String sql = c.sql();
            List<Object> params = c.params();
            if (kind == Kind.AVERAGE) {
                sql = FilterContext.average(sql, subFields.stream().map(Dialect::quote).toList(), Dialect.quote(MEASURE));
            }
            joined.add(new Joined(alias, sql, params, on));
            byKey.put(key, alias);
        }
        return FilterContext.read(kind, Dialect.quote(alias) + "." + Dialect.quote(MEASURE));
    }

    /** The FROM item of a table at the query's dates (the plan's, else read for this purpose): for {@code fx}'s rates table. */
    SqlCompiler.Source source(String table) {
        SqlCompiler.Source s = sources.get(table);
        if (s != null) {
            return s;
        }
        if (more == null) {
            throw new DrishtiException(ErrorCode.MQ_CONTEXT, "table '" + table + "' cannot be read here");
        }
        return more.of(table, query.from(), query.to());
    }

    /** The FROM text of the joins (appended after the main query's joins), its parameters added to {@code params} in order. */
    String from(List<Object> params) {
        StringBuilder b = new StringBuilder();
        for (Joined j : joined) {
            b.append(FilterContext.join(j.sql(), Dialect.quote(j.alias()), j.on().stream().map(fieldSql::get).toList(),
                    j.on().stream().map(Dialect::quote).toList()));
            params.addAll(j.params());
        }
        return b.toString();
    }

    /** The {@code table.column} of each field listed (a date part counts as its column). */
    private Set<String> columns(List<Node> list) {
        Set<String> out = new LinkedHashSet<>();
        if (list == null) {
            return out;
        }
        Resolver resolver = new Resolver(ds);
        for (Node n : list) {
            if (n instanceof Node.Ref r) {
                out.add(column(resolver, r.text()));
            }
        }
        return out;
    }

    private static String column(Resolver resolver, String field) {
        try {
            Resolver.ColRef c = resolver.resolve(List.of(field.split("\\.", -1)), null);
            return c.table() + "." + c.column();
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, e.getMessage());
        }
    }

    /** The dataset with the relationship {@code via} (its id, name or label) active and every other active one between the same two tables inactive. */
    private Dataset switched(String via) {
        Dataset.Relationship chosen = ds.relationships().stream().filter(r -> via.equals(r.id()) || via.equals(r.label())).findFirst().orElseThrow(
                () -> new DrishtiException(ErrorCode.MQ_CONTEXT, "via='" + via + "' names no relationship of this model (relationships: "
                        + ds.relationships().stream().map(r -> r.id() == null ? r.label() : r.id()).toList() + ")"));
        List<Dataset.Relationship> rels = new ArrayList<>();
        for (Dataset.Relationship r : ds.relationships()) {
            boolean samePair = r.fromTable().equals(chosen.fromTable()) && r.toTable().equals(chosen.toTable())
                    || r.fromTable().equals(chosen.toTable()) && r.toTable().equals(chosen.fromTable());
            boolean active = r == chosen || r.active() && !samePair;
            rels.add(new Dataset.Relationship(r.fromTable(), r.fromColumn(), r.toTable(), r.toColumn(), r.cardinality(), r.direction(), active, r.id()));
        }
        return ds.withRelationships(rels);
    }

    /** True when a measure (or one it names) needs a context or a balance function: the rollups cannot answer it, nor a several-facts query combine it. */
    static boolean uses(Dataset ds, String measure) {
        return uses(ds, measure, new LinkedHashSet<>());
    }

    private static boolean uses(Dataset ds, String measure, Set<String> seen) {
        Dataset.Measure m = ds.measures().get(measure);
        if (m == null || m.expr() == null || !seen.add(measure)) {
            return false;
        }
        Node n;
        try {
            n = com.ash.drishti.server.bi.expr.Expressions.node(m.expr());
        } catch (RuntimeException e) {
            return false;
        }
        return Node.anyMatch(n, x -> x instanceof Node.Call c && (Node.CONTEXT.contains(c.function()) || Node.SCALARS_B.contains(c.function()))
                || x instanceof Node.MeasureRef r && uses(ds, r.name(), seen));
    }
}
