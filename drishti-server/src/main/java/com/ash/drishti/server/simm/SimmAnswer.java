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
package com.ash.drishti.server.simm;

import com.ash.drishti.compute.simm.Simm;
import com.ash.drishti.compute.simm.SimmChoices;
import com.ash.drishti.compute.simm.SimmCompare;
import com.ash.drishti.compute.simm.SimmParams;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The JSON of the SIMM answers (docs/guides/SIMM_GUIDE.md section 9, the computed kind {@code simm-result} of SIMM.md section 9.1): the
 * margin of a netting set both ways, its steps, the Choices panel, a comparison, and the margin against the counterparty's call. The
 * console only draws these: every figure is the engine's.
 */
final class SimmAnswer {

    private SimmAnswer() {}

    static Double num(double v) {
        return Double.isFinite(v) ? v : null;
    }

    /** The headline (SIMM.md section 9.1): both directions, by product class and by risk class. */
    static Map<String, Object> result(SimmService.Run run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("SCOPE", run.scope());
        m.put("KIND", run.kind());
        m.put("ANALYTIC", !run.kind().equals(SimmService.NETTING_SET));
        m.put("NETTING_SET", run.nettingSet() == null ? null : run.nettingSet().id());
        m.put("COUNTERPARTY", run.nettingSet() == null ? null : run.nettingSet().counterparty());
        m.put("CSA", run.nettingSet() == null ? null : run.nettingSet().csa());
        m.put("BUSINESS_DATE", run.date() == null ? null : run.date().toString());
        m.put("SIMM_VERSION", run.version().version());
        m.put("PARAMETER_SHA256", run.collect().params().hash);
        m.put("CALCULATION_CURRENCY", run.calculationCurrency());
        m.put("IM_COLLECT", run.imCollect());
        m.put("IM_POST", run.imPost());
        m.put("COLLECT_REGIME", run.collectRegime());
        m.put("POST_REGIME", run.postRegime());
        m.put("SIMM_COLLECT", run.collect().simm());
        m.put("ADDON_COLLECT", run.collect().addOn().total());
        m.put("SIMM_POST", run.post().simm());
        m.put("ADDON_POST", run.post().addOn().total());
        List<Map<String, Object>> pcs = new ArrayList<>();
        for (String pc : SimmParams.PRODUCT_CLASSES) {
            Simm.ProductIm c = run.collect().product(pc);
            Simm.ProductIm p = run.post().product(pc);
            if (c == null && p == null) {
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("PRODUCT_CLASS", pc);
            x.put("COLLECT", c == null ? 0 : c.simm());
            x.put("POST", p == null ? 0 : p.simm());
            pcs.add(x);
        }
        m.put("BY_PRODUCT_CLASS", pcs);
        m.put("BY_RISK_CLASS", byRiskClass(run.collect(), run.post()));
        m.put("TRADES", run.records());
        m.put("MISSING_TRADES", run.missingTrades());
        m.put("SWITCHES", run.collect().choices().asMap());
        m.put("OVERRIDES", run.collect().params().overrides);
        m.put("REGULATORY", run.collect().regulatory() && run.kind().equals(SimmService.NETTING_SET));
        if (!run.collect().regulatory()) {
            m.put("notRegulatory", "not the regulatory figure: " + run.collect().choices().changed() + (run.collect().params().overrides.isEmpty() ? ""
                    : " overrides " + run.collect().params().overrides));
        }
        if (!run.kind().equals(SimmService.NETTING_SET)) {
            m.put("label", run.kind().equals(SimmService.EXAMPLE) ? "the hand-built examples with SIMM " + run.version().version() : SimmService.ANALYTIC);
        }
        m.put("refused", run.collect().refusals().size());
        m.put("PROVENANCE", run.provenance());
        return m;
    }

    static List<Map<String, Object>> byRiskClass(Simm.Result collect, Simm.Result post) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Simm.ClassIm c : collect.classes()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("PRODUCT_CLASS", c.productClass());
            x.put("RISK_CLASS", c.riskClass());
            x.put("DELTA", c.delta());
            x.put("VEGA", c.vega());
            x.put("CURVATURE", c.curvature());
            x.put("BASE_CORR", c.baseCorr());
            x.put("TOTAL", c.im());
            Simm.ClassIm p = post == null ? null : post.classes().stream().filter(y -> y.productClass().equals(c.productClass()) && y.riskClass().equals(c.riskClass()))
                    .findFirst().orElse(null);
            x.put("POST_TOTAL", p == null ? null : p.im());
            out.add(x);
        }
        return out;
    }

    /** The steps of one direction: per charge its buckets (K, S, the residual), per bucket its factors (s, RW, CR, WS). */
    static Map<String, Object> explain(SimmService.Run run, boolean post, String riskClass, int max) {
        Simm.Result r = post ? run.post() : run.collect();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", run.scope());
        m.put("direction", post ? "post" : "collect");
        m.put("version", run.version().version());
        List<Map<String, Object>> out = new ArrayList<>();
        int shown = 0;
        for (Simm.Charge c : r.charges()) {
            if (riskClass != null && !riskClass.isBlank() && !c.riskClass.equalsIgnoreCase(riskClass)) {
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("productClass", c.productClass);
            x.put("riskClass", c.riskClass);
            x.put("measure", c.measure);
            x.put("margin", c.margin);
            x.put("root", c.root);
            x.put("residual", c.residual);
            if (c.measure.equals(Simm.CURVATURE)) {
                x.put("theta", c.theta);
                x.put("lambda", c.lambda);
                x.put("sumCvr", c.sumCvr);
                x.put("scale", c.scale);
            }
            List<Map<String, Object>> bs = new ArrayList<>();
            for (Simm.Bucket b : c.buckets) {
                Map<String, Object> y = new LinkedHashMap<>();
                y.put("bucket", b.label);
                y.put("residual", b.residual);
                y.put("K", b.k);
                y.put("net", b.net);
                y.put("S", b.s);
                y.put("cr", b.cr);
                List<Map<String, Object>> fs = new ArrayList<>();
                for (int k : b.members) {
                    if (shown++ >= max) {
                        break;
                    }
                    Simm.Risk k2 = r.risks().get(k);
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("factor", k2.key);
                    f.put("s", k2.s);
                    f.put("rw", k2.rw);
                    f.put("rwKey", k2.rwKey);
                    f.put("hvr", k2.hvr);
                    f.put("cr", k2.cr);
                    f.put("ws", k2.ws);
                    f.put("trades", k2.parts.size());
                    fs.add(f);
                }
                y.put("factors", fs);
                bs.add(y);
            }
            x.put("buckets", bs);
            out.add(x);
        }
        m.put("charges", out);
        m.put("truncated", shown > max);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Simm.Group g : r.groups()) {
            if (g.cr > 1) {
                groups.add(Map.of("productClass", g.productClass, "riskClass", g.riskClass, "measure", g.measure, "group", g.group, "sum", g.sum, "threshold",
                        g.threshold, "cr", g.cr));
            }
        }
        m.put("concentration", groups);
        m.put("addOns", Map.of("fixed", r.addOn().fixed(), "notional", r.addOn().notional(), "multipliers", r.addOn().multipliers(), "lines", r.addOn().byProduct()));
        return m;
    }

    /** The Choices panel: each switch, and the parameter version with the installed ones. */
    static List<Map<String, Object>> choices(SimmChoices c, String version, List<String> installed) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", "simm-parameters");
        v.put("value", version);
        v.put("default", "the CSA's version");
        v.put("options", installed);
        v.put("rule", "SIMM (the CSA names its version)");
        v.put("why", "the CSA's version is the margin agreed; another installed version shows a recalibration's effect");
        out.add(v);
        for (SimmChoices.Switch s : SimmChoices.SWITCHES) {
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

    static Map<String, Object> diff(SimmCompare.Diff d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", d.label());
        m.put("base", d.baseTotal());
        m.put("alternative", d.alternativeTotal());
        m.put("difference", d.difference());
        m.put("words", d.words());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (SimmCompare.Line l : d.lines()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("productClass", l.productClass());
            x.put("riskClass", l.riskClass());
            x.put("measure", l.measure());
            x.put("base", l.base());
            x.put("alternative", l.alternative());
            x.put("difference", l.difference());
            x.put("bucket", l.bucket());
            x.put("step", l.step());
            lines.add(x);
        }
        m.put("lines", lines);
        return m;
    }

    /** The playground's grid: every weighted sensitivity of a direction with its formula in words (the formula bar). */
    static List<Map<String, Object>> grid(Simm.Result r, int max) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Simm.Charge c : r.charges()) {
            for (Simm.Bucket b : c.buckets) {
                for (int k : b.members) {
                    if (rows.size() >= max) {
                        return rows;
                    }
                    Simm.Risk x = r.risks().get(k);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("productClass", x.productClass);
                    row.put("riskClass", x.riskClass);
                    row.put("measure", x.measure);
                    row.put("bucket", b.label);
                    row.put("factor", x.key);
                    row.put("s", x.s);
                    row.put("rw", x.measure.equals(Simm.CURVATURE) ? null : x.rw);
                    row.put("hvr", x.hvr);
                    row.put("cr", x.cr);
                    row.put("ws", x.ws);
                    row.put("K", b.k);
                    row.put("formula", formula(x));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static String formula(Simm.Risk x) {
        String f = String.format(Locale.ROOT, "%,.2f", x.s);
        return switch (x.measure) {
            case Simm.CURVATURE -> "CVR = Σ SF(t) · σ · ∂V/∂σ = " + f + " (no concentration)";
            case Simm.VEGA -> "WS = VRW × HVR × VR × VCR = " + String.format(Locale.ROOT, "%.6g × %.6g × %s × %.6f", x.rw, x.hvr, f, x.cr) + " (" + x.rwKey + ")";
            case Simm.BASE_CORR -> "WS = RW × s = " + String.format(Locale.ROOT, "%.6g × %s", x.rw, f);
            default -> "WS = RW × s × CR = " + String.format(Locale.ROOT, "%.6g × %s × %.6f", x.rw, f, x.cr) + " (" + x.rwKey + ")";
        };
    }

    /** The margin against the counterparty's call (mock-up C; SIMM.md section 9.4). */
    static Map<String, Object> against(SimmService.Run run, List<SimmData.Call> calls, double tolerance, boolean read) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nettingSet", run.scope());
        m.put("version", run.version().version());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String dir : List.of("COLLECT", "POST")) {
            Simm.Result r = dir.equals("COLLECT") ? run.collect() : run.post();
            SimmData.Call call = calls.stream().filter(c -> c.direction().equals(dir)).findFirst().orElse(null);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("direction", dir.toLowerCase(Locale.ROOT));
            x.put("drishti", r.total());
            x.put("theirs", call == null ? null : num(call.total()));
            x.put("source", call == null ? null : call.source());
            Double d = call == null || !Double.isFinite(call.total()) ? null : r.total() - call.total();
            x.put("difference", d);
            x.put("status", call == null ? "no call" : d == null ? "no figure in the call" : Math.abs(d) < 0.5 ? "agreed"
                    : Double.isFinite(tolerance) && Math.abs(d) <= tolerance ? "within tolerance" : "dispute");
            List<Map<String, Object>> byRc = new ArrayList<>();
            for (String rc : SimmParams.RISK_CLASSES) {
                double ours = r.classes().stream().filter(c -> c.riskClass().equals(rc)).mapToDouble(Simm.ClassIm::im).sum();
                Double theirs = call == null ? null : call.byRiskClass().get(rc);
                if (ours == 0 && theirs == null) {
                    continue;
                }
                byRc.add(Map.of("riskClass", rc, "drishti", ours, "theirs", theirs == null ? "—" : theirs, "difference", theirs == null ? "—" : ours - theirs));
            }
            x.put("byRiskClass", byRc);
            rows.add(x);
        }
        m.put("rows", rows);
        m.put("tolerance", num(tolerance));
        if (!read) {
            m.put("note", "no connector serves crif-external: the counterparty's call is not loaded (SIMM.md section 3.4)");
        }
        m.put("method", com.ash.drishti.common.Branding.name("the product") + " never recomputes the counterparty's figure: the difference is split by risk class to trace a dispute "
                + "(the risk class margins are summed over the product classes, before ψ)");
        return m;
    }
}
