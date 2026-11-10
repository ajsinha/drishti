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

import com.ash.drishti.banking.compute.var.Backtest;
import com.ash.drishti.banking.compute.var.VarLens;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The regulatory backtest (VAR_GUIDE.md section 18.11; REGULATORY_VAR_REPORTING.md section 4.6): for a node of the hierarchies, each
 * business day's actual and hypothetical P&amp;L (the bank's {@code desk-pnl}) and Drishti's risk-theoretical P&amp;L (the official record's
 * {@code hypoPnl}) against the official VaR of the business day before, at each configured confidence, over the last 250 tested days;
 * the exceptions, the zone and the capital multiplier. Computed on demand from the official records and the desk P&amp;L, never stored.
 */
final class VarBacktesting {

    private final VarOfficialStore store;
    private final VarDeskPnl pnl;
    private final RegulatoryProperties props;
    private final double officialConfidence;

    VarBacktesting(VarOfficialStore store, VarDeskPnl pnl, RegulatoryProperties props, double officialConfidence) {
        this.store = store;
        this.pnl = pnl;
        this.props = props;
        this.officialConfidence = officialConfidence;
    }

    // ------------------------------------------------------------------------------------------------------------- the nodes

    /** What a node takes from the desk P&amp;L: every row (a root), or its desks' rows (in its legal entity, under LEGAL). */
    record Reach(String node, String name, String level, String hierarchy, boolean all, Set<String> desks, String legalEntity) {
        boolean applicable() {
            return all || !desks.isEmpty();
        }
    }

    /** The node as the official record of a date describes it (its hierarchy, level, parent, the desks below it). */
    @SuppressWarnings("unchecked")
    static Reach reach(Map<String, Object> record, String node) {
        Map<String, Map<String, Object>> nodes = record == null ? Map.of() : (Map<String, Map<String, Object>>) record.getOrDefault("nodes", Map.of());
        Map<String, Object> f = nodes.get(node);
        if (f == null) {
            return null;
        }
        boolean root = f.get("parent") == null;
        Set<String> desks = new LinkedHashSet<>();
        String le = null;
        for (String a = node; a != null; a = nodes.get(a) == null ? null : (String) nodes.get(a).get("parent")) {
            Map<String, Object> af = nodes.get(a);
            if (af != null && "legal-entity".equals(af.get("level"))) {
                le = tail(a);
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : nodes.entrySet()) {
            if ("desk".equals(e.getValue().get("level")) && under(nodes, e.getKey(), node)) {
                desks.add(tail(e.getKey()));
            }
        }
        return new Reach(node, String.valueOf(f.get("name")), String.valueOf(f.get("level")), String.valueOf(f.get("hierarchy")), root, desks, le);
    }

    private static boolean under(Map<String, Map<String, Object>> nodes, String id, String ancestor) {
        for (String a = id; a != null; a = nodes.get(a) == null ? null : (String) nodes.get(a).get("parent")) {
            if (a.equals(ancestor)) {
                return true;
            }
        }
        return false;
    }

    /** {@code MGMT~DESK-RATES} -> {@code DESK-RATES}. */
    static String tail(String id) {
        int i = id.lastIndexOf('~');
        return i < 0 ? id : id.substring(i + 1);
    }

    // ------------------------------------------------------------------------------------------------------------- the test

    /** One tested day: the official VaR of the day before at each confidence, the three P&amp;Ls of the day. */
    record DayRow(LocalDate date, LocalDate prior, double[] var, double actual, double hypo, double rtpl, boolean synthetic) {}

    /** The tested days of a node up to a date, oldest first (at most the window), and the days left out with why. */
    record Series(List<DayRow> days, List<Map<String, Object>> untested, int synthetic) {}

    Series series(Reach r, LocalDate upTo) {
        List<Double> qs = props.backtesting().confidences();
        int window = props.backtesting().window();
        List<LocalDate> dates = new ArrayList<>(store.dates().headSet(upTo, true));
        int from = Math.max(1, dates.size() - (window + 30));                     // a few spare days for the untested ones
        List<DayRow> out = new ArrayList<>();
        List<Map<String, Object>> untested = new ArrayList<>();
        int synthetic = 0;
        for (int i = from; i < dates.size(); i++) {
            LocalDate d = dates.get(i);
            LocalDate prior = dates.get(i - 1);
            Map<String, Object> rec = store.index(prior).orElse(null);
            Map<String, Object> today = store.index(d).orElse(null);
            Map<?, ?> pf = figures(rec, r.node());
            Map<?, ?> tf = figures(today, r.node());
            if (pf == null) {
                untested.add(why(d, "the node has no official record on " + prior));
                continue;
            }
            double[] var = new double[qs.size()];
            for (int k = 0; k < qs.size(); k++) {
                var[k] = varAt(pf, qs.get(k));
            }
            VarDeskPnl.Day day = pnl.day(d);
            double[] p = day.sum(r.desks(), r.legalEntity(), r.all());
            if (p == null) {
                untested.add(why(d, day.read() ? "no " + pnl.kind() + " row of the node's desks on " + d : "no connector serves " + pnl.kind()));
                continue;
            }
            boolean syn = Boolean.TRUE.equals(rec.get("synthetic")) || today != null && Boolean.TRUE.equals(today.get("synthetic"));
            synthetic += syn ? 1 : 0;
            out.add(new DayRow(d, prior, var, p[0], p[1], tf == null ? Double.NaN : VarOfficialStore.num(tf.get("hypoPnl")), syn));
        }
        while (out.size() > window) {
            if (out.get(0).synthetic()) {
                synthetic--;
            }
            out.remove(0);
        }
        LocalDate first = out.isEmpty() ? null : out.get(0).date();
        untested.removeIf(u -> first != null && LocalDate.parse((String) u.get("date")).isBefore(first));
        return new Series(out, untested, synthetic);
    }

    private static Map<String, Object> why(LocalDate d, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", d.toString());
        m.put("reason", reason);
        return m;
    }

    private static Map<?, ?> figures(Map<String, Object> record, String node) {
        return record != null && record.get("nodes") instanceof Map<?, ?> n && n.get(node) instanceof Map<?, ?> f ? f : null;
    }

    /** The record's VaR at a confidence: {@code var} at the official one, else {@code varAt} (NaN when the record has none). */
    double varAt(Map<?, ?> f, double q) {
        if (Math.abs(q - officialConfidence) < 1e-9) {
            return VarOfficialStore.num(f.get("var"));
        }
        if (f.get("varAt") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                try {
                    if (Math.abs(Double.parseDouble(String.valueOf(e.getKey())) - q) < 1e-9) {
                        return VarOfficialStore.num(e.getValue());
                    }
                } catch (NumberFormatException x) {
                    // not a confidence
                }
            }
        }
        return Double.NaN;
    }

