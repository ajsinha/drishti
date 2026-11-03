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
 * The pre-deal what-if (VAR_GUIDE.md section 8.5): a scope's P&amp;L vector, hypothetical trades added on top of it (each a vector over the
 * same scenarios, already scaled by its factor), and the figures before and after: VaR, ES and the stressed window (the largest VaR over
 * every run of {@code stressWindow} consecutive scenario days), each trade's standalone VaR and ES, its incremental VaR in the order listed
 * and alone (on the scope before), and its component VaR and ES in the scope after. Pure: the same rules as every other VaR answer
 * ({@link VarCalc}: the confidence, the rank rule, the component window and scale).
 */
public final class VarWhatIfMath {

    /** The stressed window's length when none is given: 250 scenario days, about one year. */
    public static final int STRESS_WINDOW = 250;

    private VarWhatIfMath() {}

    /** One hypothetical trade: its label, its factor, and its vector already scaled by the factor. */
    public record Item(String label, double factor, double[] pnl) {}

    /** One trade's figures. */
    public record ItemResult(String label, double factor, double standaloneVar, double standaloneEs, double incrementalVar,
            double incrementalAlone, double componentVar, double componentEs) {}

    /**
     * The answer.
     *
     * @param stressedFrom the first scenario index of the stressed window before (its run of days); -1 without one
     */
    public record Result(double beforeVar, double afterVar, double beforeEs, double afterEs, double stressedBefore, double stressedAfter,
            int stressedFrom, int stressWindow, double[] after, List<ItemResult> items) {

        public double changeVar() {
            return afterVar - beforeVar;
        }

        public double changeEs() {
            return afterEs - beforeEs;
        }
    }

    /**
     * The what-if of {@code items} on the scope whose vector is {@code before}.
     *
     * @param stressWindow the stressed window's scenario days (0: none; longer than the vector: the whole vector)
     * @throws IllegalArgumentException when an item's vector is not as long as the scope's
     */
    public static Result of(double[] before, List<Item> items, VarCalc calc, int stressWindow) {
        int n = before.length;
        for (Item it : items) {
            if (it.pnl().length != n) {
                throw new IllegalArgumentException("what-if item " + it.label() + " has " + it.pnl().length + " scenario P&Ls; the scope has " + n);
            }
        }
        VarCalc.Measures mb = calc.measures(before);
        double[] running = before.clone();
        double[] incremental = new double[items.size()];
        double[] alone = new double[items.size()];
        for (int i = 0; i < items.size(); i++) {
            double[] v = items.get(i).pnl();
            double prev = calc.var(running);
            VarMath.addInto(running, v);
            incremental[i] = calc.var(running) - prev;
            alone[i] = calc.var(plus(before, v)) - mb.var();
        }
        VarCalc.Measures ma = calc.measures(running);
        List<ItemResult> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            VarCalc.Measures own = calc.measures(it.pnl());
            out.add(new ItemResult(it.label(), it.factor(), own.var(), own.es(), incremental[i], alone[i],
                    VarMath.component(it.pnl(), ma.tail(), ma.scale()), VarMath.componentEs(it.pnl(), ma.order(), calc.esConfidence())));
        }
        double sb = Double.NaN;
        double sa = Double.NaN;
        int from = -1;
        if (stressWindow > 0 && n > 0) {
            int w = Math.min(stressWindow, n);
            double[] rb = StressWindows.rolling(before, w, calc.confidence(), calc.rule());
            double[] ra = StressWindows.rolling(running, w, calc.confidence(), calc.rule());
            from = argmax(rb);
            sb = rb[from];
            sa = ra[argmax(ra)];
        }
        return new Result(mb.var(), ma.var(), mb.es(), ma.es(), sb, sa, from, stressWindow, running, out);
    }

    /** The first index of the largest value (the earlier run of days on a tie). */
    private static int argmax(double[] v) {
        int at = 0;
        for (int i = 1; i < v.length; i++) {
            if (v[i] > v[at]) {
                at = i;
            }
        }
        return at;
    }

    private static double[] plus(double[] a, double[] b) {
        double[] out = a.clone();
        VarMath.addInto(out, b);
        return out;
    }
}
