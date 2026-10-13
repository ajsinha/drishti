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
import com.ash.drishti.server.bi.dataset.Rollups;
import com.ash.drishti.server.bi.engine.Layers;
import com.ash.drishti.server.bi.expr.Expressions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

/**
 * Stage 6 (docs/architecture/BI_DESIGN.md section 4.2): rule-based and answer-preserving. In order: <b>predicate pushdown</b> (each of the query's row filters
 * into the scan of its dataset), <b>top-N</b> (a {@code top} filter as its own grouped sub-plan feeding the scan), <b>projection pruning</b> (each scan reads
 * the columns the plan uses, plus its row rules' and joins' columns), <b>rollup matching</b> (an aggregate a rollup covers: fields, filters and the row rules'
 * columns in its {@code by}, every measure's components stored). No rule touches a scan's security: a property test checks that the rules, masks and denials
 * of every scan come out as they went in. Grain split happens when the plan is bound ({@link Planner}); the result cache is looked up before the plan runs.
 */
public final class Optimiser {

    /** One rule: its name (for explain and the tests) and what it does to a plan. */
    public record Rule(String name, BiFunction<LogicalPlan, Dataset, LogicalPlan> apply) {}

    public static final List<Rule> RULES = List.of(new Rule("predicate-pushdown", Optimiser::pushdown), new Rule("top-n", Optimiser::topN),
            new Rule("projection-pruning", Optimiser::prune), new Rule("rollup-matching", Optimiser::rollups));

    private Optimiser() {}

    public static LogicalPlan optimise(LogicalPlan p, Dataset flat) {
        LogicalPlan out = p;
        for (Rule r : RULES) {
            out = r.apply().apply(out, flat);
        }
        return out;
    }

    // ---- predicate pushdown --------------------------------------------------------------------------------------------

    static LogicalPlan pushdown(LogicalPlan p, Dataset flat) {
        return p.withRoot(push(p.root()));
    }

    private static PlanNode push(PlanNode n) {
        if (n instanceof PlanNode.Filter f) {
            List<PlanNode.Cond> rest = new ArrayList<>();
            List<PlanNode> scans = new ArrayList<>(f.scans().stream().map(Optimiser::push).toList());
            for (PlanNode.Cond c : f.conditions()) {
                String alias = Planner.alias(c.field());
                boolean done = false;
                for (int i = 0; i < scans.size() && !done; i++) {
                    PlanNode moved = into(scans.get(i), alias, c);
                    if (moved != null) {
                        scans.set(i, moved);
                        done = true;
                    }
                }
                if (!done) {
                    rest.add(c);
                }
            }
            return rest.isEmpty() && scans.size() == 1 ? scans.get(0) : rest.isEmpty() ? new PlanNode.Filter(scans, List.of()) : new PlanNode.Filter(scans, rest);
        }
        return n.withInputs(n.inputs().stream().map(Optimiser::push).toList());
    }

    /** The node with the condition pushed into the scan of {@code alias} under it; null when no scan of that alias is there. */
    private static PlanNode into(PlanNode n, String alias, PlanNode.Cond c) {
        if (n instanceof PlanNode.SecureScan s) {
            if (!s.alias().equals(alias)) {
                return null;
            }
            List<PlanNode.Cond> pushed = new ArrayList<>(s.pushed());
            pushed.add(c);
            return s.withPushed(pushed);
        }
        List<PlanNode> in = new ArrayList<>(n.inputs());
        for (int i = 0; i < in.size(); i++) {
            PlanNode moved = into(in.get(i), alias, c);
            if (moved != null) {
                in.set(i, moved);
                return n.withInputs(in);
            }
        }
        return null;
    }

    // ---- top-N ---------------------------------------------------------------------------------------------------------

    /** A pushed {@code top} filter becomes a grouped sub-plan (the field by the measure, sorted, limited) semi-joined to the scan. */
    static LogicalPlan topN(LogicalPlan p, Dataset flat) {
        return p.withRoot(top(p.root()));
    }

