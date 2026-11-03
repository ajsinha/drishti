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
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.compute.ccr.ExposureMath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One netting set's exposure worked out for the Exposure Playground (docs/architecture/COUNTERPARTY_ENGINES.md sections 4 and 8.2,
 * docs/guides/EXPOSURE_GUIDE.md section 29): the simulated values of the trades shown and of Rest of set on the first {@code n} paths,
 * and every step after them computed by the exposure engine's own code ({@link ExposureMath}, {@link CsaTerms}) on exactly those paths.
 * The workbook writes the values as data and every step as a formula; these figures are the "Engine" column it is checked against, and
 * the cached results of its formulas. Nothing here is a second implementation of the method: the steps the engine does in one call
 * ({@link CsaTerms#called}) are split into the workbook's columns (target, rounded, C) only to cache them, and C is checked equal to the
 * engine's collateral.
 *
 * <p>Matrices are row-major with {@code n} columns: {@code m[k * n + j]} for grid date (or primary point) k and path j.
 */
final class ExposurePlaygroundModel {

    /** One column of the Data sheet: a trade shown, or Rest of set (the summed values of the trades not shown). */
    record Column(String id, String label, boolean rest, int trades, double mtm0, double lifeYears, double[] values, int stride) {

        /** The value on path j at grid date k. */
        double at(int k, int j) {
            return values == null ? 0 : values[k * stride + j];
        }
    }

    /** What the playground was asked and what it is about (Main's choices and the Provenance tab). */
    record About(String nettingSet, String counterparty, String businessDate, String runId, String calibration, int runPaths, int setTrades,
            Integer mporDays, Integer frequencyDays, Integer lookbackDays, Map<String, Object> provenance, Map<String, Object> runFigures) {}

    final About about;
    final ExposureGrid grid;
    /** Every grid date's time in years (the run's ACT/365, or a hand-built case's exact quarters). */
    final double[] times;
    final List<Column> columns;
    final int n;
    final CsaTerms csa;
    final ExposureMath.Model model;
    final ExposureMath.Rule rule;
    final double[] qs;
    final double alpha;
    final int contributionAt;
    final boolean contributionPeak;

    // ---- the steps, on the n paths shown ----
    final double[] v;
    final double[] target;
    final double[] rounded;
    final double[] c;
    final double[] e;
    final double[] neg;
    final double[] pos;
    final double im;
    final double v0;
    final double life;
    final ExposureMath.Stats stats;
    final double[] primaryTimes;
    final int peakEeAt;
    /** Per path at the contribution date: 1 - (C + IM) / V where E &gt; 0 and V is not 0, else 0. */
    final double[] factor;
    /** Each column's EE share at the contribution date (Euler; they add up to EE there). */
    final double[] share;

    /**
     * @param times every grid date's time in years
     * @param contributionAt the primary point whose EE the contributions split; -1: the peak EE date
     */
    ExposurePlaygroundModel(About about, ExposureGrid grid, double[] times, List<Column> columns, int n, CsaTerms csa, ExposureMath.Model model,
            ExposureMath.Rule rule, double[] qs, double alpha, int contributionAt) {
        if (n < 1) {
            throw new IllegalArgumentException("the playground needs at least one path");
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("the netting set has no trades with paths");
        }
        if (times.length != grid.size()) {
            throw new IllegalArgumentException("one time per grid date: " + grid.size() + " dates, " + times.length + " times");
        }
        this.about = about;
        this.grid = grid;
        this.times = times.clone();
        this.columns = List.copyOf(columns);
        this.n = n;
        this.csa = csa == null ? CsaTerms.NONE : csa;
        this.model = model;
        this.rule = rule;
        this.qs = qs.clone();
        this.alpha = alpha;
        int g = grid.size();
        int gp = grid.primaryCount();
        v = new double[g * n];
        double sum0 = 0;
        double lf = 0;
        for (Column col : this.columns) {
            for (int k = 0; k < g; k++) {
                for (int j = 0; j < n; j++) {
                    v[k * n + j] += col.at(k, j);
                }
            }
            sum0 += col.mtm0();
            lf = Math.max(lf, col.lifeYears());
        }
        v0 = sum0;
        life = lf;
        double[][] enc = ExposureMath.exposure(v, grid, n, this.csa, model);
        e = enc[0];
        neg = enc[1];
        c = enc[2];
        pos = ExposureMath.positive(v, grid, n);
        im = model == ExposureMath.Model.NONE ? 0 : this.csa.imHeld();
        target = new double[gp * n];
        rounded = new double[gp * n];
        for (int i = 0; i < gp; i++) {
            int lb = grid.lookbackFor(grid.primaryAt(i));
            for (int j = 0; j < n; j++) {
                if (lb < 0) {
                    target[i * n + j] = Double.NaN;
                    rounded[i * n + j] = Double.NaN;
                } else {
                    double x = this.csa.target(v[lb * n + j]);
                    target[i * n + j] = x;
                    rounded[i * n + j] = round(x);
                }
            }
        }
        primaryTimes = new double[gp];
        for (int i = 0; i < gp; i++) {
            primaryTimes[i] = this.times[grid.primaryAt(i)];
        }
        double ce = ExposureMath.currentExposure(v0, this.csa, model);
        stats = ExposureMath.statistics(e, neg, pos, primaryTimes, n, Math.min(1.0, life), qs, ce, rule);
        int pk = 0;
        for (int i = 1; i < gp; i++) {
            if (stats.ee[i] > stats.ee[pk]) {
                pk = i;
            }
        }
        peakEeAt = pk;
        contributionPeak = contributionAt < 0;
        this.contributionAt = contributionAt < 0 ? pk : Math.min(contributionAt, gp - 1);
        factor = new double[n];
        int ci = this.contributionAt;
        int ck = grid.primaryAt(ci);
        for (int j = 0; j < n; j++) {
            double vv = v[ck * n + j];
            factor[j] = e[ci * n + j] > 0 && vv != 0 ? 1 - (c[ci * n + j] + im) / vv : 0;
        }
        share = new double[this.columns.size()];
        double[] colM = new double[g * n];
        for (int x = 0; x < this.columns.size(); x++) {
            Column col = this.columns.get(x);
            for (int k = 0; k < g; k++) {
                for (int j = 0; j < n; j++) {
                    colM[k * n + j] = col.at(k, j);
                }
            }
            double[] a = ExposureMath.tradeShare(colM, v, e, c, im, grid, n);
            double s = 0;
            for (int j = 0; j < n; j++) {
                s += a[ci * n + j];
            }
            share[x] = s / n;
        }
    }

    /** The CSA's rounding of a call as a change from today's collateral (deliveries up, returns down), as {@link CsaTerms#called} does. */
    double round(double x) {
        if (csa.rounding() <= 0) {
            return x;
        }
        double delta = x - csa.heldToday();
        delta = delta > 0 ? Math.ceil(delta / csa.rounding()) * csa.rounding() : -Math.floor(-delta / csa.rounding()) * csa.rounding();
        return csa.heldToday() + delta;
    }

    int primaryCount() {
        return grid.primaryCount();
    }

    /** The tenor label of primary point i. */
    String tenor(int i) {
        return grid.tenor(grid.primaryAt(i));
    }

    /** Δt of primary point i: its time less the previous primary point's (the first: its time). */
    double dt(int i) {
        return primaryTimes[i] - (i == 0 ? 0 : primaryTimes[i - 1]);
    }

    /** The averaging horizon T = min(1 year, the longest remaining maturity). */
    double horizon() {
        return Math.min(1.0, life);
    }

    /** The weight of primary point i in EPE and EEPE: max(0, min(t_i, T) - t_(i-1)). */
    double weight(int i) {
        double prev = i == 0 ? 0 : primaryTimes[i - 1];
        return Math.max(0, Math.min(primaryTimes[i], horizon()) - prev);
    }

    double eadImm() {
        return alpha * stats.eepe;
    }

    /** The trades counted in the columns (Rest of set counts its trades). */
    int tradesCounted() {
        return columns.stream().mapToInt(Column::trades).sum();
    }

    boolean hasRest() {
        return columns.stream().anyMatch(Column::rest);
    }

    /** The collateral model as Main's switch spells it. */
    String modelText() {
        return model.text();
    }

    String ruleText() {
        return rule == ExposureMath.Rule.KTH ? "kth" : "interpolated";
    }

    /** Main's figures as the browser and the API show them (the browser computes nothing). */
    Map<String, Object> figures() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ce", stats.ce);
        m.put("horizonYears", horizon());
        m.put("epe", stats.epe);
        m.put("eepe", stats.eepe);
        m.put("peakEe", stats.peakEe);
        m.put("peakEeTenor", tenor(peakEeAt));
        List<Map<String, Object>> peaks = new ArrayList<>();
        for (int a = 0; a < qs.length; a++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("q", qs[a]);
            p.put("peakPfe", stats.peakPfe[a]);
            p.put("tenor", stats.peakAt[a] < 0 ? null : tenor(stats.peakAt[a]));
            peaks.add(p);
        }
        m.put("peakPfe", peaks);
        m.put("alpha", alpha);
        m.put("eadImm", eadImm());
        return m;
    }

    /** The profile per primary date. */
    List<Map<String, Object>> profile() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < primaryCount(); i++) {
            int k = grid.primaryAt(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("tenor", tenor(i));
            r.put("date", grid.date(k).toString());
            r.put("t", primaryTimes[i]);
            r.put("dt", dt(i));
            r.put("lookback", grid.lookbackFor(k) < 0 ? null : grid.date(grid.lookbackFor(k)).toString());
            r.put("ee", stats.ee[i]);
            r.put("ene", stats.ene[i]);
            List<Double> pfe = new ArrayList<>();
            for (int a = 0; a < qs.length; a++) {
                pfe.add(stats.pfe[a][i]);
            }
            r.put("pfe", pfe);
            r.put("eee", stats.eee[i]);
            r.put("eeUncollateralised", stats.eeUncoll[i]);
            r.put("collateralEffect", stats.ee[i] - stats.eeUncoll[i]);
            r.put("weight", weight(i));
            out.add(r);
        }
        return out;
    }

    /** The first {@code rows} paths at primary point i: V, L, the collateral steps, E, N and max(V, 0). */
    List<Map<String, Object>> paths(int i, int rows) {
        int k = grid.primaryAt(i);
        int lb = grid.lookbackFor(k);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int j = 0; j < Math.min(rows, n); j++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("path", j + 1);
            r.put("v", v[k * n + j]);
            r.put("l", lb < 0 ? null : v[lb * n + j]);
            r.put("target", lb < 0 ? null : target[i * n + j]);
            r.put("rounded", lb < 0 ? null : rounded[i * n + j]);
            r.put("c", c[i * n + j]);
            r.put("e", e[i * n + j]);
            r.put("n", neg[i * n + j]);
            r.put("uncollateralised", pos[i * n + j]);
            out.add(r);
        }
        return out;
    }

    /** The columns' EE shares at the contribution date, the largest first as the workbook lists them (Rest of set last). */
    List<Map<String, Object>> contributions(int rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        double ee = stats.ee[contributionAt];
        for (int x = 0; x < columns.size() && out.size() < rows; x++) {
            Column col = columns.get(x);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", col.id());
            r.put("label", col.label());
            r.put("rest", col.rest());
            r.put("trades", col.trades());
            r.put("mtm0", col.mtm0());
            r.put("eeShare", share[x]);
            r.put("pct", ee == 0 ? 0 : 100 * share[x] / ee);
            out.add(r);
        }
        return out;
    }
}
