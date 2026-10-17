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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plan as people read it (docs/architecture/BI_DESIGN.md section 4.7): a tree of maps for {@code meta.plan} and an indented text for {@code EXPLAIN}.
 * A scan says <em>that</em> row rules applied (how many) and which columns are masked for the reader, never the rules' text: another reader's predicates and
 * the values of {@code user.*} stay on the server.
 */
public final class Explain {

    private Explain() {}

    public static Map<String, Object> tree(PlanNode n) {
        return tree(n, null);
    }

    /** The tree with each node's tier and each scan's layer (wave B: what stage 7 placed where). */
    public static Map<String, Object> tree(PlanNode n, TierPlanner.Placement placement) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", n.op());
        m.putAll(n.props());
        if (placement != null) {
            m.put("tier", placement.tier(n));
            if (placement.layers().containsKey(n)) {
                m.put("layer", placement.layers().get(n));
            }
        }
        if (n instanceof PlanNode.SecureScan s) {
            m.put("rules", s.rules().size());
            m.put("masked", s.masked());
            if (s.denied()) {
                m.put("denied", true);
            }
        }
        if (n instanceof PlanNode.Aggregate a && a.rollup() != null) {
            m.put("rollup", a.rollup());
        }
        List<Map<String, Object>> in = new ArrayList<>();
        n.inputs().forEach(i -> in.add(tree(i, placement)));
        if (!in.isEmpty()) {
            m.put("inputs", in);
        }
        return m;
    }

    /** The indented text: one line per node. */
    public static String text(LogicalPlan p, String signature) {
        return text(p, signature, null, Map.of());
    }

    /** The indented text with each node's tier and layer, and the times the tiers took (wave B: explain after execution). */
    public static String text(LogicalPlan p, String signature, TierPlanner.Placement placement, Map<String, Object> timings) {
        StringBuilder b = new StringBuilder();
        b.append("plan: ").append(p.hash()).append("   signature: ").append(signature).append("   time: ").append(p.time()).append('\n');
        if (!timings.isEmpty()) {
            b.append("timings: ").append(timings).append('\n');
        }
        line(p.root(), 0, b, placement);
        return b.toString();
    }

    private static void line(PlanNode n, int depth, StringBuilder b, TierPlanner.Placement placement) {
        b.append("  ".repeat(depth)).append("- ").append(n.op());
        switch (n) {
            case PlanNode.SecureScan s -> {
                b.append(' ').append(s.alias()).append(" (").append(s.dataset()).append(s.version() == null ? "" : "@" + s.version()).append(')');
                b.append("  rules: ").append(s.rules().isEmpty() ? "none" : s.rules().size() + " applied");
                if (!s.pushed().isEmpty()) {
                    b.append("  pushed: ").append(s.pushed().stream().map(PlanNode.Cond::text).toList());
                }
                if (!s.masked().isEmpty()) {
                    b.append("  masked: ").append(s.masked());
                }
                if (s.denied()) {
                    b.append("  NO ROWS FOR YOU");
                }
                if (!s.columns().isEmpty()) {
                    b.append("  columns: ").append(s.columns());
                }
            }
            case PlanNode.Aggregate a -> b.append(' ').append(a.fact()).append(" by ").append(a.by()).append(": ").append(a.measures())
                    .append(a.rollup() == null ? "" : "  (rollup " + a.rollup() + ")");
            default -> {
                if (!n.props().isEmpty()) {
                    b.append(' ').append(n.props());
                }
            }
        }
        if (placement != null) {
            b.append("   tier: ").append(placement.tier(n));
            if (placement.layers().containsKey(n)) {
                b.append("   layer: ").append(placement.layers().get(n));
            }
        }
        b.append('\n');
        n.inputs().forEach(i -> line(i, depth + 1, b, placement));
    }
}
