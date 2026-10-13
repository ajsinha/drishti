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

import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.layer.ResolvedDataset;
import com.ash.drishti.server.bi.plan.LogicalPlan;
import com.ash.drishti.server.bi.plan.PlanNode;
import com.ash.drishti.server.bi.plan.Planner;
import com.ash.drishti.server.bi.plan.SecuritySignature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The model query's side of the logical plan (docs/architecture/BI_DESIGN.md section 3.6: MQ is the plan's semantic leaf): a checked and compiled query
 * as the planner's input (the model at its fingerprint, the datasets by alias at their versions, the question with its {@code top} filters as asked and its
 * relative dates resolved, the time, the ids), and the reader's security as the signature sees it. Drishti SQL compiles to the same model query, so it gets
 * the same plan and the same hash.
 */
public final class MqPlans {

    private MqPlans() {}

    public static LogicalPlan plan(ModelView v, MqValidator.Checked c, MqCompiler.Compiled compiled, SecuritySignature.Reader reader) {
        return Planner.plan(input(v, c, compiled), reader);
    }

    static Planner.Input input(ModelView v, MqValidator.Checked c, MqCompiler.Compiled compiled) {
        ModelQuery q = c.query();
        QueryRequest main = compiled.main();
        Map<String, Planner.Source> sources = new LinkedHashMap<>();
        v.datasets().forEach((alias, r) -> sources.put(alias, source(r)));
        int tops = (int) q.where().stream().filter(w -> "top".equals(w.op())).count();
        int having = q.having().size();
        List<QueryRequest.Filter> filters = main.filters();
        int context = filters.size() - tops - having;
        List<PlanNode.Cond> rows = new ArrayList<>();
        for (int i = 0; i < context; i++) {
            QueryRequest.Filter f = filters.get(i);
            rows.add(new PlanNode.Cond(f.field(), f.op(), f.values(), i));
        }
        int i = context;
        for (ModelQuery.Where w : q.where()) {
            if ("top".equals(w.op())) {
                List<Object> spec = new ArrayList<>();
                spec.add(w.n() == null ? 0 : w.n());
                spec.add(w.by());
                spec.add(w.dir() == null ? "desc" : w.dir());
                rows.add(new PlanNode.Cond(w.field(), "top", spec, i++));
            }
        }
        List<PlanNode.Cond> hav = new ArrayList<>();
        for (int k = context + tops; k < filters.size(); k++) {
            QueryRequest.Filter f = filters.get(k);
            hav.add(new PlanNode.Cond(f.field(), f.op(), f.values(), k));
        }
        String id = v.def().id() == null || v.def().id().isBlank() ? v.id() : v.def().id();
        return new Planner.Input(compiled.dataset(), id, v.hash(), sources, main, rows, hav, time(q, compiled), q.rows(), compiled.totals().stream().map(MqCompiler.Level::by).toList(),
                Planner.ids(v.ids()), new LinkedHashSet<>(compiled.guards().values()));
    }

    private static Planner.Source source(ResolvedDataset r) {
        return r == null ? new Planner.Source("", "") : new Planner.Source(r.def().name(), r.def().version());
    }

    /** The resolved time of a question: {@code as-of D}, {@code window D1..D2}, {@code last PT1H}, {@code live}, or {@code current D} (the business date). */
    static String time(ModelQuery q, MqCompiler.Compiled compiled) {
        ModelQuery.Time t = q.time();
        if (t.asOf() != null) {
            return "as-of " + t.asOf();
        }
        if (t.from() != null) {
            return "window " + t.from() + ".." + t.to();
        }
        if (t.last() != null) {
            return "last " + t.last();
        }
        if (t.live()) {
            return "live";
        }
        return "current " + compiled.asOf();
    }

    /** The reader of a bound model, as the signature and the security rewriter see them. */
    static SecuritySignature.Reader reader(MqValidator.Context ctx) {
        return new SecuritySignature.Reader(String.valueOf(ctx.attributes().getOrDefault("name", java.util.List.of(""))), ctx.enforce(), ctx.roles(), ctx.attributes(),
                ctx.masks(), java.util.Set.of(), java.util.Set.of());
    }
}
