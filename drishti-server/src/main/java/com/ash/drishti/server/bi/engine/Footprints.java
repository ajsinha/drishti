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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.runs.DataVersionVector;
import com.ash.drishti.server.bi.runs.RunEstimate;
import com.ash.drishti.server.bi.runs.RunEstimator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a question would read, from metadata only (docs/architecture/BI_RUNS.md sections 2.3 and 4.1): for every table its dates with their rows
 * ({@link Layers#peek}: a built partition's {@code meta.json}, else the source's {@code rowEstimate}, else history, else unknown), whether each is
 * built, the rollup the optimiser would choose and its valid partitions, and the data version of every table and date read. Nothing is built and
 * no data file is opened: the planner's estimate and the synchronous cost refusal ({@code DRS-9029}) both come from here, before any build.
 */
public final class Footprints {

    /**
     * A question's footprint.
     *
     * @param plan   the estimator's view of it
     * @param data   the data version of every (connector, kind, date) it reads; incomplete when a source cannot tell or a live table is read
     * @param dates  the business dates it reads
     * @param rollup the rollup the optimiser would choose; null when none
     */
    public record Footprint(RunEstimator.Plan plan, DataVersionVector data, List<LocalDate> dates, String rollup) {}

    private final QueryService queries;

    Footprints(QueryService queries) {
        this.queries = queries;
    }

    Footprint of(QueryService.Loaded ds, QueryRequest q, QueryService.Caller who, Layers.RowHistory history) {
        Dataset d = ds.dataset();
        DataVersionVector data = new DataVersionVector();
        if (d.framed()) {                                                        // composed or derived: its sources are other questions
            data.put("framed", ds.key(), LocalDate.MIN, Optional.empty());
            return new Footprint(new RunEstimator.Plan(List.of(new RunEstimator.Table(Dataset.RESULT, "framed", RunEstimator.BuildPath.RECORD,
                    List.of(RunEstimator.DateRows.unknown(LocalDate.MIN)), 1, 0)), Optional.empty(), null, List.of(), q.limit() == null ? null : (long) q.limit(),
                    false), data, List.of(), null);
        }
        SqlCompiler.Reader reader = new SqlCompiler.Reader(who.enforce(), who.roles(), who.attributes(), HistoryMasks.over(d, who.masks()));
        SqlCompiler.Plan plan = new SqlCompiler(d, Dialect.DUCKDB).plan(q, reader);
        List<RunEstimator.Table> tables = new ArrayList<>();
        Set<LocalDate> dates = new TreeSet<>();
        boolean live = false;
        Map<String, List<Layers.Peeked>> peeked = new java.util.LinkedHashMap<>();
        ExecProperties exec = queries.execProperties();
        int fragments = Math.max(1, (int) Math.floor(exec.maxFragments() * exec.fragmentsShare()));
        for (String t : plan.tables()) {
            Dataset.Table table = d.tables().get(t);
            if (table == null || who.denied().contains(t)) {
                continue;                                                        // a denied table reads nothing for this reader
            }
            String connector = table.connector() == null || table.connector().isBlank() ? "kind:" + table.kind() : table.connector();
            if (table.isLive() || table.isHistory() || "live".equals(table.mode())) {
                live = true;
                data.put(connector, String.valueOf(table.kind()), LocalDate.MIN, Optional.empty());   // live state has no version: never reused
                continue;
            }
            List<Layers.Peeked> ps = queries.layers().peek(ds.key(), table, q.from(), q.to(), history);
            peeked.put(t, ps);
            boolean direct = "direct".equals(table.mode());
            List<RunEstimator.DateRows> rows = new ArrayList<>();
            for (Layers.Peeked p : ps) {
                LocalDate day = p.date() == null ? LocalDate.MIN : p.date();
                dates.add(day);
                data.put(connector, String.valueOf(table.kind()), day, Optional.ofNullable(p.version()));
                if (p.rows() == null) {
                    rows.add(RunEstimator.DateRows.unknown(day));
                } else if (p.built()) {
                    rows.add(RunEstimator.DateRows.built(day, p.rows()));
                } else {
                    // a direct table is read in place (nothing to build); a date the source holds no rows of costs nothing to build
                    rows.add(new RunEstimator.DateRows(day, p.rows(), basis(p.basis()), direct || p.rows() == 0));
                }
            }
            tables.add(new RunEstimator.Table(t, connector, path(table), rows, fragments, 0));
        }
        Optional<RunEstimator.Rollup> rollup = Optional.empty();
        String rollupName = null;
        if (!live && !"columns".equals(q.layer()) && plan.tables().stream().noneMatch(who.denied()::contains)) {
            Dataset.Rollup r = queries.route(d, plan, q, Map.of());
            if (r != null && peeked.containsKey(r.table())) {
                rollupName = r.name();
                rollup = Optional.of(new RunEstimator.Rollup(r.name(), queries.layers().peekRollup(ds.key(), d, r, peeked.get(r.table())), 0));
            }
        }
        List<String> grain = new ArrayList<>(new LinkedHashSet<>(q.fields()));
        RunEstimator.Plan p = new RunEstimator.Plan(tables, rollup, String.join(" × ", grain), List.of(), q.limit() == null ? null : (long) q.limit(), live);
        return new Footprint(p, data, List.copyOf(dates), rollupName);
    }

    private RunEstimator.BuildPath path(Dataset.Table t) {
        try {
            return queries.columnar(t) ? RunEstimator.BuildPath.SCAN : RunEstimator.BuildPath.RECORD;
        } catch (RuntimeException e) {
            return RunEstimator.BuildPath.RECORD;
        }
    }

    static RunEstimate.Basis basis(String wire) {
        for (RunEstimate.Basis b : RunEstimate.Basis.values()) {
            if (b.wire().equals(wire)) {
                return b;
            }
        }
        return RunEstimate.Basis.STORE_STATS;                                   // a source's own name for its statistics
    }

    /**
     * The rows a query reads by the metadata (the known rows of every table it reads; unknown dates count nothing, the check after the build covers
     * them), or -1 when a rollup answers it (its rows are small by construction) or a live table is read.
     */
    static long knownRows(Footprint f) {
        if (f.rollup() != null || f.plan().live()) {
            return -1;
        }
        long rows = 0;
        for (RunEstimator.Table t : f.plan().tables()) {
            for (RunEstimator.DateRows d : t.dates()) {
                if (d.rows() != null) {
                    rows = rows + d.rows() < 0 ? Long.MAX_VALUE : rows + d.rows();
                }
            }
        }
        return rows;
    }
}