    /** The counts of a series at confidence index k: actual, hypothetical, risk-theoretical; -1 where no day has the VaR. */
    static int[] counts(Series s, int k) {
        int a = 0;
        int h = 0;
        int t = 0;
        int with = 0;
        for (DayRow d : s.days()) {
            double v = d.var()[k];
            if (!Double.isFinite(v)) {
                continue;
            }
            with++;
            a += d.actual() < -v ? 1 : 0;
            h += d.hypo() < -v ? 1 : 0;
            t += Double.isFinite(d.rtpl()) && d.rtpl() < -v ? 1 : 0;
        }
        return with == 0 ? new int[] {-1, -1, -1, 0} : new int[] {a, h, t, with};
    }

    /** The count the zone and the multiplier read (the configured rule). */
    int counted(int[] c) {
        return switch (props.backtesting().count()) {
            case "hypothetical" -> c[1];
            case "higher" -> Math.max(c[0], c[1]);
            default -> c[0];
        };
    }

    // ------------------------------------------------------------------------------------------------------------- the answer

    /** The backtest of a node up to a date (null: the newest official date), with its parts (every desk below it) when asked. */
    Map<String, Object> answer(String node, LocalDate upTo, boolean withParts) {
        LocalDate last = upTo == null ? (store.dates().isEmpty() ? null : store.dates().last()) : store.dates().floor(upTo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node);
        if (last == null) {
            out.put("applicable", false);
            out.put("note", "no official VaR record yet: the backtest reads the official records");
            return out;
        }
        Map<String, Object> rec = store.index(last).orElseThrow();
        Reach r = reach(rec, node);
        if (r == null) {
            out.put("applicable", false);
            out.put("note", "the node " + node + " is not in the official record of " + last);
            return out;
        }
        out.putAll(one(r, last, true));
        if (withParts) {
            List<Map<String, Object>> parts = new ArrayList<>();
            parts.add(summary(r, out));
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> nodes = (Map<String, Map<String, Object>>) rec.get("nodes");
            List<String> desks = nodes.entrySet().stream().filter(e -> "desk".equals(e.getValue().get("level")) && !e.getKey().equals(node)
                    && r.desks().contains(tail(e.getKey())) && e.getKey().startsWith(r.hierarchy() + "~")).map(Map.Entry::getKey).toList();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String d : desks) {
                Reach dr = reach(rec, d);
                rows.add(summary(dr, one(dr, last, false)));
            }
            rows.sort((a, b) -> Double.compare(VarOfficialStore.num(b.get("var")), VarOfficialStore.num(a.get("var"))));
            parts.addAll(rows);
            out.put("parts", parts);
        }
        return out;
    }

    /** A part's row of the bank and desks table. */
    private static Map<String, Object> summary(Reach r, Map<String, Object> full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", r.node());
        m.put("name", r.name());
        m.put("level", r.level());
        for (String k : List.of("applicable", "var", "days", "counts", "zone", "multiplier", "spark", "note")) {
            if (full.containsKey(k)) {
                m.put(k, full.get(k));
            }
        }
        return m;
    }

    private Map<String, Object> one(Reach r, LocalDate last, boolean detail) {
        Map<String, Object> out = new LinkedHashMap<>();
        RegulatoryProperties.Backtesting bt = props.backtesting();
        List<Double> qs = bt.confidences();
        out.put("name", r.name());
        out.put("hierarchy", r.hierarchy());
        out.put("level", r.level());
        out.put("asOf", last.toString());
        Map<?, ?> f = figures(store.index(last).orElse(null), r.node());
        out.put("var", f == null ? null : f.get("var"));
        if (!r.applicable()) {
            out.put("applicable", false);
            out.put("note", "the desk P&L is kept by desk: a backtest is of the bank, a desk, or a node made of whole desks");
            return out;
        }
        Series s = series(r, last);
        out.put("applicable", true);
        out.put("window", bt.window());
        out.put("days", s.days().size());
        out.put("from", s.days().isEmpty() ? null : s.days().get(0).date().toString());
        out.put("to", last.toString());
        List<Map<String, Object>> counts = new ArrayList<>();
        for (int k = 0; k < qs.size(); k++) {
            int[] c = counts(s, k);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("confidence", qs.get(k));
            m.put("expected", Math.round(c[3] * (1 - qs.get(k)) * 100) / 100.0);
            m.put("days", c[3]);
            m.put("actual", c[0] < 0 ? null : c[0]);
            m.put("hypothetical", c[1] < 0 ? null : c[1]);
            m.put("riskTheoretical", c[2] < 0 ? null : c[2]);
            if (c[0] < 0) {
                m.put("note", "the official records of these days carry no VaR at " + VarLens.pct(qs.get(k)));
            } else if (k == 0) {
                m.put("actualZone", bt.zone(c[0]));
                m.put("hypotheticalZone", bt.zone(c[1]));
            }
            counts.add(m);
        }
        out.put("counts", counts);
        int[] c0 = counts(s, 0);
        int n = c0[0] < 0 ? 0 : counted(c0);
        out.put("counted", n);
        out.put("zone", c0[0] < 0 ? null : bt.zone(n));
        out.put("multiplier", c0[0] < 0 ? null : bt.multiplier(n));
        out.put("full", s.days().size() >= bt.window());
        if (s.days().size() < bt.window()) {
            out.put("short", s.days().size() + " of " + bt.window() + " business days tested: not yet a regulatory zone");
        }
        out.put("synthetic", s.synthetic());
        // the spark: the days' actual P&L and the 99% VaR of the day before, for the table's small chart
        List<long[]> spark = new ArrayList<>();
        for (DayRow d : s.days()) {
            spark.add(new long[] {Math.round(d.actual()), Math.round(d.var()[0])});
        }
        out.put("spark", spark);
        if (!detail) {
            return out;
        }
        out.put("rule", rule(bt));
        out.put("configuration", Map.of("window", bt.window(), "confidences", qs, "green", bt.green(), "amber", bt.amber(),
                "multipliers", bt.multipliers(), "count", bt.count()));
        // Kupiec and Christoffersen on the actual P&L at the first confidence
        int m = s.days().size();
        double[] pnlA = new double[m + 1];
        double[] varA = new double[m + 1];
        pnlA[0] = Double.NaN;
        varA[m] = Double.NaN;
        for (int i = 0; i < m; i++) {
            pnlA[i + 1] = s.days().get(i).actual();
            varA[i] = s.days().get(i).var()[0];
        }
        Backtest b = Backtest.of(pnlA, varA, qs.get(0));
        Map<String, Object> tests = new LinkedHashMap<>();
        tests.put("kupiecLr", round4(b.kupiecLr()));
        tests.put("kupiecP", round4(b.kupiecPValue()));
        tests.put("christoffersenLr", round4(b.christoffersenLr()));
        tests.put("christoffersenP", round4(b.christoffersenPValue()));
        out.put("tests", tests);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> exceptions = new ArrayList<>();
        for (DayRow d : s.days()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.date().toString());
            row.put("prior", d.prior().toString());
            for (int k = 0; k < qs.size(); k++) {
                row.put("var" + key(qs.get(k)), Double.isFinite(d.var()[k]) ? Math.round(d.var()[k]) : null);
            }
            row.put("actual", Math.round(d.actual()));
            row.put("hypothetical", Math.round(d.hypo()));
            row.put("riskTheoretical", Double.isFinite(d.rtpl()) ? Math.round(d.rtpl()) : null);
            boolean ea = d.actual() < -d.var()[0];
            boolean eh = d.hypo() < -d.var()[0];
            row.put("exceptionActual", ea);
            row.put("exceptionHypothetical", eh);
            if (qs.size() > 1 && Double.isFinite(d.var()[1])) {
                row.put("exceptionActual" + key(qs.get(1)), d.actual() < -d.var()[1]);
                row.put("exceptionHypothetical" + key(qs.get(1)), d.hypo() < -d.var()[1]);
            }
            if (d.synthetic()) {
                row.put("synthetic", true);
            }
            rows.add(row);
            if (ea || eh) {
                Map<String, Object> e = new LinkedHashMap<>(row);
                e.put("which", ea && eh ? "both" : ea ? "actual" : "hypothetical");
                e.put("excess", Math.round(Math.min(d.actual(), d.hypo()) + d.var()[0]));
                e.put("drivers", drivers(r, d.date()));
                exceptions.add(e);
            }
        }
        out.put("rows", rows);
        out.put("exceptions", exceptions);
        out.put("untested", s.untested());
        out.put("source", "the official VaR record of the business day before; " + pnl.kind() + " (actual and hypothetical); the official"
                + " record's hypoPnl (risk-theoretical); computed on demand, never stored");
        return out;
    }

    /** The desks that drove a day's loss: the largest actual losses among the node's desks, those making up most of it. */
    private List<String> drivers(Reach r, LocalDate d) {
        Map<String, Double> by = pnl.day(d).byDesk(r.desks(), r.legalEntity(), r.all());
        List<Map.Entry<String, Double>> losses = by.entrySet().stream().filter(e -> e.getValue() < 0)
                .sorted(Map.Entry.comparingByValue()).toList();
        double total = losses.stream().mapToDouble(Map.Entry::getValue).sum();
        List<String> out = new ArrayList<>();
        double acc = 0;
        for (Map.Entry<String, Double> e : losses) {
            out.add(e.getKey());
            acc += e.getValue();
            if (acc <= 0.6 * total || out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    static String key(double q) {
        return String.valueOf(Math.round(q * 1000));                    // 0.99 -> 990, 0.975 -> 975
    }

    private static double round4(double x) {
        return Double.isFinite(x) ? Math.round(x * 10_000) / 10_000.0 : x;
    }

    private static String rule(RegulatoryProperties.Backtesting bt) {
        String table = bt.multipliers().equals(RegulatoryProperties.US_MULTIPLIERS) ? "the US table (12 CFR 217.204, Table 1)" : "the configured table";
        String count = switch (bt.count()) {
            case "hypothetical" -> "the hypothetical P&L's count";
            case "higher" -> "the higher of the actual and hypothetical counts";
            default -> "the actual P&L's count (12 CFR 217.204(b))";
        };
        return "green 0-" + bt.green() + ", amber " + (bt.green() + 1) + "-" + bt.amber() + ", red " + (bt.amber() + 1) + " or more, over "
                + bt.window() + " business days at " + VarLens.pct(bt.confidences().get(0)) + "; the multiplier from " + table + ", by " + count;
    }
}
