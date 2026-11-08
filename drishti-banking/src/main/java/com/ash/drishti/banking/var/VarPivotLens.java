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

import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.banking.compute.frtb.FrtbChoices;
import com.ash.drishti.banking.compute.var.Columns;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.banking.compute.var.VarLens;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.banking.frtb.FrtbService;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One pivot view of the VaR source opened for one person (PIVOT_GUIDE.md section 29.4): the view's scope ({@code options.scope}, default
 * {@code BANK}) as the person sees it on the date, through the view's lens (horizon, 10-day method, confidences, the normal or stress set,
 * the reporting currency), and the figures of a cell from its summed 1-day vector: VaR and ES through the lens, component VaR and ES against
 * the view's total (Euler: the parts add up to it), incremental VaR, PV, trades, the limit and its use, stressed VaR and FRTB-SA SBM per cell
 * scope, DV01 and CS01 from the position sensitivities. Every number is the VaR engine's arithmetic ({@code VarCalc}, {@code VarMath},
 * {@code VarLens}), the same as {@code /api/v1/risk/var/scope} gives for the cell's scope.
 */
final class VarPivotLens {

    /** The option names a view of the VaR source may carry. */
    static final Set<String> OPTIONS = Set.of("scope", "horizon", "method", "confidence", "esConfidence", "set", "ccy");

    final VarService svc;
    final Principal p;
    final String scope;
    final String ccy;
    final VarService.Scoped base;
    final VarLens lens;
    final VarCalc calc;
    final VarBook book;
    private final MrCubeProperties props;
    private final Supplier<FrtbService> frtb;
    private final Map<String, Map<String, Double>> sens = new HashMap<>();
    private int stressedAsked;
    private int sbmAsked;

    private VarPivotLens(VarService svc, Principal p, String scope, String ccy, VarService.Scoped base, VarLens lens, MrCubeProperties props,
            Supplier<FrtbService> frtb) {
        this.svc = svc;
        this.p = p;
        this.scope = scope;
        this.ccy = ccy;
        this.base = base;
        this.lens = lens;
        this.calc = lens.calc(svc.calc());
        this.book = base.date().book();
        this.props = props;
        this.frtb = frtb == null ? () -> null : frtb;
    }