    private static PlanNode top(PlanNode n) {
        if (n instanceof PlanNode.SecureScan s && s.pushed().stream().anyMatch(c -> "top".equals(c.op()))) {
            List<PlanNode.Cond> plain = s.pushed().stream().filter(c -> !"top".equals(c.op())).toList();
            PlanNode.SecureScan base = s.withPushed(plain);
            PlanNode out = base;
            for (PlanNode.Cond c : s.pushed()) {
                if (!"top".equals(c.op())) {
                    continue;
                }
                int k = c.values().isEmpty() ? 0 : ((Number) c.values().get(0)).intValue();
                String by = c.values().size() > 1 ? String.valueOf(c.values().get(1)) : "";
                boolean asc = c.values().size() > 2 && "asc".equalsIgnoreCase(String.valueOf(c.values().get(2)));
                PlanNode sub = new PlanNode.Limit(new PlanNode.Sort(new PlanNode.Aggregate(List.of(base), s.alias(), List.of(c.field()), List.of(by), null),
                        List.of(by + (asc ? " asc" : " desc"))), k);
                out = new PlanNode.SemiJoin(out, sub, "top " + k + " " + c.field() + " by " + by);
            }
            return out;
        }
        return n.withInputs(n.inputs().stream().map(Optimiser::top).toList());
    }

    // ---- projection pruning ----------------------------------------------------------------------------------------

    static LogicalPlan prune(LogicalPlan p, Dataset flat) {
        Map<String, Set<String>> used = new LinkedHashMap<>();
        for (PlanNode n : PlanNode.all(p.root())) {
            switch (n) {
                case PlanNode.Aggregate a -> {
                    a.by().forEach(f -> take(used, f));
                    a.measures().forEach(m -> measureColumns(flat, m, used, new LinkedHashSet<>()));
                    Set<String> aliases = new LinkedHashSet<>();
                    PlanNode.scans(a).forEach(s -> aliases.add(s.alias()));
                    for (Dataset.Relationship r : flat.relationships()) {
                        if (r.active() && aliases.contains(r.fromTable()) && aliases.contains(r.toTable())) {
                            used.computeIfAbsent(r.fromTable(), k -> new TreeSet<>()).add(r.fromColumn());
                            used.computeIfAbsent(r.toTable(), k -> new TreeSet<>()).add(r.toColumn());
                        }
                    }
                }
                case PlanNode.SecureScan s -> {
                    s.pushed().forEach(c -> take(used, c.field()));
                    s.rules().forEach(r -> ruleColumns(r, used));
                }
                default -> { }
            }
        }
        return p.withRoot(columns(p.root(), used));
    }

    private static PlanNode columns(PlanNode n, Map<String, Set<String>> used) {
        if (n instanceof PlanNode.SecureScan s) {
            return s.withColumns(List.copyOf(used.getOrDefault(s.alias(), new TreeSet<>())));
        }
        return n.withInputs(n.inputs().stream().map(i -> columns(i, used)).toList());
    }

    private static void take(Map<String, Set<String>> used, String field) {
        String[] parts = field.split("\\.");
        if (parts.length >= 2) {
            used.computeIfAbsent(parts[0], k -> new TreeSet<>()).add(parts[1]);
        }
    }

    private static void measureColumns(Dataset flat, String measure, Map<String, Set<String>> used, Set<String> seen) {
        Dataset.Measure m = flat.measures().get(measure);
        if (m == null || !seen.add(measure)) {
            return;
        }
        if (m.expr() == null) {
            m.inputs().forEach(i -> take(used, i));
            return;
        }
        try {
            Node.walk(Expressions.parse(m.expr()), x -> {
                if (x instanceof Node.Ref r && r.parts().size() >= 2) {
                    take(used, r.text());
                } else if (x instanceof Node.MeasureRef r) {
                    measureColumns(flat, r.name(), used, seen);
                }
            });
        } catch (RuntimeException e) {
            // a measure that does not parse is refused when it runs
        }
    }

