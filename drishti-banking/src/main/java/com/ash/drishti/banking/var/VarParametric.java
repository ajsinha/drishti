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

import com.ash.drishti.compute.linalg.Blas;
import com.ash.drishti.banking.compute.var.ParametricVar;
import com.ash.drishti.banking.compute.var.VarLens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code parametric} blocks of the VaR answers (docs/architecture/PARAMETRIC_VAR.md sections 6, 8 and 9; VAR_GUIDE.md section 18.15):
 * the headline's, a tree node's, the P&amp;L path's curve, the contributions' columns and the correlation matrix, each figure with the
 * {@code describe} lines its <b>?</b> shows. Every number is computed here, in the server; the browser places text and draws the points.
 * A cross-check, never official: {@code official} is always false.
 */
public final class VarParametric {

    private final VarParametricProperties props;

    public VarParametric(VarParametricProperties props) {
        this.props = props;
    }

    public boolean enabled() {
        return props.enabled();
    }

    public VarParametricProperties props() {
        return props;
    }

    /**
     * The parametric figures of a vector read through a lens: the lens's own vector ({@code lens}), or under {@code square-root-of-time}
     * a 10-day figure from the 1-day vector's μ·10 and σ·√10.
     */
    public ParametricVar.Figures figures(double[] vector, double[] oneDay, VarLens lens) {
        ParametricVar.Settings st = props.settings();
        if (!lens.tenDay() || props.horizonRule().equals("lens") || oneDay == null) {
            return ParametricVar.of(vector, lens.confidence(), lens.esConfidence(), st);
        }
        ParametricVar.Figures one = ParametricVar.of(oneDay, lens.confidence(), lens.esConfidence(), st);
        ParametricVar.Fit f = one.fit();
        int h = VarLens.TEN;
        ParametricVar.Fit scaled = new ParametricVar.Fit(f.n(), f.mu() * h, f.sigma() * Math.sqrt(h), f.skew(), f.excessKurtosis(), f.weights(), st);
        double zs = one.z() * scaled.sigma();
        double var = zs - scaled.mu();
        int beyond = 0;
        for (double x : vector) {
            if (x < -var) {
                beyond++;
            }
        }
        return new ParametricVar.Figures(scaled, one.confidence(), one.esConfidence(), one.z(), one.zEs(), one.esFactor(), var,
                scaled.sigma() * one.esFactor() - scaled.mu(), zs, beyond);
    }

    /**
     * A headline's block (section 6): the figures, the shape, the gap against historical, the notes and the tooltip's lines.
     *
     * @param historicalVar the answer's historical VaR, {@code historicalEs} its ES, {@code k} the rank it was read at
     */
    public Map<String, Object> headline(ParametricVar.Figures f, VarLens lens, String label, String set, String endDate, double historicalVar,
            double historicalEs, int k) {
        Map<String, Object> m = cell(f, historicalVar, historicalEs);
        m.put("confidence", lens.confidence());
        m.put("esConfidence", lens.esConfidence());
        m.put("mu", f.fit().mu());
        m.put("sigma", f.fit().sigma());
        m.put("n", f.fit().n());
        m.put("z", f.z());
        m.put("zEs", f.zEs());
        m.put("esFactor", f.esFactor());
        m.put("zSigma", f.zSigma());
        m.put("weighting", props.weighting());
        m.put("lambda", props.weighting().equals("ewma") ? props.lambda() : null);
        m.put("horizonRule", props.horizonRule());
        m.put("skew", f.fit().skew());
        m.put("excessKurtosis", f.fit().excessKurtosis());
        m.put("beyond", f.beyond());
        m.put("expectedBeyond", f.expectedBeyond());
        m.put("official", false);
        m.put("notes", notes(f, lens));
        m.put("describe", describe(f, lens, label, set, endDate, historicalVar, k));
        return m;
    }

    /** A tree node's or a tile's figures: VaR, ES, the gap, and its short tooltip. */
    public Map<String, Object> cell(ParametricVar.Figures f, double historicalVar, double historicalEs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("var", Math.round(f.var()));
        m.put("es", Math.round(f.es()));
        double gap = historicalVar - f.var();
        m.put("gap", Math.round(gap));
        m.put("gapPct", f.var() == 0 ? null : gap / f.var());
        m.put("esGap", Math.round(historicalEs - f.es()));
        return m;
    }

