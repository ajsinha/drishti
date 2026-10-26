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
package com.ash.drishti.server.exposure;

import com.ash.drishti.compute.ccr.CsaTerms;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureCalc;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.compute.ccr.ExposureMath;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the exposure explorer shows beside a scope's figures (docs/guides/EXPOSURE_GUIDE.md section 3): where the scope sits (the bank,
 * a legal entity, a counterparty, a netting set, a trade) for the breadcrumb; a counterparty's stored credit limit and the peak PFE it
 * is set on (read as the limits system stores it: LIM stays parked); a netting set's collateral terms, what it holds and what the
 * collateral takes off the exposure at the peak; a trade's own attributes; each tree node's EE sparkline; the path fan of a scope (its
 * exposure's quantiles at every primary date); and, in a table of trades, each trade's attributes, its peak PFE alone and its EE
 * contribution at every primary date.
 */
final class ExposureExplorer {

    /** The quantiles of the path fan: 5 to 95% and 25 to 75% around the median. */
    static final double[] FAN = {0.05, 0.25, 0.5, 0.75, 0.95};
    private final ExposureService svc;

    ExposureExplorer(ExposureService svc) {
        this.svc = svc;
    }

    // ---------------------------------------------------------------------------------------------------------------------- the scope

    /** Adds the explorer's fields to a scope's answer: {@code ancestors}, and by kind {@code limit}, {@code collateral}, {@code trade}. */
    void augment(Principal p, ExposureService.Scoped s, Map<String, Object> out) {
        ExposureBook b = s.date().book();
        String kind = s.kind();
        String id = s.res().id();
        List<Map<String, Object>> up = new ArrayList<>();
        if (!kind.equals("bank")) {
            up.add(node("BANK", "Bank", "bank"));
        }
        switch (kind) {
            case "netting-set" -> {
                ExposureBook.SetInfo si = b.set(b.setOrdinal(id));
                up.add(node("LE:" + si.legalEntity(), si.legalEntity(), "legal-entity"));
                up.add(node("CPTY:" + si.counterparty(), svc.name(s.date(), si.counterparty(), p), "counterparty"));
                out.put("collateral", collateral(s, b.setOrdinal(id)));
            }
            case "counterparty" -> {
                String le = homeLegalEntity(b, id);
                if (le != null) {
                    up.add(node("LE:" + le, le, "legal-entity"));
                }
                out.put("limit", limit(s, id));
            }
            case "trade" -> {
                if (!s.res().trades().isEmpty()) {
                    ExposureBook.TradeInfo t = b.trade(s.res().trades().first());
                    ExposureBook.SetInfo si = b.set(t.set());
                    up.add(node("LE:" + si.legalEntity(), si.legalEntity(), "legal-entity"));
                    up.add(node("CPTY:" + si.counterparty(), svc.name(s.date(), si.counterparty(), p), "counterparty"));
                    up.add(node("NSET:" + si.id(), si.id(), "netting-set"));
                    out.put("trade", trade(t, si));
                }
            }
            default -> {
                // the bank, a legal entity, a group, an ad-hoc scope: the bank above it
            }
        }
        out.put("ancestors", up);
    }

    private static Map<String, Object> node(String scope, String label, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", scope);
        m.put("label", label);
        m.put("kind", kind);
        return m;
    }

    /** The legal entity a counterparty's trades with paths are mostly booked in (its place under the bank in the breadcrumb). */
    static String homeLegalEntity(ExposureBook b, String counterparty) {
        Map<String, Integer> n = new HashMap<>();
        for (ExposureBook.SetInfo si : b.sets()) {
            if (counterparty.equals(si.counterparty()) && si.legalEntity() != null) {
                n.merge(si.legalEntity(), si.to() - si.from(), Integer::sum);
            }
        }
        return n.entrySet().stream().max((x, y) -> x.getValue().equals(y.getValue()) ? y.getKey().compareTo(x.getKey())
                : Integer.compare(x.getValue(), y.getValue())).map(Map.Entry::getKey).orElse(null);
    }

    /** A counterparty's stored credit limit and how much of it the peak PFE at the limit's confidence uses; null when it has none. */
    Map<String, Object> limit(ExposureService.Scoped s, String counterparty) {
        ExposureDate.Limit l = s.date().limits().get(counterparty);
        if (l == null) {
            return null;
        }
        ExposureMath.Stats st = s.result().stats;
        int at = st.at(svc.props().limitConfidence());
        double used = at < 0 ? st.peakPfe[Math.max(0, st.at(s.ask().q()))] : st.peakPfe[at];
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.id());
        m.put("limit", Math.round(l.amount()));
        m.put("measure", l.measure());
        m.put("confidence", svc.props().limitConfidence());
        m.put("used", Math.round(used));
        m.put("use", used / l.amount());
        m.put("note", "the stored credit limit (credit-limit " + l.id() + "), compared with the computed peak PFE at "
                + Math.round(svc.props().limitConfidence() * 100) + "%");
        return m;
    }

    /** A netting set's CSA terms, holdings and the collateral's effect at the peak PFE date (EXPOSURE_GUIDE.md section 18.3). */
    Map<String, Object> collateral(ExposureService.Scoped s, int set) {
        ExposureBook b = s.date().book();
        ExposureGrid g = b.grid();
        ExposureBook.SetInfo si = b.set(set);
        CsaTerms c = si.csa();
        ExposureMath.Model model = ExposureMath.Model.of(s.ask().collateral());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("csa", c.csaId());
        m.put("margined", c.present() && c.margined());
        m.put("label", csaLabel(c));
        m.put("thresholdThem", Math.round(c.thThem()));
        m.put("thresholdUs", Math.round(c.thUs()));
        m.put("mtaThem", Math.round(c.mtaThem()));
        m.put("mtaUs", Math.round(c.mtaUs()));
        m.put("independentAmount", Math.round(c.ia()));
        m.put("rounding", Math.round(c.rounding()));
        m.put("mporDays", c.present() ? c.mporDays() : null);
        m.put("frequencyDays", c.present() ? c.frequencyDays() : null);
        m.put("effectiveMporDays", c.present() ? c.mporDays() + c.frequencyDays() - 1 : null);
        m.put("lookbackDays", b.run().get("LOOKBACK_DAYS"));
        m.put("mporMismatch", si.flags().contains("MPOR_MISMATCH"));
        m.put("lookbackUsed", "the run's look-back of " + b.run().get("LOOKBACK_DAYS") + " business days"
                + (si.flags().contains("MPOR_MISMATCH") ? " (the CSA's margin period differs: flagged MPOR_MISMATCH)" : ""));
        m.put("heldToday", Math.round(c.heldToday()));
        m.put("imHeld", Math.round(c.imHeld()));
        m.put("netMtm", Math.round(si.v0()));
        m.put("trades", si.to() - si.from());
        m.put("model", model.text());
        List<String> noLookback = new ArrayList<>();
        for (int i = 0; i < g.primaryCount(); i++) {
            if (g.lookbackFor(g.primaryAt(i)) < 0) {
                noLookback.add(g.tenor(g.primaryAt(i)));
            }
        }
        m.put("todaysCollateralAt", noLookback);
        ExposureMath.Stats st = s.result().stats;
        int qa = st.at(s.ask().q());
        int i = Math.max(0, st.peakAt[qa]);
        double[][] enc = ExposureMath.exposure(b.value(set), g, b.paths(), c, model);
        double cMean = 0;
        for (int j = 0; j < b.paths(); j++) {
            cMean += enc[2][i * b.paths() + j];
        }
        cMean /= b.paths();
        Map<String, Object> at = new LinkedHashMap<>();
        at.put("tenor", g.tenor(g.primaryAt(i)));
        at.put("date", g.date(g.primaryAt(i)).toString());
        at.put("ee", Math.round(st.ee[i]));
        at.put("eeUncoll", Math.round(st.eeUncoll[i]));
        at.put("collateralMean", Math.round(cMean));
        at.put("benefit", st.eeUncoll[i] > 0 ? 1 - st.ee[i] / st.eeUncoll[i] : 0.0);
        m.put("atPeak", at);
        return m;
    }

    /** A CSA in a few words: {@code VM, 10d}, {@code IA only}, {@code none}. */
    static String csaLabel(CsaTerms c) {
        if (c == null || !c.present()) {
            return "none";
        }
        if (!c.margined()) {
            return "IA only";
        }
        return "VM, " + (c.mporDays() + c.frequencyDays() - 1) + "d" + (c.thThem() > 0 ? ", threshold " + compact(c.thThem()) : "");
    }

    static String compact(double usd) {
        double a = Math.abs(usd);
        return a >= 1e9 ? trim(usd / 1e9) + "bn" : a >= 1e6 ? trim(usd / 1e6) + "m" : a >= 1e3 ? trim(usd / 1e3) + "k" : String.valueOf(Math.round(usd));
    }

    private static String trim(double x) {
        return Math.abs(x - Math.rint(x)) < 0.05 ? String.valueOf(Math.round(x)) : String.format(java.util.Locale.ROOT, "%.1f", x);
    }

    private static Map<String, Object> trade(ExposureBook.TradeInfo t, ExposureBook.SetInfo si) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("nettingSet", si.id());
        m.put("counterparty", t.counterparty());
        m.put("legalEntity", t.legalEntity());
        m.put("desk", t.desk());
        m.put("book", t.book());
        m.put("assetClass", t.assetClass());
        m.put("product", t.product());
        m.put("sourceSystem", t.sourceSystem());
        m.put("notional", Math.round(t.notional()));
        m.put("maturity", t.maturity());
        m.put("mtm", Math.round(t.mtm0()));
        return m;
    }

    // ---------------------------------------------------------------------------------------------------------------------- the tree

    /** A tree node's EE at every primary date (whole USD): the sparkline of the counterparty board. */
    static List<Long> eeLine(ExposureMath.Stats st) {
        List<Long> out = new ArrayList<>(st.ee.length);
        for (double x : st.ee) {
            out.add(Math.round(x));
        }
        return out;
    }

    /** The primary dates' tenor labels of the date's run. */
    static List<String> tenors(ExposureGrid g) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < g.primaryCount(); i++) {
            out.add(g.tenor(g.primaryAt(i)));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------------- the fan

    /** The scope's exposure quantiles (5, 25, 50, 75, 95%) at every primary date: the path fan drawn around EE. */
    static List<Map<String, Object>> fan(ExposureService.Scoped s, ExposureMath.Rule rule) {
        ExposureBook b = s.date().book();
        ExposureGrid g = b.grid();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < g.primaryCount(); i++) {
            double[] sorted = ExposureMath.sortedRow(s.result().e, i, b.paths());
            Map<String, Object> r = new LinkedHashMap<>();
            int k = g.primaryAt(i);
            r.put("tenor", g.tenor(k));
            r.put("date", g.date(k).toString());
            r.put("t", ExposureViews.round4(g.time(k)));
            for (double q : FAN) {
                r.put("q" + Math.round(q * 100), Math.round(ExposureMath.quantileSorted(sorted, q, rule)));
            }
            out.add(r);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------------- trades

    /**
     * Each trade row of a contribution table with the trade's attributes; with {@code alone}, its peak PFE as if it had no netting set
     * (uncollateralised, at the request's confidence) and that peak's tenor.
     */
    void trades(ExposureService.Scoped s, List<Map<String, Object>> rows, boolean alone) {
        ExposureBook b = s.date().book();
        ExposureGrid g = b.grid();
        int p = b.paths();
        List<String> ids = new ArrayList<>();
        rows.forEach(r -> ids.add(String.valueOf(r.get("key"))));
        Map<String, double[]> tp = alone ? svc.paths(s.date()).read(ids) : Map.of();
        for (Map<String, Object> r : rows) {
            String id = String.valueOf(r.get("key"));
            int t = b.tradeOrdinal(id);
            if (t < 0) {
                continue;
            }
            ExposureBook.TradeInfo ti = b.trade(t);
            r.put("nettingSet", b.set(ti.set()).id());
            r.put("product", ti.product());
            r.put("desk", ti.desk());
            r.put("book", ti.book());
            r.put("assetClass", ti.assetClass());
            r.put("notional", Math.round(ti.notional()));
            r.put("maturity", ti.maturity());
            r.put("mtm", Math.round(ti.mtm0()));
            if (alone) {
                double[] m = tp.get(id);
                double best = 0;
                int bestAt = -1;
                for (int i = 0; m != null && i < g.primaryCount(); i++) {
                    int k = g.primaryAt(i);
                    double[] row = new double[p];
                    for (int j = 0; j < p; j++) {
                        row[j] = Math.max(m[k * p + j], 0);
                    }
                    java.util.Arrays.sort(row);
                    double q = ExposureMath.quantileSorted(row, s.ask().q(), svc.props().rule());
                    if (bestAt < 0 || q > best) {
                        best = q;
                        bestAt = i;
                    }
                }
                r.put("peakPfeAlone", Math.round(best));
                r.put("peakPfeAloneTenor", bestAt < 0 ? null : g.tenor(g.primaryAt(bestAt)));
            }
        }
    }

    /** Each part's EE contribution at every primary date (its mean per date; the parts add up to the scope's EE at every date). */
    static void profiles(ExposureService.Scoped s, List<Map<String, Object>> rows, List<ExposureCalc.Part> parts) {
        int p = s.date().book().paths();
        int gp = s.date().book().grid().primaryCount();
        Map<String, ExposureCalc.Part> byKey = new HashMap<>();
        parts.forEach(x -> byKey.put(x.key(), x));
        for (Map<String, Object> r : rows) {
            ExposureCalc.Part part = byKey.get(String.valueOf(r.get("key")));
            if (part == null) {
                continue;
            }
            List<Long> line = new ArrayList<>(gp);
            for (int i = 0; i < gp; i++) {
                double sum = 0;
                for (int j = 0; j < p; j++) {
                    sum += part.x()[i * p + j];
                }
                line.add(Math.round(sum / p));
            }
            r.put("profile", line);
        }
    }
}
