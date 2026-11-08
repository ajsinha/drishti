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
package com.ash.drishti.banking.var;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.Dim;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.banking.frtb.FrtbService;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The MR lens of the pivot screen beside the pivot engine's answers (PIVOT_GUIDE.md#29-the-mr-cube-preset sections 5 to 8; layout A and tabs B and C of
 * docs/architecture/LENSES.md section 6.7): the headline strip of the view's scope, a cell's tail and histogram, the sparklines (the same view on the
 * open dates before, from the pivot engine's cache when it can), the time-travel slider's stops (a cell's move is explained by the one
 * Explain, kind var on the cell's scope), the sensitivity ladder and the factor heat map ({@link MrSensitivities}), and the what-if side by side
 * per row (the Java what-if on each row's scope). The grid itself is the pivot engine's ({@code /api/v1/pivot/query} on source {@code var});
 * every figure here is the server's, the console only draws.
 */
public final class MrCubeExtras {

    /** The most of the lake's own dates the slider lists (newest first). */
    static final int HELD_DATES = 20;
    private final VarService svc;
    private final VarWhatIf whatIf;
    private final PivotCore core;
    private final VarPivotProvider provider;
    private final MrSensitivities sensitivities;
    private final com.ash.drishti.banking.RiskLimits limits;

    MrCubeExtras(VarService svc, PivotCore core, VarPivotProvider provider, com.ash.drishti.banking.RiskLimits limits) {
        this.svc = svc;
        this.whatIf = new VarWhatIf(svc);
        this.core = core;
        this.provider = provider;
        this.sensitivities = new MrSensitivities(provider.props(), provider.frtb(), limits);
        this.limits = limits == null ? com.ash.drishti.banking.RiskLimits.defaults() : limits;
    }

    VarPivotProvider provider() {
        return provider;
    }

    private static Viewer viewer(Principal p) {
        return new Viewer(p.user(), p);
    }

    /** A view of the VaR source (the MR lens reads only that source). */
    static PivotView mrView(PivotView v) {
        if (!VarPivotProvider.SOURCE.equals(v.source())) {
            throw PivotView.bad("the MR lens reads the VaR source: \"source\": \"var\" (not " + v.source() + ")");
        }
        return v;
    }

    /**
     * The headline strip of a view (layout A, A2): its scope's VaR and ES through the lens, the limit and its use when the scope is a node
     * with one, the stressed VaR when the stress set is open, the data (version, batches, intraday) and the trades and positions.
     */
    public Map<String, Object> headline(Principal p, PivotView view) {
        mrView(view);
        VarPivotLens o = provider.open(viewer(p), view.date(), view.options());
        VarCalc.Measures m = o.base.measures();
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("scope", o.base.res().canonical());
        h.put("date", o.date().toString());
        h.put("var", m.var());
        h.put("es", m.es());
        h.put("k", m.k());
        h.put("esCount", m.esCount());
        h.put("scenarios", o.base.vector().length);
        h.put("lens", o.lens.label());
        h.put("lensKey", o.lens.key());
        h.put("confidence", o.lens.confidence());
        h.put("esConfidence", o.lens.esConfidence());
        h.put("currency", o.base.date().currency());
        h.put("trades", o.base.set().size());
        java.util.Set<Integer> positions = new java.util.HashSet<>();
        o.base.set().forEach(x -> positions.add(o.book.positionOf(x)));
        positions.remove(-1);
        h.put("positions", positions.size());
        h.put("hiddenByRole", o.base.hidden());
        VarBook.Node n = o.base.res().node() == null ? null : o.book.node(o.base.res().node());
        if (n != null && !Double.isNaN(n.limit())) {
            h.put("limit", n.limit());
            if (o.limitOnBasis(n)) {
                h.put("limitUse", m.var() / n.limit());
                h.put("limitStatus", limits.status(m.var() / n.limit()));   // within, near (drishti.risk.limit-near), breach
            } else {
                h.put("limitNote", "the limit is set for another horizon or confidence than the view's");
            }
        }
        h.put("limitNear", limits.limitNear());
        boolean stressedOpen = svc.stressedOpen(o.date());
        h.put("stressedOpen", stressedOpen);
        Object s = stressedOpen ? o.stressed(o.scope) : "no stress set is open for " + o.date();
        if (s instanceof Double d) {
            h.put("stressedVar", d);
        } else {
            h.put("stressedNote", s);
        }
        h.put("dataVersion", o.base.date().version());
        h.put("batches", o.base.date().intake() == null ? List.of() : o.base.date().intake().batchIds());
        h.put("intraday", VarViews.intradayNote(o.base.date()));
        h.put("provenance", o.provenance());
        return h;
    }

    /** The plan of a view for one cell (the MR lens asks the provider directly for a cell's vector). */
    private static AggregationPlan plan(PivotView view, Map<String, String> coords) {
        Map<String, Dim> dims = new LinkedHashMap<>();
        coords.keySet().forEach(d -> dims.put(d, new Dim(d, null)));
        return new AggregationPlan(view.source(), view.date(), view.asOf(), view.filters(), dims, List.of(), List.of(), null, 0, null,
                view.options());
    }

    /** A cell's tail and histogram (layout A, A6; tab B, B3): its summed vector through the view's lens, the VaR and ES scenarios marked. */
    public Map<String, Object> detail(Principal p, PivotView view, Map<String, String> coords) {
        mrView(view);
        VarPivotProvider.CellVector c = provider.cell(viewer(p), plan(view, coords), coords);
        if (c.members().isEmpty()) {
            throw new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "no trade of the view is in the cell " + coords);
        }
        Map<String, Object> out = new LinkedHashMap<>(c.lens().detail(c.oneDay(), c.members(), c.scope()));
        out.put("coords", coords);
        out.put("date", c.lens().date().toString());
        return out;
    }

    /**
     * The sparklines of a view (layout A, A5): the same view on each open business date up to its own (at most
     * {@code drishti.cube.mr.sparkline-dates}; a date not open is not opened for a sparkline), its first measure per visible cell, oldest
     * first; the cells beyond {@code sparkline-max-cells} have none.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> history(Principal p, PivotView view) {
        mrView(view);
        Viewer v = viewer(p);
        Map<String, Object> now = core.query(v, view);
        LocalDate upTo = LocalDate.parse(String.valueOf(now.get("date")));
        List<LocalDate> dates = new ArrayList<>(new TreeSet<>(svc.openDates()));
        dates.removeIf(d -> d.isAfter(upTo));
        int n = provider.props().sparklineDates();
        dates = dates.subList(Math.max(0, dates.size() - n), dates.size());
        Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) now.get("cells");
        List<String> keys = new ArrayList<>(cells.keySet());
        int max = provider.props().sparklineMaxCells();
        if (keys.size() > max) {
            keys = keys.subList(0, max);
        }
        String measure = String.valueOf(((List<Map<String, Object>>) now.get("measures")).get(0).get("label"));
        Map<String, List<Double>> series = new LinkedHashMap<>();
        keys.forEach(k -> series.put(k, new ArrayList<>()));
        List<String> asked = new ArrayList<>();
        for (LocalDate d : dates) {
            Map<String, Map<String, Object>> at = d.equals(upTo) ? cells
                    : (Map<String, Map<String, Object>>) core.query(v, view.on(d.toString())).get("cells");
            asked.add(d.toString());
            for (String k : keys) {
                Map<String, Object> e = at.get(k);
                Object x = e == null ? null : ((Map<String, Object>) e.get("values")).get(measure);
                series.get(k).add(x instanceof Number num ? num.doubleValue() : null);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("measure", measure);
        out.put("dates", asked);
        out.put("series", series);
        out.put("cells", keys.size());
        if (cells.size() > keys.size()) {
            out.put("note", "sparklines for the first " + keys.size() + " of " + cells.size() + " cells (drishti.cube.mr.sparkline-max-cells)");
        }
        out.put("how", "the same view on each open business date, every figure the engine's; a date not open here is not opened for a sparkline");
        return out;
    }

    /**
     * The slider's stops (layout A, A4): every business date with an official record or open on this server, and the lake's latest dates,
     * newest last, each with its batches (the morning's full runs, then the intraday ones in order) when it is open. Raw lake versions are
     * not stops (decision P3).
     */
    public Map<String, Object> versions(Principal p) {
        Set<LocalDate> dates = new TreeSet<>(svc.official().dates());
        dates.addAll(svc.openDates());
        LocalDate d0 = svc.loader().latest();
        for (int i = 0; d0 != null && i < HELD_DATES; i++) {
            dates.add(d0);
            d0 = svc.loader().previous(d0);
        }
        Map<LocalDate, Map<String, Object>> open = new HashMap<>();
        for (Map<String, Object> m : svc.loaded()) {
            if (m.get("currency") == null && m.get("businessDate") != null && m.get("refused") == null) {
                open.put(LocalDate.parse(String.valueOf(m.get("businessDate"))), m);
            }
        }
        Set<LocalDate> official = svc.official().dates();
        List<Map<String, Object>> stops = new ArrayList<>();
        for (LocalDate d : dates) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("date", d.toString());
            s.put("official", official.contains(d));
            Map<String, Object> o = open.get(d);
            s.put("open", o != null);
            if (o != null) {
                s.put("dataVersion", o.get("version"));
                s.put("batches", o.get("batchIds"));
                s.put("intraday", o.get("intraday"));
                s.put("trades", o.get("trades"));
            }
            stops.add(s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dates", stops);
        out.put("newest", stops.isEmpty() ? null : stops.get(stops.size() - 1).get("date"));
        out.put("note", "the stops are business dates (with an official record, open here, or held by the lake) and, within an open date, its "
                + "batches in order; a date not open is opened when it is chosen");
        return out;
    }

    /** The sensitivity ladder or the factor heat map of a scope (tab C, C2; tab B, B4). */
    public Map<String, Object> sensitivities(Principal p, String scope, LocalDate date, String grid, String by, String riskClass, String measure,
            String ccy) {
        return sensitivities.answer(p, scope, date, grid, by, riskClass, measure, ccy);
    }

    /**
     * The what-if side by side (tab C, C3): the same hypothetical trades on each row of the view's first row dimension (at most
     * {@code drishti.cube.mr.what-if-rows}) and on its total, before, after and incremental, each the Java what-if on the row's scope.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> whatIf(Principal p, PivotView view, Object items) {
        mrView(view);
        PivotView rows = view.reshaped(view.rows().isEmpty() ? List.of() : List.of(view.rows().get(0)), List.of(),
                List.of(new PivotView.Value("var()", "VaR", null)));
        Map<String, Object> a = core.query(viewer(p), rows);
        Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) a.get("cells");
        List<Map<String, Object>> cols = (List<Map<String, Object>>) a.get("columns");
        String grandCol = String.valueOf(cols.get(cols.size() - 1).get("key"));
        List<String> scopes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int max = provider.props().whatIfRows();
        Map<String, Object> grand = null;
        for (Map<String, Object> r : (List<Map<String, Object>>) a.get("rows")) {
            Map<String, Object> cell = cells.get(r.get("key") + "|" + grandCol);
            if ("grand".equals(r.get("kind"))) {
                grand = cell;
                continue;
            }
            if (scopes.size() < max && cell != null) {
                scopes.add((String) cell.get("scope"));
                labels.add(String.valueOf(r.get("label")));
            }
        }
        if (grand != null) {
            scopes.add((String) grand.get("scope"));
            labels.add("Total");
        }
        LocalDate date = VarPivotProvider.day(String.valueOf(a.get("date")));
        String ccy = view.options().get("ccy");
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < scopes.size(); i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("label", labels.get(i));
            r.put("scope", scopes.get(i));
            if (scopes.get(i) == null) {
                r.put("note", "no scope text names this row");
                out.add(r);
                continue;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("scope", scopes.get(i));
            body.put("items", items);
            if (ccy != null) {
                body.put("ccy", ccy);
            }
            try {
                Map<String, Object> w = whatIf.whatIf(p, body, date);
                r.put("before", ((Map<?, ?>) w.get("before")).get("var"));
                r.put("after", ((Map<?, ?>) w.get("after")).get("var"));
                r.put("incremental", ((Map<?, ?>) w.get("change")).get("var"));
                r.put("items", w.get("items"));
            } catch (DrishtiException e) {
                r.put("note", e.getMessage());
            }
            out.add(r);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("rows", out);
        res.put("date", a.get("date"));
        res.put("view", view.toMap());
        res.put("engine", "the Java what-if (VarWhatIfMath) on each row's scope: a booked trade's vector copied and scaled, added");
        return res;
    }

    /** A class's delta as the sensitivities name it: DV01 for interest rates, CS01 for credit spreads, DELTA for the others. */
    static String deltaWord(String cls) {
        return cls.startsWith("GIRR") ? "DV01" : cls.startsWith("CSR") ? "CS01" : "DELTA";
    }

    /** True when an element's measure is the one asked; DV01, CS01 and DELTA name the same first-order sensitivity. */
    static boolean isDelta(String asked, String measure) {
        if (measure == null) {
            return false;
        }
        if (asked.equalsIgnoreCase(measure)) {
            return true;
        }
        List<String> delta = List.of("DELTA", "DV01", "CS01");
        return delta.contains(asked) && delta.contains(measure.toUpperCase(java.util.Locale.ROOT));
    }

    /** The FRTB-SA engine when it runs here and its parameter files were read, else null. */
    public static FrtbService running(FrtbService s) {
        return s != null && s.props().enabled() && s.problem() == null ? s : null;
    }
}