    /** A tree node's block with its own short tooltip (the formula with the node's numbers, the gap). */
    public Map<String, Object> treeCell(ParametricVar.Figures f, VarLens lens, String label, double historicalVar, double historicalEs) {
        Map<String, Object> m = cell(f, historicalVar, historicalEs);
        List<String> d = new ArrayList<>();
        d.add(title("Parametric VaR", lens, label));
        d.add("A cross-check, not the official VaR. The official figure is historical: " + money(historicalVar) + ".");
        d.add(formula(f));
        d.add(gapLine(historicalVar, f));
        m.put("describe", d);
        return m;
    }

    /** The P&amp;L path's block (section 8): the headline's figures, the curve on the bars' scale, the expected counts, the two markers. */
    public Map<String, Object> path(Map<String, Object> headline, ParametricVar.Figures f, double[] edges, double historicalVar) {
        Map<String, Object> m = new LinkedHashMap<>(headline);
        ParametricVar.Curve c = ParametricVar.curve(f, edges, props.curvePoints());
        Map<String, Object> curve = new LinkedHashMap<>();
        curve.put("x", c.x());
        curve.put("y", c.y());
        m.put("curve", curve);
        m.put("expected", c.expected());
        m.put("binWidth", c.binWidth());
        Map<String, Object> markers = new LinkedHashMap<>();
        markers.put("historical", -historicalVar);
        markers.put("parametric", f.fit().mu() - f.zSigma());
        m.put("markers", markers);
        m.put("showCurve", props.showCurve());
        List<String> hover = new ArrayList<>();
        hover.add("Fitted normal N(μ, σ²) · n = " + f.fit().n() + " · μ " + money(f.fit().mu()) + " · σ " + money(f.fit().sigma()));
        hover.add("Expected days per bin at P&L x = n × bin width × φ((x − μ)/σ)/σ (bin width " + money(c.binWidth()) + ")");
        hover.add(formula(f));
        if (props.weighting().equals("ewma")) {
            hover.add("The bars are unweighted counts; the curve is the EWMA-weighted fit (λ = " + props.lambda() + ").");
        }
        m.put("curveDescribe", hover);
        return m;
    }

    /** One contribution row's parametric columns (section 6), with the component's tooltip. */
    public Map<String, Object> row(ParametricVar.Component c, ParametricVar.Figures scope, double size, String label, String scopeLabel,
            boolean addsUp, int parts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pComponentVar", Math.round(c.componentVar()));
        m.put("pComponentEs", Math.round(c.componentEs()));
        m.put("pStandaloneVar", Math.round(c.standaloneVar()));
        m.put("pIncrementalVar", Math.round(c.incrementalVar()));
        m.put("pMarginalPer1m", Double.isNaN(size) || size == 0 ? null : Math.round(c.componentVar() / Math.abs(size) * 1_000_000));
        m.put("pMarginalPer1pct", Math.round(0.01 * c.componentVar()));
        m.put("pPct", scope.var() == 0 ? null : c.componentVar() / scope.var());
        m.put("rho", c.rho());
        m.put("sigma", c.sigma());
        m.put("mu", c.mu());
        m.put("cov", c.cov());
        m.put("zCovOverSigma", scope.fit().sigma() > 0 ? scope.z() * c.cov() / scope.fit().sigma() : 0);
        List<String> d = new ArrayList<>();
        d.add("Parametric component VaR · " + label + " in " + scopeLabel);
        d.add("A cross-check, not the official VaR.");
        d.add("Component = z·cov(Pᵢ, P)/σ_P − μᵢ = z·ρ·σᵢ − μᵢ");
        d.add("cov = " + sci(c.cov()) + " · σ_P = " + money(scope.fit().sigma()) + " · ρ = " + fixed(c.rho(), 4) + " · σᵢ = " + money(c.sigma())
                + " · μᵢ = " + money(c.mu()));
        d.add(fixed(scope.z(), 4) + " × " + sci(c.cov()) + " / " + money(scope.fit().sigma()) + " − (" + money(c.mu()) + ") = "
                + money(c.componentVar()) + (scope.var() == 0 ? "" : " (" + pct(c.componentVar() / scope.var()) + " of the scope)"));
        d.add("Standalone (the part alone) = z·σᵢ − μᵢ = " + money(c.standaloneVar()) + "; incremental = VaR(P) − VaR(P − Pᵢ) = "
                + money(c.incrementalVar()));
        if (addsUp) {
            d.add("The " + parts + " components add up to the scope's " + money(scope.var()) + " exactly (Σ cov = σ², Σ μᵢ = μ).");
        }
        m.put("describe", d);
        return m;
    }

