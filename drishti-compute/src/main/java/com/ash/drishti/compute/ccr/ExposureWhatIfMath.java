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
package com.ash.drishti.compute.ccr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The exact pre-deal what-if (EXPOSURE_GUIDE.md section 18.7, method {@code paths}) over plain arrays, by {@link ExposureMath}'s rules: each
 * candidate's MtM on every path of the date's run at every grid date is added to its netting set's value V, the set's collateral and
 * exposure are computed again (the CSA's terms, today's holdings, the look-back points, the asked collateral model), and the scope's
 * exposure becomes {@code E_scope - E_set + E'_set} path by path; a candidate without a netting set is a netting set of its own,
 * uncollateralised. The current exposure moves the same way ({@code CE_scope - CE_set + CE'_set}, the set's MtM today plus the candidates'),
 * the horizon is the longest remaining life (at most a year). Incremental = with - without, for every figure and every primary date.
 * Deterministic: no random numbers. Each candidate is also measured alone (its peak PFE uncollateralised) and added alone (its incremental
 * peak PFE).
 *
 * <p>Computed in the Java exposure engine since 2.9.12 (the Python engine {@code exposure-whatif} it replaced gave the same answers: the parity
 * test replays its recorded answers on a book of four netting sets under the three collateral models and both rank rules).
 */
public final class ExposureWhatIfMath {

    /** The scope before: its exposure and negative exposure ({@code G_primary x P}), current exposure and longest remaining life in years. */
    public record Scope(double[] e, double[] n, double ce, double lifeYears) {}

    /**
     * A netting set of the scope (seen whole) that candidates join: its value on every path at every grid date ({@code G x P}), its exposure
     * and negative exposure as the scope summed them ({@code G_primary x P}), its CSA, its MtM today and its current exposure.
     */
    public record NettingSet(String id, double[] v, double[] e, double[] n, CsaTerms csa, double v0, double ce) {}

    /**
     * A candidate: its id and label, the netting set it joins (empty: a netting set of its own), its MtM on every path at every grid date
     * ({@code G x P}), its MtM today ({@code NaN}: the paths' mean at the first grid date) and its remaining life in years.
     */
    public record Candidate(String id, String label, String set, double[] mtm, double mtm0, double lifeYears) {}

    /** A candidate's figures: its peak PFE alone (uncollateralised) and the scope's peak PFE change with it added alone. */
    public record Item(String id, String label, String set, double peakPfeAlone, double incrementalPeakPfe) {}

    /** The answer: the scope's statistics before and after (at the one confidence asked) and each candidate's figures. */
    public record Result(ExposureMath.Stats before, ExposureMath.Stats after, List<Item> items) {}

    private ExposureWhatIfMath() {}

    /**
     * The scope with the candidates in.
     *
     * @param sets the netting sets the candidates may join, by id
     * @param times the primary points' times in years
     * @throws IllegalArgumentException a candidate's paths of another length, or a netting set the scope does not cover whole, in words
     */
    public static Result of(Scope scope, Map<String, NettingSet> sets, List<Candidate> candidates, ExposureGrid grid, int p, double[] times, double q,
            ExposureMath.Model model, ExposureMath.Rule rule, String scopeName) {
        int len = grid.size() * p;
        if (p < 1 || scope.e().length != times.length * p || grid.primaryCount() != times.length) {
            throw new IllegalArgumentException("the scope's exposure matrix is not " + times.length + " primary dates x " + p + " paths");
        }
        List<Candidate> prepared = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.mtm().length != len) {
                throw new IllegalArgumentException("the candidate " + c.label() + "'s paths are not of the run: " + c.mtm().length + " values, the run has "
                        + grid.size() + " grid dates x " + p + " paths = " + len);
            }
            String ns = c.set() == null ? "" : c.set().trim();
            if (!ns.isEmpty() && !sets.containsKey(ns)) {
                throw new IllegalArgumentException("the candidate " + c.label() + " joins the netting set " + ns + ", which the scope " + scopeName
                        + " does not cover whole (the sets it may join: " + (sets.isEmpty() ? "none" : String.join(",", sets.keySet()))
                        + "); leave nettingSet empty for a netting set of its own");
            }
            double mtm0 = c.mtm0();
            if (Double.isNaN(mtm0)) {
                double s = 0;
                for (int j = 0; j < p; j++) {
                    s += c.mtm()[j];
                }
                mtm0 = s / p;
            }
            prepared.add(new Candidate(c.id(), c.label(), ns, c.mtm(), mtm0, c.lifeYears()));
        }
        double[] qs = {q};
        ExposureMath.Stats before = ExposureMath.statistics(scope.e(), scope.n(), null, times, p, Math.min(1.0, scope.lifeYears()), qs, scope.ce(), rule);
        ExposureMath.Stats after = apply(scope, sets, prepared, grid, p, times, qs, model, rule);
        List<Item> items = new ArrayList<>();
        for (Candidate c : prepared) {
            ExposureMath.Stats alone = apply(scope, sets, List.of(c), grid, p, times, qs, model, rule);
            items.add(new Item(c.id(), c.label(), c.set(), peakAlone(c.mtm(), grid, p, q, rule), alone.peakPfe[0] - before.peakPfe[0]));
        }
        return new Result(before, after, items);
    }

    /** The scope's statistics with the candidates in. */
    private static ExposureMath.Stats apply(Scope scope, Map<String, NettingSet> sets, List<Candidate> cands, ExposureGrid grid, int p, double[] times,
            double[] qs, ExposureMath.Model model, ExposureMath.Rule rule) {
        double[] e = scope.e().clone();
        double[] n = scope.n().clone();
        double ce = scope.ce();
        double life = scope.lifeYears();
        Map<String, List<Candidate>> bySet = new LinkedHashMap<>();
        for (Candidate c : cands) {
            bySet.computeIfAbsent(c.set(), x -> new ArrayList<>()).add(c);
            life = Math.max(life, c.lifeYears());
        }
        for (Map.Entry<String, List<Candidate>> en : bySet.entrySet()) {
            if (en.getKey().isEmpty()) {                                   // each a netting set of its own, uncollateralised
                for (Candidate c : en.getValue()) {
                    ExposureMath.addInto(e, ExposureMath.positive(c.mtm(), grid, p));
                    ExposureMath.addInto(n, ExposureMath.negative(c.mtm(), grid, p));
                    ce += Math.max(c.mtm0(), 0);
                }
                continue;
            }
            NettingSet s = sets.get(en.getKey());
            double[] v = s.v().clone();
            double v0 = s.v0();
            for (Candidate c : en.getValue()) {
                ExposureMath.addInto(v, c.mtm());
                v0 += c.mtm0();
            }
            double[][] enc = ExposureMath.exposure(v, grid, p, s.csa(), model);
            for (int i = 0; i < e.length; i++) {
                e[i] = e[i] - s.e()[i] + enc[0][i];
                n[i] = n[i] - s.n()[i] + enc[1][i];
            }
            ce += ExposureMath.currentExposure(v0, s.csa(), model) - s.ce();
        }
        return ExposureMath.statistics(e, n, null, times, p, Math.min(1.0, life), qs, ce, rule);
    }

    /** A candidate's peak PFE as if it had no netting set (uncollateralised). */
    static double peakAlone(double[] m, ExposureGrid grid, int p, double q, ExposureMath.Rule rule) {
        double[] pos = ExposureMath.positive(m, grid, p);
        double best = 0;
        for (int i = 0; i < grid.primaryCount(); i++) {
            best = Math.max(best, ExposureMath.quantileSorted(ExposureMath.sortedRow(pos, i, p), q, rule));
        }
        return best;
    }
}
