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

import com.ash.drishti.compute.var.VarMath;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The VaR Playground's calculation as its workbook writes it (docs/guides/VAR_GUIDE.md section 18.16), operation for operation, so
 * the cached value of every formula cell is the number the formula gives and the screen shows the same figures: each position's P&amp;L
 * from its scenario PVs (or P&amp;L vector) and the scenario FX, into the reporting currency, the portfolio, its tie-broken rank, VaR, ES,
 * the component window and scale, every position's standalone, component and incremental figures, every confidence and both 10-day
 * methods. The engine's own figures are computed apart ({@link VarPlayground}) and compared to these by the check cells.
 */
final class VarPlaygroundModel {

    static final double EPS = 1e-9;
    static final double SQRT10 = Math.sqrt(10);

    /**
     * One position (or trade) as the workbook's Config and Data hold it.
     *
     * @param form {@code PV} (scenario PVs in its own currency) or {@code PnL} (a P&amp;L vector as delivered)
     * @param basePv today's PV in its currency (input form PnL: its USD value today)
     * @param baseFx today's rate, USD per unit
     * @param data the scenario PVs (or P&amp;L), one per scenario
     */
    record Pos(String id, String kind, String label, String currency, double basePv, double baseFx, String form, String source, double[] data) {

        boolean pv() {
            return "PV".equals(form);
        }

        /** Config's "value today (USD)": {@code basePv × baseFx}. */
        double today() {
            return basePv * baseFx;
        }
    }

    /** One currency's rates (USD per unit): today's and each scenario's; {@code source} supplied, derived or base. */
    record Fx(String currency, double base, double[] rates, String source) {}

    /** The settings the workbook's named input cells hold. */
    record Settings(double confidence, double esConfidence, List<Double> confidences, List<Double> esConfidences, VarMath.RankRule rule,
            int window, boolean scaleToVar, String horizon, String method, int incremental) {

        boolean interpolated() {
            return rule == VarMath.RankRule.INTERPOLATED;
        }
    }

    final List<LocalDate> dates;
    final List<Pos> positions;
    final double[] rest;
    final List<Fx> fx;
    final String reporting;
    final Settings set;
    final int n;
    final Map<String, Fx> byCcy = new LinkedHashMap<>();
    final double[] portfolio;
    final int[] rank;
    final double[] wv;
    final double[] we;
    final double[] t10;
    final int[] r10;
    final double[] w10v;
    final double[] w10e;
    // the headline figures at the input confidences
    final int k;
    final int kEs;
    final int lo;
    final int hi;
    final double var1;
    final double es1;
    final double smoothed;
    final double scale;
    final int n10;
    final int k10;
    final int kEs10;
    final int lo10;
    final int hi10;
    final double var10;
    final double es10;
    final double smoothed10;
    final double scale10;
    final Fig[] figs;
    final Fig restFig;

    /** A position's figures (incremental NaN beyond the incremental ones). */
    record Fig(double standaloneVar, double standaloneEs, double componentVar, double componentEs, double incremental, double component10,
            double componentEs10) {}

