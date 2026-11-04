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
package com.ash.drishti.server.saccr;

import com.ash.drishti.compute.saccr.Saccr;
import com.ash.drishti.compute.saccr.SaccrInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SA-CCR answers as JSON (docs/guides/SACCR_GUIDE.md section 9; docs/contracts/SACCR.md section 9.1, the computed kind
 * {@code saccr-result}). One answer feeds every view of the SA-CCR page: the waterfall (A), the netting-set list with the selected set's
 * breakdown (B) and a counterparty's sets side by side (C). Every figure, the waterfall's steps and each class's add-on after the
 * multiplier included, is the server's: the page draws, it computes nothing.
 */
final class SaccrAnswer {

    static final Map<String, String> CLASS_LABEL = Map.of("IR", "Interest rate", "FX", "FX", "CREDIT", "Credit", "EQUITY", "Equity", "COMMODITY",
            "Commodity");

    private SaccrAnswer() {}

    /** The page's answer: the scope's totals, every netting set's summary (with its waterfall) and the selected set's detail. */
    static Map<String, Object> scope(SaccrService.Run run, String selected, SaccrService svc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", run.scope());
        m.put("kind", run.kind());
        m.put("businessDate", run.date() == null ? null : run.date().toString());
        m.put("regulation", svc.params().describe());
        m.put("parameters", Map.of("file", svc.paramsFile(), "sha256", svc.params().hash, "version", svc.params().version));
        m.put("currency", "USD");
        double ead = 0;
        double rc = 0;
        double pfe = 0;
        int trades = 0;
        int excluded = 0;
        int unclassified = 0;
        List<Map<String, Object>> sets = new ArrayList<>();
        List<SaccrService.SetRun> sorted = new ArrayList<>(run.sets());
        sorted.sort(Comparator.comparingDouble(SaccrService.SetRun::ead).reversed().thenComparing(SaccrService.SetRun::id));
        for (SaccrService.SetRun s : sorted) {
            sets.add(summary(s));
            ead += s.ead();
            if (s.result() != null) {
                rc += s.result().rc();
                pfe += s.result().pfe();
                trades += s.result().terms().size();
            }
            excluded += s.excluded();
            unclassified += s.unclassified().size();
        }
        Map<String, Object> tot = new LinkedHashMap<>();
        tot.put("EAD", ead);
        tot.put("RC", rc);
        tot.put("PFE", pfe);
        tot.put("NETTING_SETS", run.sets().size());
        tot.put("ROWS", trades);
        tot.put("EXCLUDED_TRADES", excluded);
        tot.put("UNCLASSIFIED_TRADES", unclassified);
        tot.put("RWA", ead * svc.props().riskWeight());
        tot.put("RISK_WEIGHT", svc.props().riskWeight());
        m.put("totals", tot);
        if (!sorted.isEmpty()) {
            SaccrService.SetRun big = sorted.get(0);
            m.put("largest", Map.of("nettingSet", big.id(), "EAD", big.ead(), "share", ead > 0 ? big.ead() / ead : 0));
        }
        m.put("sets", sets);
        SaccrService.SetRun sel = selected != null && !selected.isBlank() ? run.set(selected.strip()) : sorted.isEmpty() ? null : sorted.get(0);
        m.put("selected", sel == null ? null : detail(sel));
        m.put("note", "EAD adds over netting sets: a counterparty's EAD is the sum of its sets'. Nothing below a set adds (square roots, absolute values, "
                + "the multiplier), so a class's or a trade's figure is never a share of the set's EAD.");
        m.put("provenance", run.provenance());
        return m;
    }

    /** One netting set's row (B's list, C's columns) with its waterfall. */
    static Map<String, Object> summary(SaccrService.SetRun s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nettingSet", s.id());
        m.put("counterparty", s.counterparty());
        m.put("legalEntity", s.legalEntity());
        m.put("csa", s.csa());
        m.put("status", s.status());
        m.put("flags", s.flags());
        m.put("members", s.members());
        m.put("excluded", s.excluded());
        m.put("unclassified", s.unclassified().size());
        Saccr.Result r = s.result();
        if (r == null) {
            m.put("EAD", 0.0);
            m.put("note", s.collateralNote());
            return m;
        }
        SaccrInput.NettingSet h = r.set();
        Saccr.Basis b = r.binding();
        m.put("margining", h.margined() ? "margined" : "unmargined");
        m.put("rows", r.terms().size());
        m.put("refused", r.refusals().size());
        m.put("V", r.v());
        m.put("C", h.c());
        m.put("NICA", h.nica());
        m.put("RC", b.rc());
        m.put("ADD_ON", b.addOnAgg());
        m.put("MULTIPLIER", b.multiplier());
        m.put("PFE", b.pfe());
        m.put("EAD", r.ead());
        m.put("EAD_UNMARGINED", r.unmargined().ead());
        m.put("EAD_MARGINED", r.margined() == null ? null : r.margined().ead());
        m.put("CAPPED", r.capped());
        m.put("basis", r.margined() == null || r.capped() ? "unmargined" : "margined");
        m.put("waterfall", waterfall(r));
        return m;
    }

