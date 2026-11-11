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
package com.ash.drishti.bicompute.rollup;

import com.ash.drishti.kernel.api.pivot.AggregationPlan;
import com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall;
import com.ash.drishti.kernel.api.pivot.CellKey;
import com.ash.drishti.kernel.api.pivot.CellSet;
import com.ash.drishti.kernel.api.pivot.Partials;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.ScaleOptions;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import com.ash.drishti.kernel.api.pivot.Viewer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Aggregate awareness (HOW_IT_FITS.md section A26.6): a plan is answered from the smallest current rollup whose grain gives every dimension
 * the plan names (its groupings, restrictions, exclusions and filters; a date coarsened where the grain holds it finer) and that keeps every
 * function of the plan; the rollup's rows are merged into the plan's cells in key order, so the answer equals the scan's (sums to the last
 * compensated digit, counts, Welford parts, sketch registers exactly). Only for a person who reads every row and field of the source (a
 * rollup has aggregated already, and row rules and masks apply before aggregation), never with {@code asOf} (a rollup is at the current
 * version). The answer names the rollup.
 *
 * <p>It also counts the plans that scanned for want of a rollup, by grain and functions, and proposes the rollups that would have answered
 * the most of them ({@link #advice}).
 */
public final class AggregateAwareness implements PivotCore.Hooks {

    private final Rollups rollups;
    private final int adviseAfterScans;
    private final Map<String, Asked> asked = new ConcurrentHashMap<>();

    /** Scans a rollup would have answered, by source, grain and functions. */
    static final class Asked {
        final String source;
        final Set<String> grain;
        final Set<String> functions;
        final LongAdder times = new LongAdder();
        final LongAdder millis = new LongAdder();

        Asked(String source, Set<String> grain, Set<String> functions) {
            this.source = source;
            this.grain = grain;
            this.functions = functions;
        }
    }

    public AggregateAwareness(Rollups rollups, int adviseAfterScans) {
        this.rollups = rollups;
        this.adviseAfterScans = Math.max(1, adviseAfterScans);
    }

    public Rollups rollups() {
        return rollups;
    }

    @Override
    public Optional<PivotCore.Routed> route(Viewer v, PivotProvider p, AggregationPlan plan, ScaleOptions o) {
        if (plan.asOf() != null || plan.shard() != null || o.sampled() || !o.rollups()) {
            return Optional.empty();                                    // "use rollups" off: the source answers (the screen's switch)
        }
        RollupSource src = rollups.sources().get(p.id());
        if (src == null || !src.unrestricted(v, plan.source(), plan.date())) {
            return Optional.empty();
        }
        if ((plan.money().guard() || plan.money().conversion() != null) && plan.functions().stream().anyMatch(f -> plan.money().money(f))) {
            return Optional.empty();       // money per cell in one currency, or converted: the scan says and converts; a rollup keeps neither
        }
        for (Rollup r : rollups.current(plan.source(), plan.date())) {
            Map<String, Derivation.Rule> rules = rules(plan, r, p, v);
            int[] fmap = functionMap(plan, r);
            if (rules != null && fmap != null) {
                long t0 = System.nanoTime();
                Partials parts = answer(plan, r, rules, fmap);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                String how = "answered from the rollup " + r.def().id() + " (" + r.def().title() + "; " + String.format(Locale.ROOT, "%,d", r.rows())
                        + " rows of partial states at " + r.version() + "), not by reading the table";
                return Optional.of(new PivotCore.Routed(new Partials(parts.sets(), new Partials.Stats(0, List.of("rollup " + r.def().id()),
                        ms, r.rows())), how, true));
            }
        }
        return Optional.empty();
    }

    /** Every dimension the plan names, with how the rollup's grain gives it; null when one is missing. */
    static Map<String, Derivation.Rule> rules(AggregationPlan plan, Rollup r, PivotProvider p, Viewer v) {
        Set<String> needed = new LinkedHashSet<>(plan.filters().keySet());
        for (CellSet s : plan.sets()) {
            needed.addAll(s.grouping());
            needed.addAll(s.at().keySet());
            needed.addAll(s.exclude().keySet());
        }
        Set<String> dates = dateFields(p, v, plan);
        Map<String, Derivation.Rule> rules = new LinkedHashMap<>();
        for (String d : needed) {
            Derivation.Rule rule = Derivation.rule(d, r.def().grain(), dates);
            if (rule == null) {
                return null;
            }
            rules.put(d, rule);
        }
        return rules;
    }

    private static Set<String> dateFields(PivotProvider p, Viewer v, AggregationPlan plan) {
        Set<String> out = new HashSet<>();
        for (PivotProvider.Field f : p.catalogue(v, plan.source(), plan.date()).fields()) {
            if ("date".equals(f.type())) {
                out.add(f.name());
            }
        }
        return out;
    }

    /** The rollup's function index for each of the plan's, or null when the rollup does not keep one of them. */
    static int[] functionMap(AggregationPlan plan, Rollup r) {
        int[] m = new int[plan.functions().size()];
        for (int i = 0; i < m.length; i++) {
            m[i] = r.index(plan.functions().get(i).id());
            if (m[i] < 0) {
                return null;
            }
        }
        return m;
    }

    /** The plan's cells from the rollup's rows: each row's coordinates derived, filtered, restricted, merged into its cell in key order. */
    static Partials answer(AggregationPlan plan, Rollup r, Map<String, Derivation.Rule> rules, int[] fmap) {
        List<FunctionCall> fns = plan.functions();
        List<Partials.SetResult> out = new ArrayList<>();
        List<Map.Entry<CellKey, Partials.Entry>> rows = new ArrayList<>(r.merged().entrySet());
        for (CellSet set : plan.sets()) {
            TreeMap<CellKey, Partials.Entry> cells = new TreeMap<>();
            for (Map.Entry<CellKey, Partials.Entry> row : rows) {
                Map<String, String> at = new TreeMap<>();
                boolean keep = true;
                for (Map.Entry<String, Derivation.Rule> e : rules.entrySet()) {
                    at.put(e.getKey(), Derivation.value(e.getValue(), row.getKey()));
                }
                for (Map.Entry<String, AggregationPlan.Filter> f : plan.filters().entrySet()) {
                    String val = at.get(f.getKey());
                    if (!f.getValue().in().isEmpty() && !f.getValue().in().contains(val) || f.getValue().notIn().contains(val)) {
                        keep = false;
                        break;
                    }
                }
                if (!keep) {
                    continue;
                }
                for (Map.Entry<String, String> a : set.at().entrySet()) {
                    if (!a.getValue().equals(at.get(a.getKey()))) {
                        keep = false;
                        break;
                    }
                }
                for (Map.Entry<String, List<String>> ex : set.exclude().entrySet()) {
                    if (keep && ex.getValue().contains(at.get(ex.getKey()))) {
                        keep = false;
                    }
                }
                if (!keep) {
                    continue;
                }
                Map<String, String> grouped = new TreeMap<>();
                for (String g : set.grouping()) {
                    grouped.put(g, at.get(g));
                }
                CellKey key = set.key(grouped);
                Object[] st = new Object[fns.size()];
                for (int f = 0; f < st.length; f++) {
                    Object s = row.getValue().states()[fmap[f]];
                    st[f] = fns.get(f).fn().decode(fns.get(f).fn().encode(s));      // a copy: the rollup's states are never shared
                }
                Partials.Entry have = cells.get(key);
                if (have == null) {
                    cells.put(key, new Partials.Entry(row.getValue().members(), st));
                } else {
                    Object[] m = new Object[st.length];
                    for (int f = 0; f < st.length; f++) {
                        m[f] = fns.get(f).fn().merge(have.states()[f], st[f]);
                    }
                    cells.put(key, new Partials.Entry(have.members() + row.getValue().members(), m));
                }
            }
            out.add(new Partials.SetResult(set, cells));
        }
        return new Partials(out, Partials.Stats.none());
    }

    @Override
    public void ran(Viewer v, PivotProvider p, AggregationPlan plan, Partials parts, long millis) {
        if (parts.stats().queries() == 0 || !rollups.sources().containsKey(p.id())) {
            return;                                        // answered by a rollup, or the provider has no rollups
        }
        Set<String> grain = new java.util.TreeSet<>(plan.filters().keySet());
        plan.sets().forEach(s -> {
            grain.addAll(s.grouping());
            grain.addAll(s.at().keySet());
        });
        Set<String> fns = new java.util.TreeSet<>();
        for (FunctionCall f : plan.functions()) {
            if (f.fn().sqlStateMergeable() && f.fn().kind() != com.ash.drishti.kernel.api.pivot.Kind.HOLISTIC) {
                fns.add(f.id());
            }
        }
        if (fns.isEmpty()) {
            return;
        }
        String key = plan.source() + "|" + grain + "|" + fns;
        Asked a = asked.computeIfAbsent(key, k -> new Asked(plan.source(), grain, fns));
        a.times.increment();
        a.millis.add(millis);
    }

    /** The rollups that would have answered views that scanned at least {@code advise-after-scans} times, most asked first. */
    public List<Map<String, Object>> advice() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Asked a : asked.values()) {
            long n = a.times.sum();
            if (n < adviseAfterScans) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", a.source);
            m.put("grain", List.copyOf(a.grain));
            m.put("functions", List.copyOf(a.functions));
            m.put("asked", n);
            m.put("averageMs", a.millis.sum() / Math.max(1, n));
            m.put("words", String.format(Locale.ROOT, "asked %,d times, about %s each: a rollup of %s by %s would answer them at once", n,
                    secs(a.millis.sum() / Math.max(1, n)), a.source, a.grain.isEmpty() ? "nothing (the grand total)" : String.join(" × ", a.grain)));
            out.add(m);
        }
        out.sort((x, y) -> Long.compare((long) y.get("asked"), (long) x.get("asked")));
        return out;
    }

    private static String secs(long ms) {
        return ms < 1000 ? ms + " ms" : ms < 90_000 ? String.format(Locale.ROOT, "%.0f s", ms / 1000.0)
                : String.format(Locale.ROOT, "%.0f min", ms / 60_000.0);
    }

    @Override
    public void estimated(Viewer v, PivotProvider p, AggregationPlan plan, Map<String, Object> m) {
        RollupSource src = rollups.sources().get(p.id());
        if (src == null || plan.asOf() != null) {
            return;
        }
        for (Rollup r : rollups.current(plan.source(), plan.date())) {
            if (rules(plan, r, p, v) != null && functionMap(plan, r) != null) {
                boolean usable = src.unrestricted(v, plan.source(), plan.date());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> options = m.get("options") instanceof List<?> l ? (List<Map<String, Object>>) l : new ArrayList<>();
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("id", "rollup");
                opt.put("rollup", r.def().id());
                opt.put("words", usable ? "Use the rollup " + r.def().title() + " (instant)" : "The rollup " + r.def().title() + " covers it, "
                        + "but not for you: your row rules or masks apply before aggregation");
                opt.put("seconds", usable ? 0.1 : -1);
                options.add(opt);
                m.put("options", options);
                m.put("rollup", r.def().id());
                return;
            }
        }
        m.put("rollup", null);
    }
}
