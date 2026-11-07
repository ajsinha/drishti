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
package com.ash.drishti.banking.exposure;

import com.ash.drishti.server.explain.ExplainRequest;
import com.ash.drishti.server.explain.Explainer;
import com.ash.drishti.server.explain.Explanation;
import com.ash.drishti.server.security.Principal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Explain an exposure figure (kind {@code exposure}; EXPOSURE_GUIDE.md section 18): peak PFE, CE, EPE, EEPE, peak EE and EAD of a scope
 * (a netting set, a counterparty, a group), from the engine's own answer for the person ({@link ExposureViews#scope}): the formula with its
 * values, the inputs and lineage (the run, the model, the paths and grid, the inputs' versions), the checks. Why it moved and the workbook
 * are not built for exposure yet (the under-construction registry says when).
 *
 * <p>Arguments: {@code scope} (required), {@code date}, {@code q} (the PFE quantile), {@code collateral}, {@code run}.
 */
final class ExposureNumberExplainer implements Explainer {

    static final List<String> MEASURES = List.of("peak-pfe", "ce", "epe", "eepe", "peak-ee", "ead");

    private final ExposureViews views;

    ExposureNumberExplainer(ExposureViews views) {
        this.views = views;
    }

    @Override
    public String kind() {
        return "exposure";
    }

    @Override
    public String title() {
        return "Counterparty exposure of a scope: PFE, CE, EPE, EEPE, EE and EAD";
    }

    @Override
    public List<String> measures() {
        return MEASURES;
    }

    @Override
    public String engine() {
        return "exposure";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Explanation explain(Principal p, ExplainRequest r) {
        String scope = ExposureService.scopeText(r.required("scope", "scope=NSET:<netting set>, CPTY:<counterparty>, GROUP:<group>, LE:<legal entity>"));
        String measure = r.measureOr("peak-pfe");
        ExposureService.Ask ask = ExposureService.Ask.of(views.service().props(), r.text("q"), r.text("collateral"), r.text("run"), null);
        Map<String, Object> a = views.scope(p, scope, r.date("date"), ask);
        Map<String, Object> prov = a.get("provenance") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String q = pct(num(a.get("confidence")));
        String horizon = Explanation.number(num(a.get("horizonYears")));
        long paths = (long) num(a.get("paths"));
        Explanation x = new Explanation("exposure", measure, String.valueOf(a.get("canonical")), title(measure, q) + ", " + a.get("label"));
        x.value(value(measure, a), "USD", String.valueOf(a.get("businessDate")));
        x.origin("computed by the exposure engine in the server for you (" + prov.get("engine") + ")");
        String sets = Explanation.number(num(prov.get("nettingSets"))) + " netting sets";
        switch (measure) {
            case "ce" -> {
                x.preview("today's exposure: Σ over netting sets of max(MtM − C − IM, 0)", sets);
                x.formula("CE = E at t₀ = Σ_n max(MTM_n − C_n − IM_n, 0)   (" + sets + ")");
            }
            case "epe" -> {
                x.preview("EE averaged over the first " + horizon + " years", sets);
                x.formula("EE(k) = (1/P) Σ_p E_S(p,k)   (P = " + Explanation.number(paths) + " paths)");
                x.formula("EPE = Σ_{t_k ≤ T} EE(k) Δt_k / T   (T = min(1 year, the longest maturity) = " + horizon + " years)");
            }
            case "eepe" -> {
                x.preview("effective EE (never falling) averaged over the first " + horizon + " years", sets);
                x.formula("EEE(k) = max(EEE(k−1), EE(k)), EEE(0) = EE(0)");
                x.formula("EEPE = Σ_{t_k ≤ T} EEE(k) Δt_k / T   (T = " + horizon + " years)");
            }
            case "peak-ee" -> {
                x.preview("the highest EE over the primary dates", sets);
                x.formula("EE(k) = (1/P) Σ_p E_S(p,k)   (P = " + Explanation.number(paths) + " paths)");
                x.formula("peak EE = max_k EE(k)");
            }
            case "ead" -> {
                x.preview("α × EEPE = " + a.get("alpha") + " × " + Explanation.number(num(a.get("eepe"))), sets);
                x.formula("EAD (IMM) = α × EEPE");
                x.formula("= " + a.get("alpha") + " × " + Explanation.number(num(a.get("eepe"))) + " = " + Explanation.number(num(a.get("eadImm"))));
            }
            default -> {
                x.preview("the " + q + " quantile of " + Explanation.number(paths) + " paths at its highest date (" + a.get("peakPfeDate") + ")", sets);
                x.formula("PFE_q(k) = the ⌈P·q⌉-th smallest of E_S(·,k)   (P = " + Explanation.number(paths) + ", q = " + q + ")");
                x.formula("peak PFE = max_k PFE_q(k) at " + a.get("peakPfeTenor") + " (" + a.get("peakPfeDate") + ")");
            }
        }
        Double v = value(measure, a);
        x.formula(title(measure, q) + " = " + (v == null ? "—" : Explanation.number(v)) + " USD");
        if (r.wants("inputs")) {
            inputs(x, a, prov);
        }
        Object failed = prov.get("failedTrades");
        x.check(num(failed) == 0 || failed == null, num(failed) > 0 ? Explanation.number(num(failed)) + " trades failed to price: left out"
                : "inputs complete (every trade priced on every path)");
        x.unbuilt("why", "explain-exposure-why");
        x.unbuilt("workbook", "explain-exposure-workbook");
        x.unbuilt("official-check", "explain-official-check");
        x.link("The exposure page of this scope", "/exposure?scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8));
        x.link("Guide: exposure", "/help/context/exposure");
        return x;
    }

    /** The provenance, row for row as the exposure page's How this number was built box showed it, and the lineage. */
    private static void inputs(Explanation x, Map<String, Object> a, Map<String, Object> p) {
        List<String> resolved = strings(p.get("resolved"));
        x.input("Scope", a.get("kind") + " · " + a.get("canonical"));
        x.input("Resolved", resolved.isEmpty() ? String.valueOf(p.get("scope")) : String.join("; ", resolved));
        x.input("Netting sets", Explanation.number(num(p.get("nettingSets"))) + " (" + Explanation.number(num(p.get("wholeSets"))) + " whole, "
                + Explanation.number(num(p.get("partialSets"))) + " cut)");
        x.input("Trades", Explanation.number(num(p.get("trades"))));
        x.input("Outside your view", num(p.get("hiddenSets")) > 0 ? Explanation.number(num(p.get("hiddenSets"))) + " netting sets, "
                + Explanation.number(num(p.get("hiddenTrades"))) + " trades" : "none");
        x.input("Run", p.get("runId") + " · " + p.get("model") + " " + (p.get("modelVersion") == null ? "" : p.get("modelVersion")) + " · seed " + p.get("seed"));
        x.input("Calibration", p.get("calibration") + " · " + p.get("valueBasis") + " · " + p.get("precision"));
        x.input("Grid", Explanation.number(num(p.get("gridDates"))) + " dates (" + Explanation.number(num(p.get("primaryDates"))) + " primary), look-back "
                + p.get("lookbackDays") + " business days");
        x.input("Method", p.get("method"));
        x.input("Contributions", p.get("contributions"));
        List<String> flags = strings(a.get("flags"));
        x.input("Flags", flags.isEmpty() ? "none" : String.join(", ", flags));
        List<String> warnings = strings(p.get("warnings"));
        x.input("Warnings", warnings.isEmpty() ? "none" : String.join("; ", warnings));
        x.input("Tier", p.get("tier"));
        x.input("Engine", p.get("engine"));
        x.input("View", p.get("view"));
        x.input("Members", p.get("membershipHash"));
        String at = String.valueOf(p.get("computedAt")).replace("T", " ");
        x.input("Computed", at.substring(0, Math.min(19, at.length())) + " · " + p.get("computeMs") + " ms · cache " + p.get("cache"));
        if (p.get("inputs") instanceof Map<?, ?> in) {
            in.forEach((k, v) -> x.lineage(String.valueOf(k), "business date " + a.get("businessDate"), String.valueOf(v)));
        }
        x.lineage("exposure run", String.valueOf(p.get("runId")), String.valueOf(p.get("modelVersion")));
    }

    private static String title(String measure, String q) {
        return switch (measure) {
            case "ce" -> "CE";
            case "epe" -> "EPE";
            case "eepe" -> "EEPE";
            case "peak-ee" -> "Peak EE";
            case "ead" -> "EAD (IMM)";
            default -> "Peak PFE " + q;
        };
    }

    private static Double value(String measure, Map<String, Object> a) {
        Object v = switch (measure) {
            case "ce" -> a.get("ce");
            case "epe" -> a.get("epe");
            case "eepe" -> a.get("eepe");
            case "peak-ee" -> a.get("peakEe");
            case "ead" -> a.get("eadImm");
            default -> a.get("peakPfe");
        };
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static String pct(double q) {
        double p = Math.round(q * 1000) / 10.0;
        return (p == Math.rint(p) ? Long.toString((long) p) : Double.toString(p)) + "%";
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(v -> out.add(String.valueOf(v)));
        }
        return out;
    }
}
