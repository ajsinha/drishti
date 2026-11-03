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
package com.ash.drishti.server.frtb;

import com.ash.drishti.compute.frtb.Drc;
import com.ash.drishti.compute.frtb.FrtbChoices;
import com.ash.drishti.compute.frtb.FrtbCompare;
import com.ash.drishti.compute.frtb.FrtbSa;
import com.ash.drishti.compute.frtb.Rrao;
import com.ash.drishti.compute.frtb.Sbm;
import com.ash.drishti.compute.frtb.SbmModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON of the FRTB-SA answers (FRTB_SA_GUIDE.md section 9): the capital of a scope (the stack and the three scenarios), its steps, the
 * contributions, and the playground's grid with the Choices panel and a comparison. The console only draws these: every figure is the
 * engine's.
 */
final class FrtbAnswer {

    private FrtbAnswer() {}

    static Double num(double v) {
        return Double.isFinite(v) ? v : null;
    }

    /** The capital of a scope: SBM by class, measure and scenario, the binding one, DRC, RRAO, the total and the stack. */
    static Map<String, Object> sa(FrtbService.Run run) {
        FrtbSa.Result r = run.result();
        Sbm.Result s = r.sbm();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", run.scope());
        m.put("canonical", run.canonical());
        m.put("date", run.date() == null ? null : run.date().toString());
        m.put("currency", run.reporting());
        m.put("regulatory", r.choices().regulatory());
        boolean estimated = s.model.curvs.stream().anyMatch(c -> c.estimated);
        m.put("estimate", estimated);
        if (estimated) {
            m.put("estimateNote", "curvature estimated from gamma on " + s.model.curvs.stream().filter(c -> c.estimated).count()
                    + " factors: an analysis figure until the supervisor accepts it for the filing (MAR21.17)");
        }
        if (!r.choices().regulatory()) {
            m.put("notRegulatory", "not the regulatory figure: " + r.choices().changed() + (r.choices().overrides().isEmpty() ? "" : " overrides " + r.choices().overrides()));
        }
        List<Map<String, Object>> classes = new ArrayList<>();
        for (Sbm.ClassCharge c : s.charges) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("class", c.cls);
            x.put("measure", c.measure);
            x.put("low", c.charge[0]);
            x.put("medium", c.charge[1]);
            x.put("high", c.charge[2]);
            x.put("alternative", List.of(c.alternative[0], c.alternative[1], c.alternative[2]));
            x.put("buckets", c.buckets.size());
            classes.add(x);
        }
        m.put("classes", classes);
        m.put("sbm", Map.of("low", s.total[0], "medium", s.total[1], "high", s.total[2]));
        m.put("binding", Sbm.SCENARIOS[s.binding]);
        m.put("reported", Sbm.SCENARIOS[s.reported]);
        m.put("SBM", s.sbm());
        m.put("DRC", r.drc().delivered() ? r.drc().total() : null);
        m.put("RRAO", r.rrao().delivered() ? r.rrao().total() : null);
        if (!r.drc().delivered()) {
            m.put("drcNote", "DRC not computed: no default-risk inputs");
        }
        if (!r.rrao().delivered()) {
            m.put("rraoNote", "RRAO not computed: no residual-risk inputs");
        }
        m.put("total", r.total());
        m.put("complete", r.complete());
        // the capital stack (mock-up B): delta, vega, curvature of the reported scenario, then DRC, RRAO and the total
        double[] by = new double[3];
        for (Sbm.ClassCharge c : s.charges) {
            by[c.measure.equals(SbmModel.DELTA) ? 0 : c.measure.equals(SbmModel.VEGA) ? 1 : 2] += c.charge[s.reported];
        }
        List<Map<String, Object>> stack = new ArrayList<>();
        stack.add(step("Delta", by[0]));
        stack.add(step("Vega", by[1]));
        stack.add(step("Curvature", by[2]));
        stack.add(step("DRC", r.drc().delivered() ? r.drc().total() : Double.NaN));
        stack.add(step("RRAO", r.rrao().delivered() ? r.rrao().total() : Double.NaN));
        stack.add(step("Total", r.total()));
        m.put("stack", stack);
        m.put("refused", s.model.refusals.size());
        m.put("provenance", run.provenance());
        return m;
    }

    private static Map<String, Object> step(String label, double v) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("label", label);
        x.put("value", num(v));
        return x;
    }

    /** The steps of one class (or every class): buckets with their factors, WS, K, S and the curvature sides. */
    static Map<String, Object> explain(FrtbService.Run run, String cls, int maxFactors) {
        FrtbSa.Result r = run.result();
        SbmModel model = r.sbm().model;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", run.scope());
        m.put("date", run.date() == null ? null : run.date().toString());
        m.put("reported", Sbm.SCENARIOS[r.sbm().reported]);
        List<Map<String, Object>> out = new ArrayList<>();
        int shown = 0;
        for (Sbm.ClassCharge c : r.sbm().charges) {
            if (cls != null && !cls.isBlank() && !c.cls.equalsIgnoreCase(cls)) {
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("class", c.cls);
            x.put("measure", c.measure);
            x.put("charge", List.of(c.charge[0], c.charge[1], c.charge[2]));
            x.put("alternative", List.of(c.alternative[0], c.alternative[1], c.alternative[2]));
            List<Map<String, Object>> bs = new ArrayList<>();
            for (Sbm.Bucket b : c.buckets) {
                Map<String, Object> y = new LinkedHashMap<>();
                y.put("bucket", b.bucket);
                y.put("absSum", b.absSum);
                y.put("K", List.of(b.k[0], b.k[1], b.k[2]));
                y.put("S", List.of(b.s[0], b.s[1], b.s[2]));
                if (c.measure.equals(SbmModel.CURVATURE)) {
                    y.put("Kup", List.of(b.kUp[0], b.kUp[1], b.kUp[2]));
                    y.put("Kdown", List.of(b.kDown[0], b.kDown[1], b.kDown[2]));
                    y.put("side", List.of(b.side));
                } else {
                    y.put("net", b.net);
                }
                List<Map<String, Object>> fs = new ArrayList<>();
                for (int mbr : b.members) {
                    if (shown++ >= maxFactors) {
                        break;
                    }
                    Map<String, Object> f = new LinkedHashMap<>();
                    if (c.measure.equals(SbmModel.CURVATURE)) {
                        SbmModel.Curv cv = model.curvs.get(mbr);
                        f.put("factor", cv.key);
                        f.put("rw", cv.rw);
                        f.put("up", cv.up);
                        f.put("down", cv.down);
                        f.put("deltaPart", cv.deltaPart);
                        f.put("cvrUp", cv.cvrUp);
                        f.put("cvrDown", cv.cvrDown);
                        f.put("estimated", cv.estimated);
                        f.put("divided", cv.divided);
                    } else {
                        SbmModel.Risk k = model.risks.get(mbr);
                        f.put("factor", k.key);
                        f.put("s", k.s);
                        f.put("rw", k.rw);
                        f.put("rwSource", k.rwSource);
                        f.put("ws", k.ws());
                        f.put("positions", k.parts.size());
                    }
                    fs.add(f);
                }
                y.put("factors", fs);
                bs.add(y);
            }
            x.put("buckets", bs);
            out.add(x);
        }
        m.put("classes", out);
        m.put("truncated", shown > maxFactors);
        Drc.Result d = r.drc();
        if (d.delivered()) {
            m.put("drc", Map.of("buckets", d.buckets(), "obligors", d.obligors().size(), "total", d.total()));
        }
        Rrao.Result rr = r.rrao();
        if (rr.delivered()) {
            m.put("rrao", Map.of("instruments", rr.lines().size(), "exotic", rr.exotic(), "other", rr.other(), "total", rr.total()));
        }
        return m;
    }

    /** Contributions grouped by an attribute, with the check that they add up to the total. */
    static Map<String, Object> contributions(FrtbService.Run run, String by) {
        String attr = by == null || by.isBlank() ? "DESK" : by.strip().toUpperCase(java.util.Locale.ROOT);
        Map<String, Double> c = run.result().contributionsBy(attr);
        List<Map<String, Object>> rows = new ArrayList<>();
        double total = run.result().total();
        c.entrySet().stream().sorted((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue()))).forEach(e -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("key", e.getKey());
            x.put("contribution", e.getValue());
            x.put("share", total == 0 ? null : e.getValue() / total);
            rows.add(x);
        });
        double sum = c.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", run.scope());
        m.put("by", attr);
        m.put("total", total);
        m.put("sum", sum);
        m.put("addsUp", Math.abs(sum - total) < 0.005);
        m.put("rows", rows);
        m.put("method", "SBM by Euler in the " + Sbm.SCENARIOS[run.result().sbm().reported] + " scenario, DRC by obligor share, RRAO as is");
        return m;
    }

    /** The Choices panel: each switch with its default, options, rule, why, and the value of this run. */
    static List<Map<String, Object>> choices(FrtbChoices c) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FrtbChoices.Switch s : FrtbChoices.SWITCHES) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", s.name());
            x.put("value", c.get(s.name()));
            x.put("default", s.dflt());
            x.put("options", s.options());
            x.put("rule", s.rule());
            x.put("why", s.why());
            out.add(x);
        }
        return out;
    }

    /** A comparison as the playground shows it. */
    static Map<String, Object> diff(FrtbCompare.Diff d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", d.label());
        m.put("base", d.baseTotal());
        m.put("alternative", d.alternativeTotal());
        m.put("difference", d.difference());
        m.put("baseScenario", d.baseScenario());
        m.put("alternativeScenario", d.alternativeScenario());
        m.put("words", d.words());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (FrtbCompare.Line l : d.lines()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("class", l.cls());
            x.put("measure", l.measure());
            x.put("base", l.base());
            x.put("alternative", l.alternative());
            x.put("difference", l.difference());
            x.put("bucket", l.bucket());
            x.put("step", l.step());
            x.put("detail", l.detail());
            lines.add(x);
        }
        m.put("lines", lines);
        return m;
    }

    /** The playground's grid: the WS rows with their formulas in words (the formula bar), class by class. */
    static List<Map<String, Object>> grid(FrtbService.Run run, int max) {
        SbmModel model = run.result().sbm().model;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Sbm.ClassCharge c : run.result().sbm().charges) {
            for (Sbm.Bucket b : c.buckets) {
                for (int mbr : b.members) {
                    if (rows.size() >= max) {
                        return rows;
                    }
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("class", c.cls);
                    x.put("measure", c.measure);
                    x.put("bucket", b.bucket);
                    if (c.measure.equals(SbmModel.CURVATURE)) {
                        SbmModel.Curv cv = model.curvs.get(mbr);
                        x.put("factor", cv.key);
                        x.put("s", null);
                        x.put("rw", cv.rw);
                        x.put("ws", null);
                        x.put("cvrUp", cv.cvrUp);
                        x.put("cvrDown", cv.cvrDown);
                        x.put("formula", cv.estimated ? "CVR± = −½ · Γ · h² (estimate)" : "CVR+ = −(UP − RW · Σ s), CVR− = −(DOWN + RW · Σ s)");
                    } else {
                        SbmModel.Risk k = model.risks.get(mbr);
                        x.put("factor", k.key);
                        x.put("s", k.s);
                        x.put("rw", k.rw);
                        x.put("ws", k.ws());
                        x.put("formula", "WS = RW × s = " + String.format(java.util.Locale.ROOT, "%.6g", k.rw) + " × " + String.format(java.util.Locale.ROOT, "%,.2f",
                                k.s) + " (" + k.rwSource + ")");
                    }
                    x.put("K", List.of(b.k[0], b.k[1], b.k[2]));
                    rows.add(x);
                }
            }
        }
        return rows;
    }
}
