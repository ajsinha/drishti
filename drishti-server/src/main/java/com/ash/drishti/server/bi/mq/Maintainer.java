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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.GenerationBus;
import com.ash.drishti.server.bi.engine.Incremental;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.SqlCompiler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps one standing query's answer current from the changes of its model's live tables (docs/architecture/BI_DESIGN.md section 4.6), by the rule
 * {@link Incremental#classify} gives for the changed table: by difference (no query), the touched groups asked again ({@code field in [values]} added to the
 * question: the engine applies the reader's security), or the whole question asked again (contexts, fields of other tables), or on the tick (windows, time
 * intelligence, Python). It answers the rows as {@link MqService#answer} writes them (the fields, then the measures). Not thread-safe: its standing query
 * serialises it.
 */
final class Maintainer {

    /** What one change did: the rows now, and how they were worked out. */
    record Applied(List<List<Object>> rows, Incremental.Kind kind) {}

    private final MqService mq;
    private final MqService.Bound bound;
    private final ModelQuery query;
    private final boolean engineLimit;
    private final MqService.Prepared prepared;
    private final SqlCompiler.Reader reader;
    private final Map<String, Incremental.Shape> shapes = new LinkedHashMap<>();
    private final Map<String, Incremental> states = new LinkedHashMap<>();
    /** Per live table, the generation the kept answer is at: a notice at or below it is in the answer already. */
    private final Map<String, Long> at = new LinkedHashMap<>();
    private final java.util.function.ToLongFunction<String> generationOf;

    /** @param generationOf a live table's generation now (to start a kept state at a known point) */
    Maintainer(MqService mq, MqService.Bound bound, ModelQuery query, boolean engineLimit, java.util.function.ToLongFunction<String> generationOf) {
        this.generationOf = generationOf;
        this.mq = mq;
        this.bound = bound;
        this.query = query;
        this.engineLimit = engineLimit;
        this.prepared = mq.prepare(bound, query, engineLimit);
        MqValidator.Context ctx = bound.context();
        this.reader = new SqlCompiler.Reader(ctx.enforce(), ctx.roles(), ctx.attributes(), ctx.masks());
    }

    /** The rule for a change of {@code table} (cached per table). */
    Incremental.Shape shape(String table) {
        return shapes.computeIfAbsent(table, t -> {
            Dataset ds = prepared.compiled().dataset();
            QueryRequest main = prepared.main();
            boolean extra = query.having().size() > 0 || query.totals() != null || !prepared.compiled().guards().isEmpty();
            if (query.where().stream().anyMatch(w -> "top".equals(w.op())) || query.rows() || !query.define().isEmpty() && !Incremental.evaluable(ds, main)) {
                return new Incremental.Shape(Incremental.Kind.RECOMPUTE, "a top filter, a row extract or a local measure: asked again on each change");
            }
            Incremental.Shape s = Incremental.classify(ds, main, reader, t, extra);
            if (s.kind() == Incremental.Kind.DIFFERENCE && !Incremental.evaluable(ds, main)) {
                return new Incremental.Shape(Incremental.Kind.TOUCHED, "a measure whose arithmetic is worked out by the engine: the touched groups are asked again");
            }
            return s;
        });
    }

    /** The worst rule over the model's live tables (the standing query's mode in its status). */
    Incremental.Kind mode() {
        Incremental.Kind worst = Incremental.Kind.DIFFERENCE;
        for (Dataset.Table t : prepared.compiled().dataset().tables().values()) {
            if (t.isLive()) {
                Incremental.Kind k = shape(t.name()).kind();
                worst = k.ordinal() > worst.ordinal() ? k : worst;
            }
        }
        return worst;
    }

    /**
     * Applies the live changes of one table to {@code current} (the answer before them); null when the change needs the whole question asked again (the caller
     * does) or on the tick.
     */
    Applied apply(String table, List<GenerationBus.Notice> notices, List<List<Object>> current) {
        Incremental.Shape s = shape(table);
        long since = at.getOrDefault(table, Long.MIN_VALUE);
        List<GenerationBus.Notice> fresh = notices.stream().filter(n -> n.generation() > since).toList();
        if (fresh.isEmpty()) {
            return new Applied(current, s.kind());
        }
        List<GenerationBus.Change> changes = fresh.stream().map(GenerationBus.Notice::change).filter(java.util.Objects::nonNull).toList();
        switch (s.kind()) {
            case DIFFERENCE -> {
                Incremental inc = states.get(table);
                if (inc == null) {
                    for (int attempt = 0; attempt < 3 && inc == null; attempt++) {
                        long g0 = generationOf.applyAsLong(table);
                        Incremental started = Incremental.start(prepared.compiled().dataset(), prepared.main(), reader, table, (ds, q) -> bound.runner().run(ds, q, false));
                        if (generationOf.applyAsLong(table) == g0) {
                            inc = started;
                            at.put(table, g0);                                  // the batches up to g0 are in it; later notices apply on top
                        }
                    }
                    if (inc == null) {
                        return null;                                            // the table moves too fast to start: ask again
                    }
                    states.put(table, inc);
                    long g = at.get(table);
                    for (GenerationBus.Notice n : fresh) {
                        if (n.generation() > g && n.change() != null) {
                            inc.apply(n.change());
                            at.put(table, n.generation());
                        }
                    }
                    return new Applied(inc.rows(), s.kind());
                }
                for (GenerationBus.Notice n : fresh) {
                    if (n.change() != null) {
                        inc.apply(n.change());
                    }
                    at.put(table, Math.max(at.getOrDefault(table, Long.MIN_VALUE), n.generation()));
                }
                return new Applied(inc.rows(), s.kind());
            }
            case TOUCHED -> {
                List<List<Object>> rows = touched(table, changes, current);
                if (rows != null) {
                    at.put(table, fresh.get(fresh.size() - 1).generation());
                }
                return rows == null ? null : new Applied(rows, s.kind());
            }
            default -> {
                return null;
            }
        }
    }

    /** The whole question was asked again with the live tables at these generations: forget the kept state, and skip the notices it already holds. */
    void synced(Map<String, Long> generations) {
        states.clear();
        at.clear();
        at.putAll(generations);
    }

    /** The touched groups asked again ({@code field in [their values]}), merged into the answer; null when the answer was cut by its limit. */
    private List<List<Object>> touched(String table, List<GenerationBus.Change> changes, List<List<Object>> current) {
        List<String> fields = prepared.checked().fields();
        int limit = prepared.main().limit() == null ? Integer.MAX_VALUE : prepared.main().limit();
        if (fields.isEmpty() || current.size() >= limit) {
            return null;
        }
        List<Set<Object>> values = new ArrayList<>();
        fields.forEach(f -> values.add(new LinkedHashSet<>()));
        for (GenerationBus.Change c : changes) {
            List<Object[]> rows = new ArrayList<>();
            c.before().values().stream().filter(java.util.Objects::nonNull).forEach(rows::add);
            rows.addAll(c.deletes().values());
            rows.addAll(c.upserts().values());
            for (Object[] row : rows) {
                for (int i = 0; i < fields.size(); i++) {
                    Object v = fieldValue(table, c.columns(), fields.get(i), row);
                    values.get(i).add(v);
                }
            }
        }
        List<ModelQuery.Where> where = new ArrayList<>(query.where());
        for (int i = 0; i < fields.size(); i++) {
            List<Object> vs = values.get(i).stream().filter(java.util.Objects::nonNull).toList();
            if (values.get(i).contains(null)) {
                return null;                                                    // a group of None cannot be named by `in`: ask everything
            }
            where.add(ModelQuery.Where.of(fields.get(i), "in", vs));
        }
        ModelQuery narrowed = new ModelQuery(query.mq(), query.model(), query.define(), query.fields(), query.measures(), where, query.having(), query.time(), query.sort(),
                query.limit(), null, query.drill(), query.shape(), query.params(), query.options(), query.ext());
        MqService.Ran ran = mq.run(bound, narrowed, false, engineLimit);
        List<List<Object>> fresh = MqService.rows(ran.shown());
        List<List<Object>> out = new ArrayList<>();
        int n = fields.size();
        for (List<Object> row : current) {
            boolean inTouched = true;
            for (int i = 0; i < n; i++) {
                Object v = row.get(i);
                if (values.get(i).stream().noneMatch(x -> same(x, v))) {
                    inTouched = false;
                    break;
                }
            }
            if (!inTouched) {
                out.add(row);
            }
        }
        out.addAll(fresh);
        out.sort(order(prepared.main()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }
        return java.util.Objects.equals(a == null ? null : String.valueOf(a), b == null ? null : String.valueOf(b));
    }

    /** A field's value in a changed row, as the answer holds it. */
    private static Object fieldValue(String table, List<String> columns, String field, Object[] row) {
        String[] p = field.split("\\.");
        int i = columns.indexOf(p.length > 1 ? p[1] : p[0]);
        if (i < 0) {
            return null;
        }
        Object v = row[i];
        if (p.length > 2 && v != null) {
            v = partOf(p[2], v);
        }
        return v instanceof java.time.LocalDate d ? d.toString() : v instanceof Boolean b ? b.toString() : v;
    }

    private static Object partOf(String part, Object v) {
        java.time.LocalDate d = v instanceof java.time.LocalDate x ? x : java.time.LocalDate.parse(String.valueOf(v).substring(0, 10));
        return switch (part) {
            case "year" -> (long) d.getYear();
            case "quarter" -> (long) ((d.getMonthValue() - 1) / 3 + 1);
            case "month" -> (long) d.getMonthValue();
            case "day" -> (long) d.getDayOfMonth();
            default -> (long) d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        };
    }

    /** The engine's order: the query's sort, else its fields ascending, nulls last. */
    static java.util.Comparator<List<Object>> order(QueryRequest q) {
        List<String> names = new ArrayList<>(q.fields());
        names.addAll(q.measures());
        List<QueryRequest.Sort> sorts = q.orderBy().isEmpty() ? q.fields().stream().map(f -> new QueryRequest.Sort(f, false)).toList() : q.orderBy();
        return (a, b) -> {
            for (QueryRequest.Sort s : sorts) {
                int i = names.indexOf(s.by());
                if (i < 0 || i >= a.size()) {
                    continue;
                }
                Object x = a.get(i);
                Object y = b.get(i);
                if (x == null || y == null) {
                    if (x != y) {
                        return x == null ? 1 : -1;
                    }
                    continue;
                }
                int c = x instanceof Number u && y instanceof Number w ? Double.compare(u.doubleValue(), w.doubleValue()) : String.valueOf(x).compareTo(String.valueOf(y));
                if (c != 0) {
                    return s.desc() ? -c : c;
                }
            }
            return 0;
        };
    }
}
