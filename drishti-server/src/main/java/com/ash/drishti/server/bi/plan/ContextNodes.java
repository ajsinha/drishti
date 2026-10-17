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
package com.ash.drishti.server.bi.plan;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.expr.DelPrinter;
import com.ash.drishti.server.bi.expr.Expressions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The {@code Context} nodes of a measure (docs/architecture/BI_DESIGN.md sections 3.6 and 4.2): each filter-context or time-travel function the measure (or a
 * measure it names) calls becomes a {@link PlanNode.Context} over its own aggregate, at the grain and with the filters the function leaves, beside the main
 * aggregate in the plan's {@code Meet}. The context's scans are ordinary scans, so the security rewriter secures them like any other (security is never in
 * scope). What the engine runs is the context sub-query of the SQL compiler; these nodes are what the plan hash, explain and the incremental class see.
 */
final class ContextNodes {

    /** The functions that are their own sub-plan (a {@code calc} with only {@code where=}, {@code closing}, {@code opening} along the answer are filters). */
    private static final Set<String> SUB_PLANS = Set.of("calc", "share_of", "parent_of", "avg_by", "avg_over", "as_of", "last", "first");

    private ContextNodes() {}

    static List<PlanNode> of(Dataset ds, String measure, List<PlanNode> scans, List<PlanNode.Cond> conds, String fact, List<String> fields) {
        List<PlanNode> out = new ArrayList<>();
        collect(ds, measure, measure, scans, conds, fact, fields, out, new LinkedHashSet<>());
        return out;
    }

    private static void collect(Dataset ds, String top, String measure, List<PlanNode> scans, List<PlanNode.Cond> conds, String fact, List<String> fields, List<PlanNode> out,
            Set<String> seen) {
        Dataset.Measure m = ds.measures().get(measure);
        if (m == null || m.expr() == null || !seen.add(measure)) {
            return;
        }
        Node n;
        try {
            n = Expressions.parse(m.expr());
        } catch (RuntimeException e) {
            return;
        }
        Node.walk(n, x -> {
            if (x instanceof Node.MeasureRef r) {
                collect(ds, top, r.name(), scans, conds, fact, fields, out, seen);
            } else if (x instanceof Node.Call c && SUB_PLANS.contains(c.function()) && !whereOnly(c)) {
                out.add(node(c, top, scans, conds, fact, fields));
            }
        });
    }

    private static boolean whereOnly(Node.Call c) {
        return "calc".equals(c.function()) && Node.keyword(c, "ignore") == null && Node.keyword(c, "keep") == null && Node.keyword(c, "via") == null;
    }

    private static PlanNode node(Node.Call c, String measure, List<PlanNode> scans, List<PlanNode.Cond> conds, String fact, List<String> fields) {
        Map<String, Object> edits = new TreeMap<>();
        List<Node> pos = Node.positional(c);
        edits.put("aggregate", DelPrinter.print(pos.get(0)));
        Set<String> dropped = new LinkedHashSet<>();
        boolean all = false;
        for (Node a : c.args()) {
            if (a instanceof Node.Call k && k.function().startsWith(Node.KEYWORD)) {
                String key = k.function().substring(Node.KEYWORD.length());
                List<String> items = k.args().stream().map(DelPrinter::print).toList();
                edits.put(key, items.size() == 1 && !List.of("ignore", "keep", "over", "by").contains(key) ? items.get(0) : items);
                if (List.of("ignore", "over").contains(key)) {
                    if (items.equals(List.of("ALL"))) {
                        all = true;
                    }
                    dropped.addAll(items);
                }
            }
        }
        for (int i = 1; i < pos.size(); i++) {
            edits.put(i == 1 ? "date" : "arg" + i, DelPrinter.print(pos.get(i)));
        }
        Object keep = edits.get("keep");
        List<String> by = new ArrayList<>();
        List<PlanNode.Cond> kept = new ArrayList<>();
        for (String f : fields) {
            if (!all && !dropped.contains(column(f)) && !dropped.contains(f) && (!(keep instanceof List<?> k) || k.contains(column(f)) || k.contains(f))) {
                by.add(f);
            }
        }
        for (PlanNode.Cond cond : conds) {
            String f = cond.field();
            if (!all && !dropped.contains(column(f)) && !dropped.contains(f) && (!(keep instanceof List<?> k) || k.contains(column(f)) || k.contains(f))) {
                kept.add(cond);
            }
        }
        if (edits.get("by") instanceof List<?> extra) {
            extra.forEach(x -> {
                if (!by.contains(String.valueOf(x))) {
                    by.add(String.valueOf(x));
                }
            });
        }
        PlanNode agg = new PlanNode.Aggregate(List.of(new PlanNode.Filter(scans, kept)), fact, by, List.of(measure), null);
        return new PlanNode.Context(agg, c.function(), edits);
    }

    /** {@code table.column} of a field (a date part counts as its column). */
    private static String column(String field) {
        String[] p = field.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] : field;
    }
}
