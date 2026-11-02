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
package com.ash.drishti.server.var;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.frtb.FrtbChoices;
import com.ash.drishti.compute.var.Columns;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.var.VarCalc;
import com.ash.drishti.compute.var.VarLens;
import com.ash.drishti.compute.var.VarMath;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.server.cube.CubeDimension;
import com.ash.drishti.server.cube.CubeMeasure;
import com.ash.drishti.server.cube.CubeProperties;
import com.ash.drishti.server.cube.CubeSource;
import com.ash.drishti.server.cube.CubeView;
import com.ash.drishti.server.frtb.FrtbService;
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
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The market-risk cube's source (MR_CUBE_GUIDE.md): the VaR engine's book of a business date. Members are the date's trades (ordinals) as
 * the person sees them; an aggregate is a set's summed 1-day scenario P&amp;L vector (float64, the engine's fixed order; a whole node its
 * aggregate; a sharded date one batch per owner for all of a view's sets, {@link VarBook#vectors}); a figure is the engine's own statistic
 * of that vector through the view's lens. So a cell's VaR is exactly {@code /api/v1/var/scope} of the cell's scope, and a total is the union's
 * VaR, never a sum.
 *
 * <p>Dimensions: every hierarchy level ({@code MGMT.DESK}, {@code LEGAL.LEGAL_ENTITY}, a configured {@code LL.LIMIT_LETTER}), the
 * positions' columns ({@code DESK}, {@code TT_BOOK}, {@code CURRENCY}, {@code PRODUCT_GROUP} …: a whole position in one value, as a
 * {@code WHERE:} rule selects it), the trades' own columns, {@code RISK_CLASS} (derived from the product group by configuration, a
 * position's primary class), {@code POSITION} and {@code TRADE}. A role that sees a scope only in aggregate may pivot by hierarchy levels
 * only; a masked column is refused as a dimension. Measures: VaR, ES, component VaR and ES (Euler, adding up to the view's total), incremental
 * VaR, PV, trades; stressed VaR per cell scope; DV01 and CS01 from the position sensitivities; FRTB-SA SBM per cell scope.
 */
public final class VarCubeSource implements CubeSource<double[]> {

    static final String MEMBER_POSITION = "POSITION";
    static final String MEMBER_TRADE = "TRADE";
    static final String RISK_CLASS = "RISK_CLASS";

    private final VarService svc;
    private final CubeProperties.Mr props;
    private final Supplier<FrtbService> frtb;
    private final List<CubeMeasure> measures;

    public VarCubeSource(VarService svc, CubeProperties.Mr props, Supplier<FrtbService> frtb) {
        this.svc = svc;
        this.props = props;
        this.frtb = frtb == null ? () -> null : frtb;
        List<CubeMeasure> m = new ArrayList<>();
        m.add(new CubeMeasure("VAR", "VaR", CubeMeasure.Kind.VECTOR, "money", "the cell's own VaR from its summed scenario P&L vector", ""));
        m.add(new CubeMeasure("ES", "ES", CubeMeasure.Kind.VECTOR, "money", "the cell's own expected shortfall from its summed vector", ""));
        m.add(new CubeMeasure("COMP_VAR", "Comp VaR", CubeMeasure.Kind.ADDITIVE, "money",
                "the cell's share of the view's total VaR (Euler: the cells add up to the total exactly)", ""));
        m.add(new CubeMeasure("COMP_ES", "Comp ES", CubeMeasure.Kind.ADDITIVE, "money", "the cell's share of the view's total ES", ""));
        m.add(new CubeMeasure("INCR_VAR", "Incremental VaR", CubeMeasure.Kind.VECTOR, "money",
                "the view's total VaR minus the total without the cell", ""));
        m.add(new CubeMeasure("PV", "PV", CubeMeasure.Kind.ADDITIVE, "money", "the sum of the trades' present values", ""));
        m.add(new CubeMeasure("TRADES", "Trades", CubeMeasure.Kind.ADDITIVE, "count", "the trades in the cell", ""));
        m.add(new CubeMeasure("SVAR", "Stressed VaR", CubeMeasure.Kind.VECTOR, "money",
                "the cell's VaR on the stress set, computed scope by scope (USD)", ""));
        m.add(new CubeMeasure("DV01", "DV01", CubeMeasure.Kind.ADDITIVE, "money",
                "the sum of the cell's interest-rate deltas per basis point (GIRR delta) from the position sensitivities", "sensitivities"));
        m.add(new CubeMeasure("CS01", "CS01", CubeMeasure.Kind.ADDITIVE, "money",
                "the sum of the cell's credit-spread deltas (CSR delta) from the position sensitivities", "sensitivities"));
        m.add(new CubeMeasure("SBM", "SBM capital", CubeMeasure.Kind.FORMULA, "money",
                "FRTB-SA sensitivities-based capital of the cell's own scope (the formula re-run on its members)", "frtb-sa"));
        this.measures = List.copyOf(m);
    }

    @Override
    public String id() {
        return "mr";
    }

    @Override
    public String title() {
        return "MR cube";
    }

    /** The measures; those whose engine is off here are not offered. */
    @Override
    public List<CubeMeasure> measures() {
        boolean f = frtb.get() != null;
        return measures.stream().filter(m -> m.needs().isEmpty() || f).toList();
    }

    @Override
    public List<String> defaultMeasures() {
        return List.of("VAR");
    }

    @Override
    public String drillBelow(String dimension) {
        if (dimension == null) {
            return "DESK";
        }
        String d = dimension.contains(".") ? dimension.substring(dimension.indexOf('.') + 1) : dimension;
        return switch (d) {
            case "BANK", "DIVISION", "DIV", "LEGAL_ENTITY", "LE", RISK_CLASS, "PRODUCT_GROUP" -> "DESK";
            case "DESK" -> "TT_BOOK";
            case "TT_BOOK", "BOOK", "PORTFOLIO" -> MEMBER_POSITION;
            case MEMBER_POSITION -> MEMBER_TRADE;
            case MEMBER_TRADE -> null;
            default -> MEMBER_POSITION;
        };
    }

    /** The open dates up to the view's (none is opened for a sparkline), newest last. */
    @Override
    public List<LocalDate> historyDates(Principal p, CubeView view, int n) {
        LocalDate upTo = view.date();
        List<LocalDate> d = new ArrayList<>(new java.util.TreeSet<>(svc.openDates()));
        if (upTo != null) {
            d.removeIf(x -> x.isAfter(upTo));
        }
        return d.subList(Math.max(0, d.size() - n), d.size());
    }

    VarService service() {
        return svc;
    }

    /** The view's lens: the engine's default unless the view names one (a 400 in words when it is not offered). */
    VarLens lens(Map<String, String> l) {
        String h = l.get("horizon");
        String m = l.get("method");
        String c = l.get("confidence");
        String e = l.get("esConfidence");
        String set = l.get("set");
        if (h == null && m == null && c == null && e == null && set == null) {
            return null;
        }
        try {
            return svc.props().lens(h, m, c == null ? null : Double.valueOf(c), e == null ? null : Double.valueOf(e), set);
        } catch (IllegalArgumentException ex) {
            throw new DrishtiException(ErrorCode.CUBE_BAD_VIEW, ex.getMessage());
        }
    }

    @Override
    public Universe<double[]> open(Principal p, CubeView view) {
        VarLens lens = lens(view.lens());
        String ccy = view.lens().get("ccy");
        VarService.Scoped base = svc.scoped(p, view.scope(), view.date(), ccy, lens);
        return new MrUniverse(p, view, base, lens == null ? svc.props().defaultLens() : lens);
    }

    /** One opened view of the MR cube. */
    final class MrUniverse implements Universe<double[]> {

        private final Principal p;
        private final CubeView view;
        private final VarService.Scoped base;
        private final VarLens lens;
        private final VarCalc calc;
        private final VarBook book;
        private final Map<String, Map<String, OrdinalSet>> partitions = new HashMap<>();
        private final Map<String, Map<String, Double>> sens = new HashMap<>();
        private double[] totalVector;
        private VarCalc.Measures totalMeasures;
        private double[] totalLensed;
        private int stressedAsked;
        private int sbmAsked;

        MrUniverse(Principal p, CubeView view, VarService.Scoped base, VarLens lens) {
            this.p = p;
            this.view = view;
            this.base = base;
            this.lens = lens;
            this.calc = lens.calc(svc.calc());
            this.book = base.date().book();
        }

        VarService.Scoped base() {
            return base;
        }

        VarLens lensUsed() {
            return lens;
        }

        @Override
        public LocalDate date() {
            return base.date().date();
        }

        @Override
        public String canonical() {
            return base.res().canonical();
        }

        @Override
        public OrdinalSet members() {
            return base.set();
        }

        @Override
        public List<CubeDimension> dimensions() {
            List<CubeDimension> out = new ArrayList<>();
            Map<String, Set<String>> levels = new LinkedHashMap<>();
            for (VarBook.Node n : book.nodes().values()) {
                levels.computeIfAbsent(n.hierarchy(), k -> new LinkedHashSet<>()).add(n.level().toUpperCase(Locale.ROOT));
            }
            levels.forEach((h, ls) -> ls.forEach(l -> out.add(new CubeDimension(h + "." + l, h + " › " + l.toLowerCase(Locale.ROOT), "hierarchy",
                    "the nodes of the " + h + " hierarchy at its " + l.toLowerCase(Locale.ROOT) + " level"))));
            out.add(new CubeDimension(RISK_CLASS, "risk class", "derived", "a position's primary risk class, from its product group "
                    + "(drishti.cube.mr.risk-classes)"));
            Columns pc = book.positions();
            for (String c : pc == null ? List.<String>of() : pc.textColumns()) {
                if (!svc.access().masked(p, c)) {
                    out.add(new CubeDimension(c, c.toLowerCase(Locale.ROOT).replace('_', ' '), "attribute", "a column of the positions"));
                }
            }
            for (String c : book.trades().textColumns()) {
                if ((pc == null || !pc.has(c)) && !svc.access().masked(p, c)) {
                    out.add(new CubeDimension(c, c.toLowerCase(Locale.ROOT).replace('_', ' '), "attribute", "a column of the trades"));
                }
            }
            out.add(new CubeDimension(MEMBER_POSITION, "position", "member", "each position"));
            out.add(new CubeDimension(MEMBER_TRADE, "trade", "member", "each trade"));
            return out;
        }

        @Override
        public Map<String, OrdinalSet> partition(String dimension) {
            return partitions.computeIfAbsent(dimension, this::split);
        }

        private Map<String, OrdinalSet> split(String d) {
            OrdinalSet members = base.set();
            if (d.contains(".")) {
                String h = d.substring(0, d.indexOf('.'));
                String level = d.substring(d.indexOf('.') + 1);
                Map<String, OrdinalSet> out = new LinkedHashMap<>();
                for (VarBook.Node n : book.nodes().values()) {
                    if (n.hierarchy().equalsIgnoreCase(h) && n.level().equalsIgnoreCase(level)) {
                        OrdinalSet s = n.members().and(members);
                        if (!s.isEmpty()) {
                            out.put(n.id(), s);
                        }
                    }
                }
                if (out.isEmpty() && book.nodes().values().stream().noneMatch(n -> n.hierarchy().equalsIgnoreCase(h) && n.level().equalsIgnoreCase(level))) {
                    throw new DrishtiException(ErrorCode.CUBE_BAD_VIEW, d + ": the date has no hierarchy " + h + " with a level " + level
                            + "; the hierarchy levels: " + dimensions().stream().filter(x -> x.group().equals("hierarchy")).map(CubeDimension::id).toList());
                }
                return out;
            }
            if (!base.view().seesPositions(members)) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, "your role sees " + base.res().canonical() + " in aggregate only: pivot it by "
                        + "hierarchy levels (MGMT.DESK), not by " + d + ", which would name positions or trades");
            }
            if (svc.access().masked(p, d)) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, d + " is masked for your role: the cube does not pivot by it");
            }
            Columns pc = book.positions();
            Map<String, OrdinalSet.Builder> groups = new TreeMap<>();
            switch (d) {
                case MEMBER_TRADE -> {
                    Map<String, OrdinalSet> out = new LinkedHashMap<>();
                    members.forEach(o -> out.put(book.tradeId(o), OrdinalSet.of(o)));
                    return out;
                }
                case MEMBER_POSITION -> {
                    for (int pi = 0; pi < book.positionCount(); pi++) {
                        if (book.positionMemberCountIn(pi, members) > 0) {
                            OrdinalSet.Builder b = groups.computeIfAbsent(book.positionId(pi), k -> new OrdinalSet.Builder());
                            book.forEachPositionMemberIn(pi, members, b::add);
                        }
                    }
                    return built(groups);
                }
                case RISK_CLASS -> {
                    Map<String, String> classOf = new HashMap<>();
                    props.riskClasses().forEach((c, gs) -> gs.forEach(g -> classOf.put(g.toUpperCase(Locale.ROOT), c)));
                    Map<String, OrdinalSet.Builder> ordered = new LinkedHashMap<>();
                    props.riskClasses().keySet().forEach(c -> ordered.put(c, new OrdinalSet.Builder()));
                    ordered.put("OTHER", new OrdinalSet.Builder());
                    for (int pi = 0; pi < book.positionCount(); pi++) {
                        String g = pc == null ? null : pc.text("PRODUCT_GROUP", pi);
                        String c = g == null ? "OTHER" : classOf.getOrDefault(g.toUpperCase(Locale.ROOT), "OTHER");
                        book.forEachPositionMemberIn(pi, members, ordered.get(c)::add);
                    }
                    Map<String, OrdinalSet> out = new LinkedHashMap<>();
                    ordered.forEach((k, b) -> {
                        OrdinalSet s = b.build();
                        if (!s.isEmpty()) {
                            out.put(k, s);
                        }
                    });
                    return out;
                }
                default -> {
                    if (pc != null && pc.has(d)) {
                        for (int pi = 0; pi < book.positionCount(); pi++) {
                            if (book.positionMemberCountIn(pi, members) > 0) {
                                String v = pc.text(d, pi);
                                OrdinalSet.Builder b = groups.computeIfAbsent(v == null || v.isBlank() ? NONE : v, k -> new OrdinalSet.Builder());
                                book.forEachPositionMemberIn(pi, members, b::add);
                            }
                        }
                        return built(groups);
                    }
                    Columns tc = book.trades();
                    if (tc.has(d)) {
                        members.forEach(o -> {
                            String v = tc.text(d, o);
                            groups.computeIfAbsent(v == null || v.isBlank() ? NONE : v, k -> new OrdinalSet.Builder()).add(o);
                        });
                        return built(groups);
                    }
                    throw new DrishtiException(ErrorCode.CUBE_BAD_VIEW, d + " is not a dimension of the MR cube on " + date() + "; its dimensions: "
                            + dimensions().stream().map(CubeDimension::id).toList());
                }
            }
        }

        private static Map<String, OrdinalSet> built(Map<String, OrdinalSet.Builder> groups) {
            Map<String, OrdinalSet> out = new LinkedHashMap<>();
            groups.forEach((k, b) -> out.put(k, b.build()));
            return out;
        }

        @Override
        public String label(String dimension, String value) {
            if (dimension.contains(".")) {
                VarBook.Node n = book.node(value);
                return n == null || n.name() == null || n.name().isBlank() ? value : n.name();
            }
            return value;
        }

        @Override
        public String scopeOf(Map<String, String> coords) {
            List<String> terms = new ArrayList<>();
            String scope = view.scope().strip();
            if (!scope.equalsIgnoreCase("BANK") || coords.isEmpty()) {
                terms.add(scope);
            }
            for (Map.Entry<String, String> e : coords.entrySet()) {
                String t = term(e.getKey(), e.getValue());
                if (t == null) {
                    return null;
                }
                terms.add(t);
            }
            if (terms.size() == 1) {
                return terms.get(0);
            }
            return String.join(" & ", terms.stream().map(t -> "(" + t + ")").toList());
        }

        /** One coordinate in the scope grammar, or null when the grammar cannot write it. */
        private String term(String d, String v) {
            if (d.contains(".")) {
                return "NODE:" + v;
            }
            Columns pc = book.positions();
            return switch (d) {
                case MEMBER_POSITION -> "POS:" + v;
                case MEMBER_TRADE -> "TRD:" + v;
                case RISK_CLASS -> {
                    List<String> all = new ArrayList<>();
                    props.riskClasses().values().forEach(all::addAll);
                    if (v.equals("OTHER")) {
                        yield "WHERE:not PRODUCT_GROUP in (" + quoted(all) + ")";
                    }
                    List<String> gs = props.riskClasses().get(v);
                    yield gs == null ? null : "WHERE:PRODUCT_GROUP in (" + quoted(gs) + ")";
                }
                default -> {
                    if (pc != null && pc.has(d)) {
                        yield v.equals(NONE) ? null : "WHERE:" + d + " == '" + v.replace("'", "''") + "'";
                    }
                    yield d.equals("COUNTERPARTY") && !v.equals(NONE) ? "CPTY:" + v : null;
                }
            };
        }

        private static String quoted(List<String> vs) {
            return String.join(", ", vs.stream().map(x -> "'" + x.replace("'", "''") + "'").toList());
        }

        @Override
        public String cacheKey() {
            VarDate d = base.date();
            return d.date() + "|" + d.currency() + "|" + d.version() + "|i" + d.intradayStamp() + "|" + base.view().signature() + "|"
                    + (lens.stressed() ? "stressed" : "normal");
        }

        @Override
        public List<double[]> aggregates(List<OrdinalSet> sets) {
            List<double[]> out = new ArrayList<>(sets.size());
            List<OrdinalSet> ask = new ArrayList<>();
            List<Integer> at = new ArrayList<>();
            for (int i = 0; i < sets.size(); i++) {
                OrdinalSet s = sets.get(i);
                if (s.equals(base.set())) {
                    out.add(base.oneDay());                                // the view's own scope: the engine's answer already made
                } else {
                    out.add(null);
                    ask.add(s);
                    at.add(i);
                }
            }
            if (!ask.isEmpty()) {
                List<double[]> got = book.vectors(ask, svc.sums());       // one batch: on a sharded date one call per owner (section 4.11)
                for (int k = 0; k < at.size(); k++) {
                    out.set(at.get(k), got.get(k));
                }
            }
            return out;
        }

        @Override
        public long bytes(double[] a) {
            return 16L + 8L * a.length;
        }

        private double[] lensed(double[] oneDay) {
            return lens.equals(svc.props().defaultLens()) && !lens.tenDay() ? oneDay : lens.apply(oneDay);
        }

        private VarCalc.Measures measuresOf(double[] oneDay, double[] lensed) {
            return lensed == oneDay ? calc.measures(lensed) : lens.measures(svc.calc(), oneDay, lensed);
        }

        private synchronized void total(double[] total) {
            if (totalVector != total) {
                totalVector = total;
                totalLensed = lensed(total);
                totalMeasures = measuresOf(total, totalLensed);
            }
        }

        @Override
        public Figures figures(List<String> ids, double[] oneDay, Cell cell, double[] total) {
            total(total);
            double[] v = lensed(oneDay);
            VarCalc.Measures m = measuresOf(oneDay, v);
            Map<String, Double> values = new LinkedHashMap<>();
            Map<String, String> notes = new LinkedHashMap<>();
            for (String id : ids) {
                switch (id) {
                    case "VAR" -> values.put(id, m.var());
                    case "ES" -> values.put(id, m.es());
                    case "COMP_VAR" -> values.put(id, VarMath.component(v, totalMeasures.tail(), totalMeasures.scale()));
                    case "COMP_ES" -> values.put(id, VarMath.componentEs(v, totalMeasures.order(), calc.esConfidence()));
                    case "INCR_VAR" -> values.put(id, totalMeasures.var() - calc.var(VarMath.minus(totalLensed, v)));
                    case "PV" -> values.put(id, pv(cell.set()));
                    case "TRADES" -> values.put(id, (double) cell.set().size());
                    case "SVAR" -> stressed(cell, values, notes);
                    case "DV01" -> sensitivity("DV01", cell.set(), values, notes);
                    case "CS01" -> sensitivity("CS01", cell.set(), values, notes);
                    case "SBM" -> sbm(cell, values, notes);
                    default -> values.put(id, null);
                }
            }
            limit(cell, m, values, notes);
            return new Figures(values, notes);
        }

        private double pv(OrdinalSet set) {
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

        /** A row's or the total's VaR limit: the node's, when the set is the node's whole membership the viewer sees, on the limit's basis. */
        private void limit(Cell cell, VarCalc.Measures m, Map<String, Double> values, Map<String, String> notes) {
            if (cell.kind().equals("cell") && cell.coords().size() > 1) {
                return;
            }
            VarBook.Node n = null;
            if (cell.coords().isEmpty()) {
                n = base.res().node() == null ? null : book.node(base.res().node());
            } else if (cell.coords().size() == 1) {
                Map.Entry<String, String> e = cell.coords().entrySet().iterator().next();
                if (e.getKey().contains(".")) {
                    n = book.node(e.getValue());
                } else if (List.of("DESK", "TT_BOOK", "DIVISION", "LEGAL_ENTITY").contains(e.getKey())) {
                    n = book.node((e.getKey().equals("LEGAL_ENTITY") ? "LEGAL~" : "MGMT~") + e.getValue());
                }
            }
            if (n == null || Double.isNaN(n.limit())) {
                return;
            }
            OrdinalSet seen = base.view().full() ? n.members() : n.members().and(base.view().visible());
            if (!seen.equals(cell.set())) {
                return;                                                   // a part of the node (a filter or a narrower scope): no limit
            }
            values.put("limit", n.limit());
            if (svc.loader().limitBasis(date(), n.id()).matches(lens) && n.limit() != 0) {
                values.put("limitUse", m.var() / n.limit());
            } else {
                notes.put("limitUse", "the limit is set for another horizon or confidence than the view's");
            }
        }

        private void stressed(Cell cell, Map<String, Double> values, Map<String, String> notes) {
            if (cell.scope() == null) {
                values.put("SVAR", null);
                notes.put("SVAR", "no scope text names this cell, and stressed VaR is computed scope by scope");
                return;
            }
            if (++stressedAsked > props.stressedMaxCells()) {
                values.put("SVAR", null);
                notes.put("SVAR", "stressed VaR is computed for the first " + props.stressedMaxCells() + " cells (drishti.cube.mr.stressed-max-cells)");
                return;
            }
            try {
                VarLens s = (lens == null ? svc.props().defaultLens() : lens).withStressed(true);
                values.put("SVAR", svc.scoped(p, cell.scope(), date(), null, s).measures().var());
            } catch (DrishtiException | IllegalStateException e) {
                values.put("SVAR", null);
                notes.put("SVAR", e.getMessage());
            }
        }

        /** The sensitivities' keys: by trade id (a record of a trade) or by position id (a record of a whole position). */
        private synchronized Map<String, Double> sensitivities(String measure) {
            Map<String, Double> hit = sens.get(measure);
            if (hit != null) {
                return hit;
            }
            FrtbService f = frtb.get();
            if (f == null) {
                return null;
            }
            String cls = measure.equals("DV01") ? "GIRR" : "CSR";
            FrtbService.Elements el = f.elements(p, view.scope(), date(), base.date().currency());
            Map<String, Double> byKey = new HashMap<>();
            for (FrtbService.Element e : el.elements()) {
                if (!VarCubeExtras.isDelta("DELTA", e.measure()) || e.factor() == null || e.factor().riskClass() == null
                        || !e.factor().riskClass().toUpperCase(Locale.ROOT).startsWith(cls)) {
                    continue;
                }
                String key = "TRADE".equalsIgnoreCase(e.grain()) && e.tradeId() != null ? "T:" + e.tradeId() : "P:" + e.positionKey();
                byKey.merge(key, e.value(), Double::sum);
            }
            sens.put(measure, byKey);
            return byKey;
        }

        private void sensitivity(String measure, OrdinalSet set, Map<String, Double> values, Map<String, String> notes) {
            Map<String, Double> byKey;
            try {
                byKey = sensitivities(measure);
            } catch (DrishtiException e) {
                values.put(measure, null);
                notes.put(measure, e.getMessage());
                return;
            }
            if (byKey == null) {
                values.put(measure, null);
                notes.put(measure, "the sensitivities engine is off on this server");
                return;
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
                    s[0] += v;                                             // a position's own record: only when the whole position is in
                }
            }
            values.put(measure, s[0]);
        }

        private void sbm(Cell cell, Map<String, Double> values, Map<String, String> notes) {
            FrtbService f = frtb.get();
            if (f == null || cell.scope() == null) {
                values.put("SBM", null);
                notes.put("SBM", f == null ? "the FRTB-SA engine is off on this server" : "no scope text names this cell");
                return;
            }
            if (++sbmAsked > props.sbmMaxCells()) {
                values.put("SBM", null);
                notes.put("SBM", "SBM is computed for the first " + props.sbmMaxCells() + " cells (drishti.cube.mr.sbm-max-cells)");
                return;
            }
            try {
                values.put("SBM", f.run(p, cell.scope(), date(), null, base.date().currency(), FrtbChoices.defaults()).result().sbm().sbm());
            } catch (DrishtiException e) {
                values.put("SBM", null);
                notes.put("SBM", e.getMessage());
            }
        }

        @Override
        public Map<String, Object> detail(double[] oneDay, Cell cell) {
            double[] v = lensed(oneDay);
            VarCalc.Measures m = measuresOf(oneDay, v);
            List<LocalDate> dates = lens.dates(book.scenarioDates());
            VarCalc.Histogram h = VarCalc.histogram(v, 34);
            Map<String, Object> out = new LinkedHashMap<>();
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
            cell.set().forEach(o -> {
                int pi = book.positionOf(o);
                if (pi >= 0) {
                    positions.add(pi);
                }
            });
            out.put("trades", cell.set().size());
            out.put("positions", positions.size());
            out.put("scope", cell.scope());
            out.put("method", lens.describe(book.scenarioDates().size()));
            return out;
        }

        @Override
        public Map<String, Object> provenance() {
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
            m.put("engine", "the VaR engine (drishti-compute VarBook), the same sums as /api/v1/var/scope");
            return m;
        }

        @Override
        public Map<String, Object> headline(double[] total) {
            total(total);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("var", totalMeasures.var());
            h.put("es", totalMeasures.es());
            h.put("k", totalMeasures.k());
            h.put("esCount", totalMeasures.esCount());
            h.put("scenarios", totalLensed.length);
            h.put("lens", lens.label());
            h.put("confidence", lens.confidence());
            h.put("esConfidence", lens.esConfidence());
            h.put("currency", base.date().currency());
            h.put("trades", base.set().size());
            h.put("positions", book.positionCount());
            VarBook.Node n = base.res().node() == null ? null : book.node(base.res().node());
            if (n != null && !Double.isNaN(n.limit())) {
                h.put("limit", n.limit());
                if (svc.loader().limitBasis(date(), n.id()).matches(lens) && n.limit() != 0) {
                    h.put("limitUse", totalMeasures.var() / n.limit());
                }
            }
            h.put("stressedOpen", svc.stressedOpen(date()));
            h.put("dataVersion", base.date().version());
            h.put("batches", base.date().intake() == null ? List.of() : base.date().intake().batchIds());
            h.put("intraday", VarViews.intradayNote(base.date()));
            return h;
        }
    }

    /** A member with no value in a column. */
    static final String NONE = "(none)";
}