    /** The contributions' totals. */
    public Map<String, Object> totals(ParametricVar.Figures scope, double sumComponent, double sumStandalone, boolean addsUp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("var", Math.round(scope.var()));
        m.put("es", Math.round(scope.es()));
        m.put("pSumComponent", Math.round(addsUp ? sumComponent : scope.var()));
        m.put("pSumStandalone", Math.round(sumStandalone));
        m.put("pDiversification", Math.round(sumStandalone - scope.var()));
        m.put("addsUp", addsUp);
        m.put("official", false);
        return m;
    }

    /** The parts' correlation and covariance matrix (one dgemm through the bound linear algebra; the largest parts beyond the cap). */
    public Map<String, Object> matrix(List<String> keys, List<String> labels, List<double[]> vectors, double sigma) {
        int max = props.matrixMaxParts();
        boolean truncated = keys.size() > max;
        List<double[]> vs = truncated ? vectors.subList(0, max) : vectors;
        double[][][] cm = ParametricVar.matrix(vs, props.settings(), Blas.get());
        double check = 0;
        for (double[] row : cm[0]) {
            for (double x : row) {
                check += x;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keys", truncated ? keys.subList(0, max) : keys);
        m.put("labels", truncated ? labels.subList(0, max) : labels);
        m.put("correlation", cm[1]);
        m.put("covariance", cm[0]);
        m.put("truncated", truncated);
        m.put("sigmaCheck", truncated ? null : Math.sqrt(Math.max(0, check)));
        m.put("sigma", sigma);
        m.put("engine", Blas.get().name());
        return m;
    }

    // ---- the tooltip's lines (section 9) ---------------------------------------------------------------------------------------

    List<String> describe(ParametricVar.Figures f, VarLens lens, String label, String set, String endDate, double historicalVar, int k) {
        List<String> d = new ArrayList<>();
        d.add(title("Parametric VaR", lens, label));
        d.add("A cross-check, not the official VaR. The official figure is historical: " + money(historicalVar) + ".");
        d.add("Method: normal (variance–covariance) on this scope's own scenario P&L vector: the same " + f.fit().n()
                + " values historical VaR ranks.");
        d.add("Window: " + f.fit().n() + (lens.method() == VarLens.Method.SUM ? " 10-day windows" : " scenario days") + ", the set " + set
                + (endDate == null ? "" : ", ending " + endDate) + ".");
        d.add(props.weighting().equals("ewma") ? "Weighting: EWMA, λ = " + props.lambda() + ": the newest day weighs "
                + pct(1 - props.lambda()) + "." : "Weighting: equal (1/n each); EWMA off.");
        d.add("Confidence: " + pct(lens.confidence()) + " → z = " + fixed(f.z(), 4) + ".");
        d.add("Horizon: " + horizon(lens, f) + ".");
        d.add("n, μ, σ: " + f.fit().n() + " · " + money(f.fit().mu()) + " · " + money(f.fit().sigma())
                + (props.weighting().equals("ewma") ? " (weighted)" : " (sample, n − 1)") + ".");
        d.add(formula(f));
        d.add("ES = σ·φ(z_ES)/(1 − " + trim(lens.esConfidence()) + ") − μ = " + money(f.fit().sigma()) + " × " + fixed(f.esFactor(), 4) + " − ("
                + money(f.fit().mu()) + ") = " + money(f.es()) + ".");
        d.add("Historical VaR (" + ordinal(k) + " worst of " + f.fit().n() + "): " + money(historicalVar) + ".");
        d.add(gapLine(historicalVar, f) + " Skew " + fixed(f.fit().skew(), 2) + ", excess kurtosis " + fixed(f.fit().excessKurtosis(), 2)
                + " (a normal has 0 and 0). " + f.beyond() + " scenario days lie beyond the parametric VaR; the normal expects "
                + fixed(f.expectedBeyond(), 2) + ".");
        d.add("Computed in the server (VaR engine), never in the browser.");
        return d;
    }

    List<String> notes(ParametricVar.Figures f, VarLens lens) {
        List<String> n = new ArrayList<>();
        if (f.fit().sigma() == 0) {
            n.add("σ = 0: no spread, the normal is a point (VaR = ES = −μ).");
        }
        if (lens.method() == VarLens.Method.SUM && props.horizonRule().equals("lens")) {
            n.add("10-day sum: " + f.fit().n() + " overlapping windows, about " + Math.max(1, f.fit().n() / 10) + " independent periods.");
        }
        if (props.weighting().equals("ewma")) {
            n.add("EWMA: the histogram is unweighted, the curve weighted.");
        }
        if (f.var() < 0) {
            n.add("μ exceeds z·σ: the parametric VaR is negative (a gain at the confidence).");
        }
        return n;
    }

    private String horizon(VarLens lens, ParametricVar.Figures f) {
        if (!lens.tenDay()) {
            return "1 day";
        }
        if (props.horizonRule().equals("square-root-of-time")) {
            return "10 days: μ × 10 and σ × √10 from the 1-day vector (square-root-of-time)";
        }
        return lens.method() == VarLens.Method.SUM ? "10 days: " + f.fit().n() + " overlapping windows (about " + Math.max(1, f.fit().n() / 10)
                + " independent periods)" : "10 days: σ and μ × √10 (the lens scales the vector)";
    }

    private static String title(String what, VarLens lens, String label) {
        return what + " " + pct(lens.confidence()) + " · " + (lens.tenDay() ? "10 days" : "1 day") + (lens.stressed() ? " · stressed" : "")
                + (label == null ? "" : " · " + label);
    }

    static String formula(ParametricVar.Figures f) {
        return "VaR = z·σ − μ = " + fixed(f.z(), 4) + " × " + money(f.fit().sigma()) + " − (" + money(f.fit().mu()) + ") = " + money(f.zSigma())
                + " − (" + money(f.fit().mu()) + ") = " + money(f.var());
    }

    static String gapLine(double historicalVar, ParametricVar.Figures f) {
        double gap = historicalVar - f.var();
        return "Fat-tail gap = historical − parametric = " + (gap >= 0 ? "+" : "−") + money(Math.abs(gap))
                + (f.var() == 0 ? "" : " (" + (gap >= 0 ? "+" : "−") + pct(Math.abs(gap / f.var())) + ")") + ". "
                + (gap >= 0 ? "Positive: the scope's real tail is heavier than a normal with the same σ, so the normal understates the loss."
                        : "Negative: the worst days were milder than a normal with the same σ predicts.");
    }

    static String money(double v) {
        return String.format(Locale.ROOT, "%,d", Math.round(v)).replace('-', '−');
    }

    static String fixed(double v, int places) {
        return String.format(Locale.ROOT, "%." + places + "f", v).replace('-', '−');
    }

    static String sci(double v) {
        return String.format(Locale.ROOT, "%.4e", v).replace('-', '−');
    }

    static String pct(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v * 100);
        return (s.endsWith(".0") ? s.substring(0, s.length() - 2) : s) + "%";
    }

    private static String trim(double v) {
        String s = Double.toString(v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String ordinal(int k) {
        int m = k % 100;
        String suf = m >= 11 && m <= 13 ? "th" : k % 10 == 1 ? "st" : k % 10 == 2 ? "nd" : k % 10 == 3 ? "rd" : "th";
        return k + suf;
    }
}