    /** The waterfall: RC, each class's add-on after the multiplier, RC + PFE, × α, EAD; each step with where it starts and ends. */
    static List<Map<String, Object>> waterfall(Saccr.Result r) {
        Saccr.Basis b = r.binding();
        List<Map<String, Object>> out = new ArrayList<>();
        double at = 0;
        out.add(step("RC", "RC", 0, b.rc(), "rc"));
        at = b.rc();
        for (String ac : Saccr.ASSET_CLASSES) {
            double v = b.addOns().getOrDefault(ac, 0.0) * b.multiplier();
            if (b.addOns().getOrDefault(ac, 0.0) > 0) {
                out.add(step(ac, CLASS_LABEL.get(ac), at, at + v, "class"));
                at += v;
            }
        }
        out.add(step("RC_PFE", "RC + PFE", 0, b.rc() + b.pfe(), "subtotal"));
        out.add(step("ALPHA", "× " + fmtAlpha(r.params().get("alpha")), b.rc() + b.pfe(), r.ead(), "alpha"));
        out.add(step("EAD", "EAD", 0, r.ead(), "total"));
        return out;
    }

    private static String fmtAlpha(double a) {
        return a == Math.rint(a) ? String.valueOf((long) a) : String.valueOf(a);
    }

    private static Map<String, Object> step(String key, String label, double from, double to, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("from", from);
        m.put("to", to);
        m.put("value", to - from);
        m.put("role", role);
        return m;
    }

    /** One netting set in full (A): the header, both bases, the add-ons by class and hedging set, the formula with its figures. */
    static Map<String, Object> detail(SaccrService.SetRun s) {
        Map<String, Object> m = summary(s);
        Saccr.Result r = s.result();
        m.put("unclassifiedTrades", s.unclassified());
        m.put("collateralNote", s.collateralNote());
        if (r == null) {
            return m;
        }
        SaccrInput.NettingSet h = r.set();
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("MARGINED", h.margined());
        head.put("CLEARED", h.cleared());
        head.put("TH", h.th());
        head.put("MTA", h.mta());
        head.put("NICA", h.nica());
        head.put("MPOR_DAYS", h.mporDays());
        head.put("MARGIN_FREQUENCY_DAYS", h.marginFrequencyDays());
        head.put("DISPUTES_LAST_2Q", h.disputes());
        head.put("ILLIQUID_COLLATERAL", h.illiquidCollateral());
        head.put("MPOR_FLOOR", r.mporFloor());
        head.put("MPOR", r.mpor());
        head.put("MF_MARGINED", r.mfMargined());
        m.put("header", head);
        m.put("unmarginedBasis", basis(r.unmargined()));
        m.put("marginedBasis", r.margined() == null ? null : basis(r.margined()));
        Saccr.Basis b = r.binding();
        List<Map<String, Object>> classes = new ArrayList<>();
        for (String ac : Saccr.ASSET_CLASSES) {
            double a = b.addOns().getOrDefault(ac, 0.0);
            if (a > 0) {
                classes.add(Map.of("assetClass", ac, "label", CLASS_LABEL.get(ac), "ADD_ON", a, "AFTER_MULTIPLIER", a * b.multiplier()));
            }
        }
        m.put("classes", classes);
        boolean marg = r.margined() != null && !r.capped();
        List<Map<String, Object>> hs = new ArrayList<>();
        for (Saccr.HedgingSet x : r.hedgingSets()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("assetClass", x.assetClass());
            e.put("hedgingSet", x.hedgingSet());
            e.put("rows", x.rows());
            e.put("EFFECTIVE_NOTIONAL", marg ? x.enMargined() : x.enUnmargined());
            e.put("ADD_ON", marg ? x.addOnMargined() : x.addOnUnmargined());
            List<Map<String, Object>> subs = new ArrayList<>();
            for (Saccr.SubKey k : x.subKeys()) {
                if (k.rows() == 0) {
                    continue;
                }
                Map<String, Object> sk = new LinkedHashMap<>();
                sk.put("key", k.key());
                sk.put("D", marg ? k.dMargined() : k.dUnmargined());
                sk.put("SF", k.sf());
                sk.put("RHO", k.rho());
                double ao = marg ? k.addOnMargined() : k.addOnUnmargined();
                sk.put("ADD_ON", Double.isFinite(ao) ? ao : null);
                sk.put("rows", k.rows());
                subs.add(sk);
            }
            e.put("SUB_KEYS", subs);
            hs.add(e);
        }
        m.put("hedgingSets", hs);
        m.put("formula", formula(r));
        m.put("refusals", r.refusals().stream().map(x -> Map.of("tradeId", String.valueOf(x.tradeId()), "driver", x.driver(), "reason", x.reason())).toList());
        return m;
    }

