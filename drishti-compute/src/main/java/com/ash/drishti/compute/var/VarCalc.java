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
package com.ash.drishti.compute.var;

import java.util.ArrayList;
import java.util.List;

/**
 * The figures a screen shows for one scope, computed from its vector and its parts' (VAR_ENGINE.md section 7): the headline measures,
 * each part's standalone, component, incremental and marginal VaR, a histogram with its VaR and ES marks, the worst scenarios. All
 * of it is small (a few hundred numbers) whatever the scope's size: the screens are sent summaries, never vectors per part.
 *
 * @param confidence VaR's (0.99)
 * @param esConfidence ES's (0.975)
 * @param rule the rank rule
 * @param window component VaR's smoothing window (5 scenarios around the VaR rank)
 * @param scaleToVar components scaled to add up to the VaR exactly (else to the smoothed VaR)
 */
public record VarCalc(double confidence, double esConfidence, VarMath.RankRule rule, int window, boolean scaleToVar) {

    public static VarCalc defaults() {
        return new VarCalc(0.99, 0.975, VarMath.RankRule.KTH_WORST, 5, true);
    }

    /**
     * A scope's measures.
     *
     * @param var VaR at the confidence
     * @param es ES at its confidence
     * @param order scenario indices worst first
     * @param tail the scenarios components average over
     * @param smoothed the VaR as the components see it
     * @param scale the components' scale
     * @param varScenario the scenario that sets the VaR (rank k)
     * @param k the VaR rank
     * @param esCount the scenarios ES averages
     */
    public record Measures(double var, double es, int[] order, int[] tail, double smoothed, double scale, int varScenario, int k, int esCount) {}

    /** One part of a scope (a child node, a position, a trade). */
    public record Part(String key, String label, String kind, double[] vector, double size) {}

    /**
     * A part's contribution.
     *
     * @param standaloneVar its own VaR
     * @param standaloneEs its own ES
     * @param componentVar its share of the scope's VaR (the parts add up to it)
     * @param componentEs its share of the scope's ES (the parts add up to it)
     * @param pct component VaR over the scope's VaR
     * @param incrementalVar the scope's VaR minus the scope's VaR without the part
     * @param marginalPer1m component VaR per million of the part's size (market value), NaN without a size
     */
    public record Contribution(String key, String label, String kind, double standaloneVar, double standaloneEs, double componentVar,
            double componentEs, double pct, double incrementalVar, double marginalPer1m) {}

    /** A histogram of the scenario P&amp;Ls: bin {@code i} counts values in {@code [edges[i], edges[i+1])} (the last bin closed). */
    public record Histogram(double[] edges, int[] counts) {}

    public Measures measures(double[] v) {
        int[] order = VarMath.order(v);
        double var = VarMath.var(v, order, confidence, rule);
        double es = VarMath.es(v, order, esConfidence);
        int[] tail = VarMath.tail(order, confidence, window);
        double smoothed = VarMath.smoothedVar(v, tail);
        double scale = scaleToVar ? VarMath.componentScale(var, smoothed) : 1.0;
        int k = VarMath.rank(v.length, confidence);
        return new Measures(var, es, order, tail, smoothed, scale, v.length == 0 ? -1 : order[k - 1], k, VarMath.rank(v.length, esConfidence));
    }

    /** Each part's figures in the scope whose vector is {@code total}. Parts that do not add up to the total are fine (a selection). */
    public List<Contribution> contributions(double[] total, Measures m, List<Part> parts) {
        List<Contribution> out = new ArrayList<>(parts.size());
        for (Part p : parts) {
            Measures own = measures(p.vector());
            double comp = VarMath.component(p.vector(), m.tail(), m.scale());
            double compEs = VarMath.componentEs(p.vector(), m.order(), esConfidence);
            double without = VarMath.var(VarMath.minus(total, p.vector()), confidence, rule);
            double marginal = Double.isNaN(p.size()) || p.size() == 0 ? Double.NaN : comp / Math.abs(p.size()) * 1_000_000;
            out.add(new Contribution(p.key(), p.label(), p.kind(), own.var(), own.es(), comp, compEs, m.var() == 0 ? 0 : comp / m.var(),
                    m.var() - without, marginal));
        }
        return out;
    }

    /** A histogram of {@code v} in {@code bins} equal bins between its smallest and largest value. */
    public static Histogram histogram(double[] v, int bins) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double x : v) {
            if (!Double.isNaN(x)) {
                lo = Math.min(lo, x);
                hi = Math.max(hi, x);
            }
        }
        if (lo > hi) {
            return new Histogram(new double[] {0, 0}, new int[1]);
        }
        if (hi == lo) {
            hi = lo + 1;
        }
        double[] edges = new double[bins + 1];
        for (int i = 0; i <= bins; i++) {
            edges[i] = lo + (hi - lo) * i / bins;
        }
        int[] counts = new int[bins];
        for (double x : v) {
            if (!Double.isNaN(x)) {
                counts[Math.min(bins - 1, (int) ((x - lo) / (hi - lo) * bins))]++;
            }
        }
        return new Histogram(edges, counts);
    }
}