    private static void ruleColumns(String rule, Map<String, Set<String>> used) {
        String text = rule.startsWith("filter ") ? rule.substring("filter ".length()) : rule;
        try {
            Node.walk(Expressions.parse(text), x -> {
                if (x instanceof Node.Ref r && r.parts().size() >= 2) {
                    take(used, r.text());
                }
            });
        } catch (RuntimeException e) {
            // the rule's text is the engine's to refuse
        }
    }

    // ---- rollup matching -----------------------------------------------------------------------------------------------

    /**
     * An aggregate over one fact table, with no filter on a measure, is answered from a rollup of that table when the rollup's {@code by} (with the business
     * date) holds every column the aggregate groups or filters on <em>and every column of the reader's row rules on the fact</em> (security first: else no
     * match), and the rollup stores every component of its measures. A dimension in the aggregate only for its row rules (its rules reach the fact) does not
     * stop the match when the fact joins it directly, many-to-one, on a column the rollup groups by: the rule keeps or drops each rollup row whole. The engine
     * makes the same choice when it runs (QueryService#route; a live table kept as a snapshot has no rollup to read).
     */
    static LogicalPlan rollups(LogicalPlan p, Dataset flat) {
        if (flat.rollups().isEmpty() || PlanNode.all(p.root()).stream().anyMatch(PlanNode.Having.class::isInstance)) {
            return p;
        }
        return p.withRoot(rollup(p.root(), flat));
    }

    private static PlanNode rollup(PlanNode n, Dataset flat) {
        if (n instanceof PlanNode.Aggregate a) {
            List<PlanNode.SecureScan> scans = PlanNode.scans(a);
            if (a.measures().isEmpty() || PlanNode.all(a).stream().anyMatch(x -> x instanceof PlanNode.Dedup || x instanceof PlanNode.Regroup)) {
                return a;
            }
            PlanNode.SecureScan s = scans.stream().filter(x -> x.alias().equals(a.fact())).findFirst().orElse(null);
            if (s == null) {
                return a;
            }
            Map<String, Set<String>> needed = new LinkedHashMap<>();
            a.by().forEach(f -> take(needed, f));
            s.pushed().forEach(c -> take(needed, c.field()));
            s.rules().forEach(r -> ruleColumns(r, needed));
            Set<String> cols = new LinkedHashSet<>(needed.getOrDefault(s.alias(), Set.of()));
            for (PlanNode.SecureScan o : scans) {
                if (o == s) {
                    continue;
                }
                Dataset.Relationship rel = flat.relationships().stream().filter(r -> r.active() && r.fromTable().equals(s.alias()) && r.toTable().equals(o.alias())
                        && !"one-to-many".equals(r.cardinality()) && !"many-to-many".equals(r.cardinality())).findFirst().orElse(null);
                Dataset.TableSecurity sec = flat.security().tables().get(o.alias());
                if (rel == null || sec == null || !sec.dimension() || !o.pushed().isEmpty() || needed.containsKey(o.alias())) {
                    return a;                                                  // a table read for more than its rules, or not joined directly from the fact
                }
                cols.add(rel.fromColumn());
            }
            for (Dataset.Rollup r : flat.rollups()) {
                if (!r.table().equals(s.alias())) {
                    continue;
                }
                Set<String> by = new LinkedHashSet<>(r.by());
                by.add(Dataset.DATE);
                if (!by.containsAll(cols)) {
                    continue;
                }
                Map<String, String> have = Layers.componentColumns(flat, r);
                boolean ok = a.measures().stream().allMatch(m -> {
                    Map<String, Node.Call> need = Rollups.components(flat, flat.measures().get(m), s.alias());
                    return need != null && have.keySet().containsAll(need.keySet());
                });
                if (ok) {
                    return a.withRollup(r.name());
                }
            }
            return a;
        }
        return n.withInputs(n.inputs().stream().map(i -> rollup(i, flat)).toList());
    }
}