    private static Map<String, Object> basis(Saccr.Basis b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("RC", b.rc());
        m.put("ADD_ONS", b.addOns());
        m.put("ADD_ON", b.addOnAgg());
        m.put("EXPONENT", b.exponent());
        m.put("MULTIPLIER", b.multiplier());
        m.put("PFE", b.pfe());
        m.put("EAD", b.ead());
        return m;
    }

    /** The formula lines with this set's figures, as the waterfall's panel prints them (text made on the server). */
    static List<String> formula(Saccr.Result r) {
        Saccr.Basis b = r.binding();
        SaccrInput.NettingSet h = r.set();
        boolean margined = r.margined() != null && !r.capped();
        List<String> out = new ArrayList<>();
        out.add(margined ? String.format("RC = max(V − C, TH + MTA − NICA, 0) = max(%s − %s, %s + %s − %s, 0) = %s", m(r.v()), m(h.c()), m(h.th()), m(h.mta()),
                m(h.nica()), m(b.rc())) : String.format("RC = max(V − C, 0) = max(%s − %s, 0) = %s", m(r.v()), m(h.c()), m(b.rc())));
        out.add(String.format("add-on = Σ class add-ons = %s", m(b.addOnAgg())));
        out.add(String.format("multiplier = min(1, 0.05 + 0.95 · exp((V − C) / (2 · 0.95 · add-on))) = %.6f", b.multiplier()));
        out.add(String.format("PFE = multiplier × add-on = %s", m(b.pfe())));
        out.add(String.format("EAD = α × (RC + PFE) = %s × %s = %s", fmtAlpha(r.params().get("alpha")), m(b.rc() + b.pfe()), m(b.ead())));
        if (r.margined() != null) {
            out.add(String.format("cap: min(EAD margined %s, EAD unmargined %s) = %s%s", m(r.margined().ead()), m(r.unmargined().ead()), m(r.ead()),
                    r.capped() ? " (the cap binds)" : ""));
        }
        return out;
    }

    /** USD in millions with two decimals ("4.40m"). */
    static String m(double v) {
        return String.format("%.2fm", v / 1e6);
    }

    /** A trade's terms (Trades view; GET /trade/{id}). */
    static Map<String, Object> terms(Saccr.Terms t, double standalone) {
        Map<String, Object> m = new LinkedHashMap<>();
        SaccrInput.Trade x = t.trade();
        m.put("tradeId", x.tradeId());
        m.put("driver", x.driver());
        m.put("assetClass", x.assetClass());
        m.put("hedgingSet", x.hedgingSet());
        m.put("subKey", t.subKey());
        m.put("transactionType", x.transactionType());
        m.put("direction", x.direction());
        m.put("notionalUsd", x.notional());
        m.put("S", t.s());
        m.put("E", t.e());
        m.put("M", t.m());
        m.put("T", Double.isFinite(t.t()) ? t.t() : null);
        m.put("SD", t.sd());
        m.put("d", t.d());
        m.put("d1", Double.isFinite(t.d1()) ? t.d1() : null);
        m.put("delta", t.delta());
        m.put("MF_UNMARGINED", t.mfUnmargined());
        m.put("MF_MARGINED", t.mfMargined());
        m.put("D_UNMARGINED", t.dUnmargined());
        m.put("D_MARGINED", t.dMargined());
        m.put("bucket", t.bucket() == 0 ? null : t.bucket());
        m.put("SF", t.sf());
        m.put("MTM", Double.isFinite(x.mtm()) ? x.mtm() : null);
        if (Double.isFinite(standalone)) {
            m.put("standaloneEad", standalone);
        }
        return m;
    }
}
