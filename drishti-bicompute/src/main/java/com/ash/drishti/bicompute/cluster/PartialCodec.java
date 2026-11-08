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
package com.ash.drishti.bicompute.cluster;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.AggregationPlan.FunctionCall;
import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.bicompute.core.CellSet;
import com.ash.drishti.bicompute.core.Dim;
import com.ash.drishti.bicompute.core.Partials;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.spi.PivotProvider;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plans and partial states as JSON-ready maps (ARCHITECTURE.md section 26.6): what a coordinator sends a node (the plan: source, date, filters,
 * dimensions, functions by their canonical text, cell sets, the node's shard or parts) and what the node answers (per set, per cell key, the
 * members and each function's state in its own codec, base64). The node parses the functions again on its own provider, so it applies its
 * entitlements and masks for the same person before any aggregation; nothing but states crosses.
 */
public final class PartialCodec {

    private PartialCodec() {}

    // ---- the plan ----------------------------------------------------------------------------------------------------------------------

    public static Map<String, Object> plan(AggregationPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", p.source());
        m.put("date", p.date());
        m.put("asOf", p.asOf());
        Map<String, Object> f = new LinkedHashMap<>();
        p.filters().forEach((k, v) -> f.put(k, Map.of("in", v.in(), "notIn", v.notIn())));
        m.put("filters", f);
        m.put("dims", new ArrayList<>(new java.util.TreeSet<>(p.dims().keySet())));
        m.put("functions", p.functions().stream().map(FunctionCall::id).toList());
        List<Map<String, Object>> sets = new ArrayList<>();
        for (CellSet s : p.sets()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("grouping", s.grouping());
            x.put("at", new LinkedHashMap<>(s.at()));
            x.put("exclude", new LinkedHashMap<>(s.exclude()));
            x.put("marks", new LinkedHashMap<>(s.marks()));
            sets.add(x);
        }
        m.put("sets", sets);
        if (p.shard() != null) {
            m.put("shard", List.of(p.shard().index(), p.shard().count()));
        }
        m.put("maxGroups", p.maxGroups());
        AggregationPlan.Money money = p.money();
        Map<String, Object> mm = new LinkedHashMap<>();
        mm.put("currencyField", money.currencyField());
        mm.put("fields", new ArrayList<>(new java.util.TreeSet<>(money.fields())));
        if (money.conversion() != null) {
            PivotView.Currency c = money.conversion();
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("report", c.report());
            cm.put("field", c.field());
            cm.put("rates", new LinkedHashMap<>(c.rates()));
            cm.put("source", c.source());
            mm.put("conversion", cm);
        }
        m.put("money", mm);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static AggregationPlan plan(Map<String, Object> m, PivotProvider provider) {
        Map<String, AggregationPlan.Filter> filters = new TreeMap<>();
        ((Map<String, Object>) m.getOrDefault("filters", Map.of())).forEach((k, v) -> {
            Map<String, Object> f = (Map<String, Object>) v;
            filters.put(k, new AggregationPlan.Filter(strings(f.get("in")), strings(f.get("notIn"))));
        });
        Map<String, Dim> dims = new LinkedHashMap<>();
        for (String d : strings(m.get("dims"))) {
            dims.put(d, Dim.parse(d));
        }
        List<FunctionCall> fns = new ArrayList<>();
        for (String f : strings(m.get("functions"))) {
            fns.add(com.ash.drishti.bicompute.functions.Sketches.parse(f, provider));     // a sketched call keeps its ≈
        }
        List<CellSet> sets = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("sets", List.of())) {
            Map<String, Object> s = (Map<String, Object>) o;
            Map<String, List<String>> ex = new LinkedHashMap<>();
            ((Map<String, Object>) s.getOrDefault("exclude", Map.of())).forEach((k, v) -> ex.put(k, strings(v)));
            sets.add(new CellSet(strings(s.get("grouping")), (Map<String, String>) s.getOrDefault("at", Map.of()), ex,
                    (Map<String, String>) s.getOrDefault("marks", Map.of())));
        }
        AggregationPlan.Shard shard = null;
        if (m.get("shard") instanceof List<?> l && l.size() == 2) {
            shard = new AggregationPlan.Shard(((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue());
        }
        int max = m.get("maxGroups") instanceof Number n ? n.intValue() : 0;
        AggregationPlan.Money money = AggregationPlan.Money.none();
        if (m.get("money") instanceof Map<?, ?> mm) {
            PivotView.Currency conv = null;
            if (mm.get("conversion") instanceof Map<?, ?> cm) {
                Map<String, Double> rates = new TreeMap<>();
                if (cm.get("rates") instanceof Map<?, ?> rm) {
                    rm.forEach((k, v) -> rates.put(String.valueOf(k), ((Number) v).doubleValue()));
                }
                conv = new PivotView.Currency(str(cm.get("report")), str(cm.get("field")), rates, str(cm.get("source")));
            }
            money = new AggregationPlan.Money(str(mm.get("currencyField")), new java.util.LinkedHashSet<>(strings(mm.get("fields"))), conv);
        }
        return new AggregationPlan(str(m.get("source")), str(m.get("date")), str(m.get("asOf")), filters, dims, fns, sets, shard, max, money);
    }

    // ---- the partial states ------------------------------------------------------------------------------------------------------------

    public static Map<String, Object> partials(AggregationPlan plan, Partials p) {
        Base64.Encoder b64 = Base64.getEncoder();
        List<Object> sets = new ArrayList<>();
        for (Partials.SetResult r : p.sets()) {
            List<Object> cells = new ArrayList<>();
            for (Map.Entry<CellKey, Partials.Entry> e : r.cells().entrySet()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("key", new LinkedHashMap<>(e.getKey().coords()));
                c.put("members", e.getValue().members());
                List<String> st = new ArrayList<>();
                for (int f = 0; f < plan.functions().size(); f++) {
                    Object s = e.getValue().states()[f];
                    st.add(s == null ? null : b64.encodeToString(plan.functions().get(f).fn().encode(s)));
                }
                c.put("states", st);
                if (e.getValue().scope() != null) {
                    c.put("scope", e.getValue().scope());
                }
                if (!e.getValue().notes().isEmpty()) {
                    Map<String, String> n = new LinkedHashMap<>();
                    e.getValue().notes().forEach((k, v) -> n.put(Integer.toString(k), v));
                    c.put("notes", n);
                }
                cells.add(c);
            }
            sets.add(cells);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sets", sets);
        m.put("stats", Map.of("queries", p.stats().queries(), "statements", p.stats().statements(), "millis", p.stats().millis(), "rowsRead",
                p.stats().rowsRead()));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Partials partials(AggregationPlan plan, Map<String, Object> m) {
        Base64.Decoder b64 = Base64.getDecoder();
        List<Object> sets = (List<Object>) m.get("sets");
        if (sets == null || sets.size() != plan.sets().size()) {
            throw new IllegalStateException("a node answered " + (sets == null ? 0 : sets.size()) + " cell sets; " + plan.sets().size() + " were asked");
        }
        List<Partials.SetResult> out = new ArrayList<>();
        for (int i = 0; i < sets.size(); i++) {
            TreeMap<CellKey, Partials.Entry> cells = new TreeMap<>();
            for (Object o : (List<Object>) sets.get(i)) {
                Map<String, Object> c = (Map<String, Object>) o;
                List<Object> st = (List<Object>) c.get("states");
                Object[] states = new Object[plan.functions().size()];
                for (int f = 0; f < states.length; f++) {
                    Object s = st.get(f);
                    states[f] = s == null ? null : plan.functions().get(f).fn().decode(b64.decode(String.valueOf(s)));
                }
                Map<Integer, String> notes = new LinkedHashMap<>();
                ((Map<String, Object>) c.getOrDefault("notes", Map.of())).forEach((k, v) -> notes.put(Integer.parseInt(k), String.valueOf(v)));
                cells.put(CellKey.of((Map<String, String>) c.get("key")), new Partials.Entry(((Number) c.get("members")).longValue(), states,
                        str(c.get("scope")), notes));
            }
            out.add(new Partials.SetResult(plan.sets().get(i), cells));
        }
        Map<String, Object> s = (Map<String, Object>) m.getOrDefault("stats", Map.of());
        Partials.Stats stats = new Partials.Stats(s.get("queries") instanceof Number n ? n.intValue() : 0, strings(s.get("statements")),
                s.get("millis") instanceof Number n ? n.longValue() : 0, s.get("rowsRead") instanceof Number n ? n.longValue() : -1);
        return new Partials(out, stats);
    }

    static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