    VarPlaygroundModel(List<LocalDate> dates, List<Pos> positions, double[] rest, List<Fx> fx, String reporting, Settings set) {
        this.dates = dates;
        this.positions = positions;
        this.rest = rest;
        this.fx = fx;
        this.reporting = reporting;
        this.set = set;
        this.n = dates.size();
        fx.forEach(f -> byCcy.put(f.currency(), f));
        portfolio = new double[n];
        double[] row = new double[positions.size()];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < positions.size(); i++) {
                row[i] = pnl(i, j);
            }
            double s = 0;
            for (double x : row) {
                s += x;
            }
            portfolio[j] = rest == null ? s : s + rest[j];
        }
        rank = rank(portfolio);
        k = kth(n, set.confidence());
        kEs = kth(n, set.esConfidence());
        int h = set.window() / 2;
        lo = Math.max(1, k - h);
        hi = Math.min(n, k + h);
        wv = new double[n];
        we = new double[n];
        for (int j = 0; j < n; j++) {
            wv[j] = rank[j] >= lo && rank[j] <= hi ? 1 : 0;
            we[j] = rank[j] <= kEs ? 1 : 0;
        }
        var1 = var(portfolio, set.confidence());
        es1 = -dot(we, portfolio) / kEs;
        smoothed = -dot(wv, portfolio) / (hi - lo + 1);
        scale = set.scaleToVar() ? (Math.abs(smoothed) > EPS ? var1 / smoothed : 1) : 1;
        n10 = Math.max(0, n - 9);
        t10 = new double[n10];
        for (int w = 0; w < n10; w++) {
            double s = 0;
            for (int j = w; j <= w + 9; j++) {
                s += portfolio[j];
            }
            t10[w] = s;
        }
        r10 = rank(t10);
        k10 = n10 == 0 ? 0 : kth(n10, set.confidence());
        kEs10 = n10 == 0 ? 0 : kth(n10, set.esConfidence());
        lo10 = Math.max(1, k10 - h);
        hi10 = Math.min(n10, k10 + h);
        w10v = new double[n];
        w10e = new double[n];
        for (int j = 0; j < n; j++) {
            double a = 0;
            double b = 0;
            for (int w = Math.max(j - 9, 0); w <= Math.min(j, n10 - 1); w++) {
                a += r10[w] >= lo10 && r10[w] <= hi10 ? 1 : 0;
                b += r10[w] <= kEs10 ? 1 : 0;
            }
            w10v[j] = a;
            w10e[j] = b;
        }
        var10 = n10 == 0 ? Double.NaN : var(t10, set.confidence());
        double s10 = 0;
        double e10 = 0;
        for (int w = 0; w < n10; w++) {
            s10 += (r10[w] >= lo10 && r10[w] <= hi10 ? 1 : 0) * t10[w];
            e10 += (r10[w] <= kEs10 ? 1 : 0) * t10[w];
        }
        es10 = n10 == 0 ? Double.NaN : -e10 / kEs10;
        smoothed10 = n10 == 0 ? Double.NaN : -s10 / (hi10 - lo10 + 1);
        scale10 = set.scaleToVar() ? (Math.abs(smoothed10) > EPS ? var10 / smoothed10 : 1) : 1;
        figs = new Fig[positions.size()];
        double[] p = new double[n];
        for (int i = 0; i < positions.size(); i++) {
            for (int j = 0; j < n; j++) {
                p[j] = pnl(i, j);
            }
            figs[i] = fig(p, i < set.incremental());
        }
        restFig = rest == null ? null : fig(rest, false);
    }

    private Fig fig(double[] p, boolean incremental) {
        double inc = Double.NaN;
        if (incremental) {
            double[] without = new double[n];
            for (int j = 0; j < n; j++) {
                without[j] = portfolio[j] - p[j];
            }
            inc = var1 - var(without, set.confidence());
        }
        return new Fig(var(p, set.confidence()), es(p, set.esConfidence()), -scale * dot(wv, p) / (hi - lo + 1), -dot(we, p) / kEs, inc,
                n10 == 0 ? Double.NaN : -scale10 * dot(w10v, p) / (hi10 - lo10 + 1), n10 == 0 ? Double.NaN : -dot(w10e, p) / kEs10);
    }

    /** The rate of {@code ccy} under scenario {@code j} ({@code -1}: today). */
    double rate(String ccy, int j) {
        Fx f = byCcy.get(ccy);
        return j < 0 ? f.base() : f.rates()[j];
    }

    /** A position's USD P&amp;L under scenario {@code j}, as the PnL tab's formula computes it. */
    double usd(int i, int j) {
        Pos p = positions.get(i);
        double d = p.data()[j];
        if (p.pv()) {
            return d * rate(p.currency(), j) - p.today();
        }
        return "USD".equals(p.currency()) ? d : d * rate(p.currency(), j);
    }

    /** The same in the reporting currency: {@code (p + usd) / FX_r(s) − p / FX_r(base)}. */
    double pnl(int i, int j) {
        double today = positions.get(i).today();
        return (today + usd(i, j)) / rate(reporting, j) - today / rate(reporting, -1);
    }

    /** k = max(1, min(N, ceil(N (1 − a) − 1e-9))): {@code MAX(1,MIN(N,ROUNDUP(N*(1-a)-1E-9,0)))}. */
    static int kth(int n, double a) {
        return Math.max(1, Math.min(n, (int) Math.ceil(n * (1 - a) - EPS)));
    }

    /** VaR of a vector by the rank rule, as the sheet's {@code SMALL} reads it. */
    double var(double[] v, double a) {
        return VarMath.var(v, a, set.rule());
    }

    /** ES of a vector as the sheet's no-rank formula computes it: the values below the k-th worst, plus the k-th worst to make up k. */
    static double es(double[] v, double a) {
        int k = kth(v.length, a);
        double kthWorst = v[VarMath.order(v)[k - 1]];
        double below = 0;
        int count = 0;
        for (double x : v) {
            if (x < kthWorst) {
                below += x;
                count++;
            }
        }
        return -(below + (k - count) * kthWorst) / k;
    }

    static double dot(double[] w, double[] v) {
        double s = 0;
        for (int j = 0; j < v.length; j++) {
            s += w[j] * v[j];
        }
        return s;
    }

    /** Rank worst first, ties by index: {@code SUMPRODUCT((v<v_j)*1)+SUMPRODUCT((v_before=v_j)*1)+1}. */
    static int[] rank(double[] v) {
        int[] order = VarMath.order(v);
        int[] r = new int[v.length];
        for (int i = 0; i < order.length; i++) {
            r[order[i]] = i + 1;
        }
        return r;
    }

    /** The headline VaR and ES of the lens chosen ({@code Horizon}, {@code TenDayMethod}). */
    double headlineVar() {
        return "1D".equals(set.horizon()) ? var1 : "SUM".equals(set.method()) ? var10 : var1 * SQRT10;
    }

    double headlineEs() {
        return "1D".equals(set.horizon()) ? es1 : "SUM".equals(set.method()) ? es10 : es1 * SQRT10;
    }

    /** The VaR scenario's index (rank k), or -1. */
    int varScenario() {
        for (int j = 0; j < n; j++) {
            if (rank[j] == k) {
                return j;
            }
        }
        return -1;
    }

    /** Σ standalone VaR (positions and Rest of scope) − VaR. */
    double diversification() {
        double s = restFig == null ? 0 : restFig.standaloneVar();
        for (Fig f : figs) {
            s += f.standaloneVar();
        }
        return s - var1;
    }

    /** Every confidence's {k, VaR 1-day, VaR √10, VaR 10-day sum}. */
    List<double[]> everyConfidence() {
        List<double[]> out = new ArrayList<>();
        for (double c : set.confidences()) {
            double v = var(portfolio, c);
            out.add(new double[] {c, kth(n, c), v, v * SQRT10, n10 == 0 ? Double.NaN : var(t10, c)});
        }
        return out;
    }

    /** Every ES confidence's {k, ES 1-day, ES √10, ES 10-day sum}. */
    List<double[]> everyEsConfidence() {
        List<double[]> out = new ArrayList<>();
        for (double c : set.esConfidences()) {
            double e = es(portfolio, c);
            out.add(new double[] {c, kth(n, c), e, e * SQRT10, n10 == 0 ? Double.NaN : es(t10, c)});
        }
        return out;
    }
}
