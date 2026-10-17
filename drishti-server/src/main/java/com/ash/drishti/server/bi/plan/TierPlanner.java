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
import com.ash.drishti.server.bi.engine.ConnectorCapabilities;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Stage 7 of the pipeline (docs/architecture/BI_DESIGN.md sections 4.1 and 4.3): every node of the optimised plan is placed on a tier. A scan of a
 * {@code direct} table whose connector is a SQL store runs at the <b>source</b>, and so does an aggregate (with its filters and semi-joins) whose every
 * scan is in that one store and whose connector aggregates (its capability descriptor says so); the {@code Py*} nodes run on the <b>console</b> backend;
 * everything else (layer and rollup reads, live tables, contexts, meets, windows, totals, masks) on the <b>Java</b> tier. The browser draws. The placement is
 * not part of the plan hash: explain shows it with the times each tier took. Pure.
 */
public final class TierPlanner {

    public static final String SOURCE = "source";
    public static final String JAVA = "java";
    public static final String CONSOLE = "console";

    /** Each node's tier (by identity) and each scan's layer ({@code columns}, {@code live}, {@code rollup <name>}, {@code direct}). */
    public record Placement(Map<PlanNode, String> tiers, Map<PlanNode, String> layers) {
        public String tier(PlanNode n) {
            return tiers.getOrDefault(n, JAVA);
        }

        /** How many nodes each tier runs (for the answer's meta). */
        public Map<String, Integer> counts() {
            Map<String, Integer> m = new LinkedHashMap<>();
            tiers.values().forEach(t -> m.merge(t, 1, Integer::sum));
            return m;
        }
    }

    private TierPlanner() {}

    /**
     * Places a plan.
     *
     * @param capabilities the descriptor of a table's connector (null when it does not run, or not known: a direct table is then assumed a SQL store)
     */
    public static Placement place(LogicalPlan p, Dataset flat, Function<Dataset.Table, ConnectorCapabilities> capabilities) {
        Map<PlanNode, String> tiers = new IdentityHashMap<>();
        Map<PlanNode, String> layers = new IdentityHashMap<>();
        visit(p.root(), flat, capabilities, tiers, layers, null);
        return new Placement(tiers, layers);
    }

    private static String visit(PlanNode n, Dataset flat, Function<Dataset.Table, ConnectorCapabilities> caps, Map<PlanNode, String> tiers, Map<PlanNode, String> layers,
            String rollup) {
        String tier;
        switch (n) {
            case PlanNode.SecureScan s -> {
                Dataset.Table t = flat.tables().get(s.alias());
                String mode = t == null ? "cached" : t.mode();
                ConnectorCapabilities c = t == null || caps == null ? null : caps.apply(t);
                boolean sql = c == null ? "direct".equals(mode) : c.sql();
                tier = "direct".equals(mode) && sql ? SOURCE : JAVA;
                layers.put(n, rollup != null ? "rollup " + rollup : "direct".equals(mode) ? "direct" : t != null && t.isLive() ? "live" : t != null && t.isHistory() ? "history"
                        : "columns");
            }
            case PlanNode.PyNode py -> {
                py.inputs().forEach(i -> visit(i, flat, caps, tiers, layers, null));
                tier = CONSOLE;
            }
            case PlanNode.Aggregate a -> {
                List<String> under = a.inputs().stream().map(i -> visit(i, flat, caps, tiers, layers, a.rollup())).toList();
                boolean allSource = !under.isEmpty() && under.stream().allMatch(SOURCE::equals);
                boolean aggregates = PlanNode.scans(a).stream().allMatch(s -> {
                    Dataset.Table t = flat.tables().get(s.alias());
                    ConnectorCapabilities c = t == null || caps == null ? null : caps.apply(t);
                    return c == null || c.aggregates();
                });
                tier = allSource && aggregates ? SOURCE : JAVA;
            }
            case PlanNode.Filter f -> {
                List<String> under = f.inputs().stream().map(i -> visit(i, flat, caps, tiers, layers, rollup)).toList();
                tier = !under.isEmpty() && under.stream().allMatch(SOURCE::equals) ? SOURCE : JAVA;
            }
            case PlanNode.SemiJoin sj -> {
                List<String> under = sj.inputs().stream().map(i -> visit(i, flat, caps, tiers, layers, rollup)).toList();
                tier = under.stream().allMatch(SOURCE::equals) ? SOURCE : JAVA;
            }
            default -> {
                n.inputs().forEach(i -> visit(i, flat, caps, tiers, layers, null));
                tier = JAVA;
            }
        }
        tiers.put(n, tier);
        return tier;
    }
}
