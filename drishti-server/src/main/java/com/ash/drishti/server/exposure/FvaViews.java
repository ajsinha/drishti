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

import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.compute.ccr.FvaMath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one FVA answer (docs/guides/FVA_GUIDE.md section 6): the scope's figures and terms by date, every netting set's figures and term bars,
 * the counterparties' sums, the curves and settings used, the flags and the provenance. The three screens (the netting-set page's FVA
 * section, the netting sets by FVA, the counterparty page) and the playground are views of it; the browser computes nothing.
 */
final class FvaViews {

    private FvaViews() {}

    static Map<String, Object> answer(FvaService.Answer a) {
        ExposureService.Scoped s = a.scoped();
        ExposureBook b = s.date().book();
        ExposureGrid g = b.grid();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", s.res().canonical());
        m.put("kind", s.kind());
        m.put("label", s.label());
        m.put("businessDate", s.date().date().toString());
        m.put("runId", b.runId());
        m.put("calibration", a.calibration().isEmpty() ? null : a.calibration());
        m.put("valueBasis", a.valueBasis().isEmpty() ? null : a.valueBasis());
        m.put("currency", "USD");
        m.put("flags", a.flags());
        m.put("figures", figures(a.total()));
        m.put("epeReading", reading(a));
        m.put("cva", Map.of("status", "planned", "text", "CVA (planned): the CVA family joins this engine later and reads the same EE profile"));
        m.put("settings", settings(a));
        List<Map<String, Object>> terms = new ArrayList<>();
        FvaMath.Result r = a.total();
        for (int i = 0; i < r.t().length; i++) {
            int k = g.primaryAt(i);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("k", i + 1);
            t.put("date", g.date(k).toString());
            t.put("tenor", g.tenor(k));
            t.put("t", r.t()[i]);
            t.put("dt", r.dt()[i]);
            t.put("df", r.df()[i]);
            t.put("sb", r.sb()[i]);
            t.put("sl", r.sl()[i]);
            t.put("survival", r.survival() == null ? null : r.survival()[i]);
            t.put("ee", r.ee()[i]);
            t.put("ene", r.ene()[i]);
            t.put("eeStar", r.eeStar()[i]);
            t.put("eneStar", r.eneStar()[i]);
            t.put("eePoint", r.eePoint()[i]);
            t.put("enePoint", r.enePoint()[i]);
            t.put("fca", r.fcaTerm()[i]);
            t.put("fba", r.fbaTerm()[i]);
            terms.add(t);
        }
        m.put("terms", terms);
        List<Map<String, Object>> sets = new ArrayList<>();
        Map<String, Map<String, Object>> cps = new LinkedHashMap<>();
        for (FvaService.SetFva sf : a.sets()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("nettingSet", sf.id());
            n.put("counterparty", sf.counterparty());
            n.put("name", sf.name());
            n.put("csa", sf.csa());
            n.put("whole", sf.whole());
            n.putAll(figures(sf.result()));
            n.put("fcaTerms", round(sf.result().fcaTerm()));
            n.put("fbaTerms", round(sf.result().fbaTerm()));
            sets.add(n);
            Map<String, Object> c = cps.computeIfAbsent(sf.counterparty() == null ? "" : sf.counterparty(), id -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("counterparty", id);
                x.put("name", sf.name());
                x.put("fca", 0.0);
                x.put("fba", 0.0);
                x.put("fva", 0.0);
                x.put("nettingSets", 0);
                return x;
            });
            c.put("fca", (double) c.get("fca") + sf.result().fca());
            c.put("fba", (double) c.get("fba") + sf.result().fba());
            c.put("fva", (double) c.get("fva") + sf.result().fva());
            c.put("nettingSets", (int) c.get("nettingSets") + 1);
        }
        m.put("nettingSets", sets);
        List<Map<String, Object>> cpl = new ArrayList<>(cps.values());
        cpl.sort((x, y) -> Double.compare((double) x.get("fva"), (double) y.get("fva")));
        m.put("counterparties", cpl);
        m.put("nettingSetCount", s.result().pieces.size());
        m.put("setsShown", a.setsShown());
        if (!a.sets().isEmpty() && a.total().fva() != 0) {
            FvaService.SetFva top = a.sets().get(0);
            m.put("largest", Map.of("nettingSet", top.id(), "share", top.result().fva() / a.total().fva()));
        }
        Map<String, Object> prov = new LinkedHashMap<>();
        prov.put("engine", "fva (Java, the exposure engine's profile; FvaMath)");
        prov.put("exposure", Map.of("runId", b.runId(), "paths", b.paths(), "primaryDates", g.primaryCount(), "collateralModel", s.ask().collateral(),
                "dataVersion", String.valueOf(s.date().version())));
        prov.put("fundingCurve", Map.of("id", a.borrow().id(), "source", a.borrow().source()));
        prov.put("lendingCurve", Map.of("id", a.lend().id(), "source", a.lend().source()));
        prov.put("discountCurve", a.discount() == null ? Map.of("id", "none", "source", "the paths are discounted (VALUE_BASIS DISCOUNTED): DF = 1")
                : Map.of("id", a.discount().id(), "source", a.discount().source()));
        prov.put("computeMs", s.computeMs());
        prov.put("cached", s.cached());
        m.put("provenance", prov);
        m.put("playground", "PLAY FVA " + s.res().canonical());
        return m;
    }

    static Map<String, Object> figures(FvaMath.Result r) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fca", r.fca());
        f.put("fba", r.fba());
        f.put("fva", r.fva());
        f.put("sumEeDt", r.sumEeDt());
        f.put("life", r.life());
        f.put("epeLife", r.epeLife());
        f.put("eneLife", r.eneLife());
        return f;
    }

    /** The EPE reading (spread × life × EPE_life), meaningful when the borrowing spread is flat; said so otherwise. */
    static Map<String, Object> reading(FvaService.Answer a) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean flat = a.borrow().isFlat();
        m.put("flatSpread", flat);
        m.put("sumEeDt", a.total().sumEeDt());
        m.put("life", a.total().life());
        m.put("epeLife", a.total().epeLife());
        if (flat) {
            double sp = a.borrow().v()[0];
            m.put("spread", sp);
            m.put("product", sp * a.total().life() * a.total().epeLife());
            m.put("text", "funding spread × life × average exposure over the life: " + sp + " × " + a.total().life() + " × EPE_life (before discounting)");
        } else {
            m.put("text", "the spread is a curve: the one-line reading needs a flat spread");
        }
        return m;
    }

    static Map<String, Object> settings(FvaService.Answer a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eePoint", a.point().text());
        m.put("fba", a.fba());
        m.put("fundingSurvival", a.survivalOn());
        m.put("fundingSpread", curve(a.borrow(), "spread"));
        m.put("lendingSpread", a.lend() == a.borrow() ? "the funding spread (symmetric)" : curve(a.lend(), "spread"));
        m.put("discountCurve", a.discount() == null ? null : curve(a.discount(), "df"));
        return m;
    }

    static Map<String, Object> curve(FvaMath.Curve c, String field) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("source", c.source());
        m.put("flat", c.isFlat());
        List<Map<String, Object>> pts = new ArrayList<>();
        for (int i = 0; i < c.t().length; i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("tenor", i < c.tenors().size() ? c.tenors().get(i) : null);
            p.put("t", c.t()[i]);
            p.put(field, c.v()[i]);
            pts.add(p);
        }
        m.put("points", pts);
        return m;
    }

    private static double[] round(double[] x) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = Math.round(x[i] * 100) / 100.0;
        }
        return out;
    }
}