    /**
     * Opens a view: its options checked (an unknown name or a lens the engine does not offer refused in words, DRS-5701), its scope resolved
     * as the person on the date (the VaR engine's refusals as they are).
     */
    static VarPivotLens open(VarService svc, Principal p, LocalDate date, Map<String, String> options, MrCubeProperties props,
            Supplier<FrtbService> frtb) {
        Map<String, String> o = options == null ? Map.of() : options;
        for (String k : o.keySet()) {
            if (!OPTIONS.contains(k)) {
                throw PivotView.bad("'" + k + "' is not an option of the VaR source; its options: scope, horizon (1d, 10d), method (sqrt10, sum), "
                        + "confidence, esConfidence, set (normal, stressed), ccy");
            }
        }
        String scope = o.getOrDefault("scope", "BANK").strip();
        String ccy = o.get("ccy");
        VarLens lens;
        try {
            String set = o.get("set");
            boolean any = List.of("horizon", "method", "confidence", "esConfidence", "set").stream().anyMatch(o::containsKey);
            lens = !any ? null : svc.props().lens(o.get("horizon"), o.get("method"), dbl(o.get("confidence")), dbl(o.get("esConfidence")),
                    set == null ? null : set.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw PivotView.bad(e.getMessage());
        }
        VarService.Scoped base = svc.scoped(p, scope.isEmpty() ? "BANK" : scope, date, ccy, lens);
        return new VarPivotLens(svc, p, scope.isEmpty() ? "BANK" : scope, ccy, base, lens == null ? svc.props().defaultLens() : lens, props, frtb);
    }

    private static Double dbl(String s) {
        if (s == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(s.strip().replace("%", ""));
            return s.contains("%") || d > 1 ? d / 100.0 : d;
        } catch (NumberFormatException e) {
            throw PivotView.bad("a confidence is a number (0.99 or 99%), not " + s);
        }
    }

    LocalDate date() {
        return base.date().date();
    }

    /** The cache part of everything this lens reads: the date's data, the viewer's sight, the scope and the lens. */
    String key() {
        VarDate d = base.date();
        return d.date() + "|" + d.currency() + "|" + d.version() + "|i" + d.intradayStamp() + "|" + base.view().signature() + "|"
                + base.res().canonical() + "|" + lens.key();
    }

    /** The key of the book's codes (who asks does not change them). */
    String bookKey() {
        VarDate d = base.date();
        return d.date() + "|" + d.currency() + "|" + d.version() + "|i" + d.intradayStamp() + "|" + (lens.stressed() ? "S" : "N");
    }

    double[] lensed(double[] oneDay) {
        return lens.equals(svc.props().defaultLens()) && !lens.tenDay() ? oneDay : lens.apply(oneDay);
    }

    VarCalc.Measures measuresOf(double[] oneDay, double[] lensed) {
        return lensed == oneDay ? calc.measures(lensed) : lens.measures(svc.calc(), oneDay, lensed);
    }

    /** The sum of a set's trades' present values (NaN: the date has no PV column). */
    double pv(OrdinalSet set) {
        Columns tc = book.trades();
        if (!tc.has("PV")) {
            return Double.NaN;
        }
        double[] s = {0};
        set.forEach(o -> {
            double x = tc.number("PV", o);
            if (Double.isFinite(x)) {
                s[0] += x;
            }
        });
        return s[0];
    }

    /**
     * The VaR limit of a cell: a node's, when the cell is the node's whole membership as the person sees it (the view's total on a node
     * scope, a hierarchy level's member, a desk or book column naming one); null otherwise.
     */
    VarBook.Node limitNode(Map<String, String> coords, OrdinalSet cell, VarPivotCodes codes) {
        VarBook.Node n = null;
        if (coords.isEmpty()) {
            n = base.res().node() == null ? null : book.node(base.res().node());
        } else if (coords.size() == 1) {
            Map.Entry<String, String> e = coords.entrySet().iterator().next();
            if (VarPivotCodes.isLevel(e.getKey())) {
                String id = codes.codes(book, bookKey(), e.getKey()).nodeIds().get(e.getValue());
                n = id == null ? null : book.node(id);
            } else if (List.of("DESK", "TT_BOOK", "DIVISION", "LEGAL_ENTITY").contains(e.getKey())) {
                n = book.node((e.getKey().equals("LEGAL_ENTITY") ? "LEGAL~" : "MGMT~") + e.getValue());
            }
        }
        if (n == null || Double.isNaN(n.limit())) {
            return null;
        }
        OrdinalSet seen = base.view().full() ? n.members() : n.members().and(base.view().visible());
        return seen.equals(cell) ? n : null;
    }

    /** True when the node's limit is set for this view's horizon and confidence (otherwise its use is not shown). */
    boolean limitOnBasis(VarBook.Node n) {
        return svc.loader().limitBasis(date(), n.id()).matches(lens) && n.limit() != 0;
    }

    /** A cell's stressed VaR from its scope, the first {@code stressed-max-cells} of an evaluation; else the reason. */
    Object stressed(String cellScope) {
        if (cellScope == null) {
            return "no scope text names this cell, and stressed VaR is computed scope by scope";
        }
        if (++stressedAsked > props.stressedMaxCells()) {
            return "stressed VaR is computed for the first " + props.stressedMaxCells() + " cells of a view (drishti.cube.mr.stressed-max-cells)";
        }
        try {
            return svc.scoped(p, cellScope, date(), null, lens.withStressed(true)).measures().var();
        } catch (DrishtiException | IllegalStateException e) {
            return e.getMessage();
        }
    }

    /** A cell's FRTB-SA SBM capital from its scope, the first {@code sbm-max-cells} of an evaluation; else the reason. */
    Object sbm(String cellScope) {
        FrtbService f = frtb.get();
        if (f == null) {
            return "the FRTB-SA engine is off on this server";
        }
        if (cellScope == null) {
            return "no scope text names this cell, and SBM is computed scope by scope";
        }
        if (++sbmAsked > props.sbmMaxCells()) {
            return "SBM is computed for the first " + props.sbmMaxCells() + " cells of a view (drishti.cube.mr.sbm-max-cells)";
        }
        try {
            return f.run(p, cellScope, date(), null, base.date().currency(), FrtbChoices.defaults()).result().sbm().sbm();
        } catch (DrishtiException e) {
            return e.getMessage();
        }
    }

    /** The view scope's GIRR (DV01) or CSR (CS01) deltas by trade ({@code T:}) and by position ({@code P:}), read once; null: engine off. */
    private Map<String, Double> sensitivities(String measure) {
        Map<String, Double> hit = sens.get(measure);
        if (hit != null) {
            return hit;
        }
        FrtbService f = frtb.get();
        if (f == null) {
            return null;
        }
        String cls = measure.equals("dv01") ? "GIRR" : "CSR";
        FrtbService.Elements el = f.elements(p, scope, date(), base.date().currency());
        Map<String, Double> byKey = new HashMap<>();
        for (FrtbService.Element e : el.elements()) {
            if (!MrCubeExtras.isDelta("DELTA", e.measure()) || e.factor() == null || e.factor().riskClass() == null
                    || !e.factor().riskClass().toUpperCase(Locale.ROOT).startsWith(cls)) {
                continue;
            }
            String key = "TRADE".equalsIgnoreCase(e.grain()) && e.tradeId() != null ? "T:" + e.tradeId() : "P:" + e.positionKey();
            byKey.merge(key, e.value(), Double::sum);
        }
        sens.put(measure, byKey);
        return byKey;
    }

    /** A cell's DV01 or CS01: its trades' records, and the records of the positions wholly in it; else the reason. */
    Object sensitivity(String measure, OrdinalSet set) {
        Map<String, Double> byKey;
        try {
            byKey = sensitivities(measure);
        } catch (DrishtiException e) {
            return e.getMessage();
        }
        if (byKey == null) {
            return "the sensitivities engine is off on this server";
        }
        double[] s = {0};
        Set<Integer> positions = new LinkedHashSet<>();
        set.forEach(o -> {
            Double t = byKey.get("T:" + book.tradeId(o));
            if (t != null) {
                s[0] += t;
            }
            int pi = book.positionOf(o);
            if (pi >= 0) {
                positions.add(pi);
            }
        });
        for (int pi : positions) {
            Double v = byKey.get("P:" + book.positionId(pi));
            if (v != null && book.positionWithin(pi, set)) {
                s[0] += v;                                                 // a position's own record: only when the whole position is in
            }
        }
        return s[0];
    }

    /** A cell's tail and histogram (the right rail, tab B): the scenario P&amp;Ls through the lens, the VaR and ES scenarios marked. */
    Map<String, Object> detail(double[] oneDay, OrdinalSet set, String cellScope) {
        double[] v = lensed(oneDay);
        VarCalc.Measures m = measuresOf(oneDay, v);
        List<LocalDate> dates = lens.dates(book.scenarioDates());
        VarCalc.Histogram h = VarCalc.histogram(v, 34);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", cellScope);
        out.put("scenarios", v.length);
        out.put("var", m.var());
        out.put("es", m.es());
        out.put("k", m.k());
        out.put("esCount", m.esCount());
        out.put("varScenario", m.varScenario());
        out.put("varDate", m.varScenario() >= 0 && m.varScenario() < dates.size() ? dates.get(m.varScenario()).toString() : null);
        List<Map<String, Object>> tail = new ArrayList<>();
        for (int i = 0; i < Math.min(m.esCount(), m.order().length); i++) {
            int s = m.order()[i];
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("rank", i + 1);
            t.put("scenario", s);
            t.put("date", s < dates.size() ? dates.get(s).toString() : null);
            t.put("pnl", Math.round(v[s] * 100) / 100.0);
            tail.add(t);
        }
        out.put("tail", tail);
        double mean = 0;
        for (double x : v) {
            mean += x;
        }
        out.put("mean", v.length == 0 ? 0 : mean / v.length);
        out.put("worst", v.length == 0 ? 0 : v[m.order()[0]]);
        out.put("best", v.length == 0 ? 0 : v[m.order()[m.order().length - 1]]);
        out.put("edges", h.edges());
        out.put("counts", h.counts());
        Set<Integer> positions = new LinkedHashSet<>();
        set.forEach(o -> {
            int pi = book.positionOf(o);
            if (pi >= 0) {
                positions.add(pi);
            }
        });
        out.put("trades", set.size());
        out.put("positions", positions.size());
        out.put("method", lens.describe(book.scenarioDates().size()));
        return out;
    }

    /** What every cell of the view shares (the provenance's notes): the date's data, the scenarios, the method, the scope, the shards. */
    Map<String, Object> provenance() {
        VarDate d = base.date();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("businessDate", d.date().toString());
        m.put("currency", d.currency());
        m.put("dataVersion", d.version());
        m.put("inputVersions", d.versions());
        m.put("loadedAt", String.valueOf(d.loadedAt()));
        m.put("batches", d.intake() == null ? List.of() : d.intake().batchIds());
        m.put("intraday", VarViews.intradayNote(d));
        m.put("scenarioSet", book.scenarioSet());
        List<LocalDate> sd = book.scenarioDates();
        m.put("window", sd.isEmpty() ? null : sd.get(0) + " … " + sd.get(sd.size() - 1));
        m.put("scenarios", sd.size());
        m.put("method", lens.describe(sd.size()) + "; vectors summed in float64 in the engine's fixed order, the statistic taken once per cell");
        m.put("lens", lens.key());
        m.put("scope", base.res().canonical());
        m.put("trades", base.set().size());
        m.put("hiddenByRole", base.hidden());
        m.put("shards", base.shards() == null ? "one server: the date is not sharded" : base.shards());
        m.put("engine", "the VaR engine (drishti-compute VarBook), the same sums as /api/v1/risk/var/scope");
        return m;
    }
}
