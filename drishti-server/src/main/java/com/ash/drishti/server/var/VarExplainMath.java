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

import com.ash.drishti.compute.var.VarCalc;
import com.ash.drishti.compute.var.VarMath;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The arithmetic of "why VaR moved" (VAR_GUIDE.md section 3.11; LENSES.md section 5.4.1, RO1), with no engine: a sequence of
 * <b>states</b>, each a full VaR computation, from the earlier date's VaR (S0) to the later date's (the last state). Each step is the
 * difference of two consecutive states, so the steps add up to V(to) − V(from) exactly (a telescoping sum).
 *
 * <p>Every state is a vector over the <b>union</b> of the two dates' scenario days, NaN on the days outside the state's own set: the
 * earlier date's days (D0) until the oldest day is dropped, the days common to both (D0 ∩ D1) for the market step, the later date's
 * days (D1) once the newest day is added. A state's VaR is the engine's VaR ({@link VarCalc}) of its values on its days, in date order.
 *
 * <p>Only the <b>churn</b> is summed: the inputs are the two dates' scope totals (already held by the engine) and the sums of the trades
 * that moved between them (out, in, moved by the hierarchy, amended), so the cost is bounded by the churn, not the book.
 */
final class VarExplainMath {

    private VarExplainMath() {}

    /** The causes of the configured order ({@code drishti.var.explain.order}). */
    enum Cause {
        RESTATEMENT("restatement"), HIERARCHY("hierarchy"), TRADES_OUT("trades-out"), WINDOW("window"), MARKET("market"), TRADES_IN("trades-in");

        final String word;

        Cause(String word) {
            this.word = word;
        }

        static Cause of(String w) {
            String t = w == null ? "" : w.strip().toLowerCase(Locale.ROOT).replace('_', '-');
            for (Cause c : values()) {
                if (c.word.equals(t)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("drishti.var.explain.order: " + w + " is not a cause; the causes are restatement, hierarchy, "
                    + "trades-out, window, market, trades-in");
        }
    }

    /** One step from a state to the next. */
    enum Op {
        RESTATE("restatement", "Restatement", "the earlier date's figures recomputed now: its data corrected after the official run"),
        HIER("hierarchy", "Hierarchy and rules", "the scope's membership by the later date's hierarchy and rules, no trade changing"),
        OUT("trades-out", "Trades out", "trades in the scope on the earlier date with no vector on the later one (matured, closed) or cancelled"),
        AMEND_OUT("amendments-out", "Amendments: old terms out", "amended trades' vectors before the amendment taken out"),
        DROP("window-drop", "Oldest scenario dropped", "the window rolled: the scenario days of the earlier set that are not in the later one left"),
        MARKET("market", "Market", "the same trades revalued on the later date over the same scenario days: market levels, time decay, sensitivities"),
        ADD("window-add", "Newest scenario added", "the window rolled: the later set's new scenario days entered"),
        SET_CHANGE("scenario-set", "Scenario set and market", "the scenario set was replaced, not rolled: the window and the market in one step"),
        IN("trades-in", "Trades in", "trades in the scope on the later date with no vector on the earlier one: new trades"),
        AMEND_IN("amendments-in", "Amendments: new terms in", "amended trades' vectors after the amendment put back");

        final String key;
        final String label;
        final String reads;

        Op(String key, String label, String reads) {
            this.key = key;
            this.label = label;
            this.reads = reads;
        }

        /** True for the steps valued with the earlier date's vectors. */
        boolean before() {
            return this == RESTATE || this == OUT || this == AMEND_OUT || this == DROP;
        }
    }

    /** The causes of an order's words. */
    static List<Cause> causes(List<String> words) {
        List<Cause> out = new ArrayList<>();
        for (String w : words) {
            out.add(Cause.of(w));
        }
        return out;
    }

    /**
     * The steps of an order, checked: every cause once; restatement and trades out before the market (they have only the earlier date's
     * vectors), trades in after it (only the later date's), the window next to the market (its oldest days leave just before the market
     * step and its newest enter just after). Amendments (when marked) follow trades out and trades in; a replaced scenario set makes the
     * window and the market one step.
     */
    static List<Op> plan(List<Cause> order, boolean amendments, boolean setChange) {
        for (Cause c : Cause.values()) {
            long n = order.stream().filter(x -> x == c).count();
            if (n != 1) {
                throw new IllegalArgumentException("drishti.var.explain.order names each cause once: " + c.word + " is named " + n
                        + " times (the causes: restatement, hierarchy, trades-out, window, market, trades-in)");
            }
        }
        int market = order.indexOf(Cause.MARKET);
        if (order.indexOf(Cause.RESTATEMENT) > market || order.indexOf(Cause.TRADES_OUT) > market) {
            throw new IllegalArgumentException("drishti.var.explain.order: restatement and trades-out come before market (they have only the "
                    + "earlier date's vectors)");
        }
        if (order.indexOf(Cause.TRADES_IN) < market) {
            throw new IllegalArgumentException("drishti.var.explain.order: trades-in comes after market (new trades have only the later date's vectors)");
        }
        if (Math.abs(order.indexOf(Cause.WINDOW) - market) != 1) {
            throw new IllegalArgumentException("drishti.var.explain.order: window is next to market (the oldest days leave just before the "
                    + "market step, the newest enter just after)");
        }
        List<Op> ops = new ArrayList<>();
        boolean rolled = false;
        for (Cause c : order) {
            switch (c) {
                case RESTATEMENT -> ops.add(Op.RESTATE);
                case HIERARCHY -> ops.add(Op.HIER);
                case TRADES_OUT -> {
                    ops.add(Op.OUT);
                    if (amendments) {
                        ops.add(Op.AMEND_OUT);
                    }
                }
                case WINDOW, MARKET -> {
                    if (!rolled) {
                        ops.addAll(setChange ? List.of(Op.SET_CHANGE) : List.of(Op.DROP, Op.MARKET, Op.ADD));
                        rolled = true;
                    }
                }
                case TRADES_IN -> {
                    ops.add(Op.IN);
                    if (amendments) {
                        ops.add(Op.AMEND_IN);
                    }
                }
                default -> throw new IllegalStateException(c.word);
            }
        }
        return ops;
    }

    /**
     * What the states are made of, each on its own date's scenario days (null: zero). {@code captured} is the earlier date's official
     * vector (null: none, S0 is the earlier date's live figure); {@code fromTotal} the earlier date's scope now, {@code toTotal} the
     * later date's; the rest the churn's sums.
     */
    record Inputs(List<LocalDate> fromDays, List<LocalDate> toDays, double[] captured, double[] fromTotal, double[] toTotal,
            double[] movedInFrom, double[] movedOutFrom, double[] outFrom, double[] amendedFrom, double[] inTo, double[] amendedTo,
            double[] movedInTo, double[] movedOutTo) {}

    /** The scenario days of both dates: their union in date order, and where each date's days sit in it. */
    record Days(List<LocalDate> union, int[] fromAt, int[] toAt, boolean[] inFrom, boolean[] inTo) {

        static Days of(List<LocalDate> from, List<LocalDate> to) {
            TreeSet<LocalDate> all = new TreeSet<>(from);
            if (all.size() != from.size() || new TreeSet<>(to).size() != to.size()) {
                throw new IllegalStateException("a scenario day repeats within a date's set: the explanation needs each day once");
            }
            all.addAll(to);
            List<LocalDate> u = new ArrayList<>(all);
            int[] fa = new int[from.size()];
            int[] ta = new int[to.size()];
            boolean[] inF = new boolean[u.size()];
            boolean[] inT = new boolean[u.size()];
            for (int i = 0; i < from.size(); i++) {
                fa[i] = java.util.Collections.binarySearch(u, from.get(i));
                inF[fa[i]] = true;
            }
            for (int i = 0; i < to.size(); i++) {
                ta[i] = java.util.Collections.binarySearch(u, to.get(i));
                inT[ta[i]] = true;
            }
            return new Days(List.copyOf(u), fa, ta, inF, inT);
        }

        int size() {
            return union.size();
        }

        boolean common(int u) {
            return inFrom[u] && inTo[u];
        }

        int commonCount() {
            int n = 0;
            for (int u = 0; u < union.size(); u++) {
                n += common(u) ? 1 : 0;
            }
            return n;
        }

        /** A vector of the earlier date's days on the union (NaN elsewhere; zeros when null). */
        double[] liftFrom(double[] v) {
            return lift(v, fromAt);
        }

        double[] liftTo(double[] v) {
            return lift(v, toAt);
        }

        private double[] lift(double[] v, int[] at) {
            double[] out = new double[union.size()];
            java.util.Arrays.fill(out, Double.NaN);
            for (int i = 0; i < at.length; i++) {
                out[at[i]] = v == null ? 0 : v[i];
            }
            return out;
        }
    }

    /** Which days a state is valued on. */
    enum Domain { FROM, COMMON, TO }

    /**
     * One state: its vector on the union (NaN outside its days), the days and the vectors it is valued with, and its figures: VaR, the
     * VaR scenario's day ({@code varDay}, an index in the union), k and the count of days.
     */
    record State(int index, Op op, double[] v, Domain domain, boolean today, double var, int varDay, int k, int n, VarCalc.Measures measures,
            int[] at) {

        String id() {
            return "S" + index;
        }

        /** The state's values on its own days, in date order. */
        double[] compact() {
            double[] c = new double[at.length];
            for (int i = 0; i < at.length; i++) {
                c[i] = v[at[i]];
            }
            return c;
        }

        /** A vector on the union restricted to this state's days, in date order (NaN read as zero). */
        double[] compact(double[] onUnion) {
            double[] c = new double[at.length];
            for (int i = 0; i < at.length; i++) {
                double x = onUnion[at[i]];
                c[i] = Double.isNaN(x) ? 0 : x;
            }
            return c;
        }

        /** The 1-based rank of a union day in this state (1: the worst), 0 when the day is not in it. */
        int rank(int u) {
            for (int r = 0; r < measures.order().length; r++) {
                if (at[measures.order()[r]] == u) {
                    return r + 1;
                }
            }
            return 0;
        }
    }

    /** One step: the difference of two consecutive states. */
    record Step(int index, Op op, double effect, State before, State after) {}

    /** The states and their steps. */
    record Result(Days days, List<Op> ops, List<State> states, List<Step> steps) {

        State first() {
            return states.get(0);
        }

        State last() {
            return states.get(states.size() - 1);
        }

        double change() {
            return last().var() - first().var();
        }

        double sumOfSteps() {
            double s = 0;
            for (Step st : steps) {
                s += st.effect();
            }
            return s;
        }
    }

    /** The states of {@code ops} over the inputs, each valued by {@code calc}. */
    static Result run(Inputs in, List<Op> ops, VarCalc calc) {
        Days d = Days.of(in.fromDays(), in.toDays());
        int n = d.size();
        double[] fromTotal = d.liftFrom(in.fromTotal());
        double[] captured = in.captured() == null ? fromTotal.clone() : d.liftFrom(in.captured());
        double[] restated = new double[n];
        for (int u = 0; u < n; u++) {
            restated[u] = captured[u] - fromTotal[u];
        }
        double[] movedFrom = diff(d.liftFrom(in.movedInFrom()), d.liftFrom(in.movedOutFrom()));
        double[] outFrom = d.liftFrom(in.outFrom());
        double[] amendedFrom = d.liftFrom(in.amendedFrom());
        double[] toTotal = d.liftTo(in.toTotal());
        double[] inTo = d.liftTo(in.inTo());
        double[] amendedTo = d.liftTo(in.amendedTo());
        double[] movedTo = diff(d.liftTo(in.movedInTo()), d.liftTo(in.movedOutTo()));

        List<State> states = new ArrayList<>();
        double[] v = captured.clone();
        Domain dom = Domain.FROM;
        boolean today = false;
        states.add(state(0, null, v, dom, today, d, calc));
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            double[] next = v.clone();
            switch (op) {
                case RESTATE -> sub(next, restated);
                case HIER -> add(next, today ? movedTo : movedFrom);
                case OUT -> sub(next, outFrom);
                case AMEND_OUT -> sub(next, amendedFrom);
                case DROP -> {
                    for (int u = 0; u < n; u++) {
                        if (!d.common(u)) {
                            next[u] = Double.NaN;
                        }
                    }
                    dom = Domain.COMMON;
                }
                case MARKET, ADD, SET_CHANGE -> {
                    today = true;
                    dom = op == Op.MARKET ? Domain.COMMON : Domain.TO;
                    double[] base = toTotal.clone();               // the later date's scope less what the steps after this one put in
                    for (Op later : ops.subList(i + 1, ops.size())) {
                        switch (later) {
                            case IN -> sub(base, inTo);
                            case AMEND_IN -> sub(base, amendedTo);
                            case HIER -> sub(base, movedTo);
                            default -> { }
                        }
                    }
                    for (int u = 0; u < n; u++) {
                        next[u] = dom == Domain.COMMON && !d.common(u) || !d.inTo[u] ? Double.NaN : base[u];
                    }
                }
                case IN -> add(next, inTo);
                case AMEND_IN -> add(next, amendedTo);
                default -> throw new IllegalStateException(op.key);
            }
            v = next;
            states.add(state(i + 1, op, v, dom, today, d, calc));
        }
        List<Step> steps = new ArrayList<>();
        for (int i = 1; i < states.size(); i++) {
            State a = states.get(i - 1);
            State b = states.get(i);
            steps.add(new Step(i, b.op(), b.var() - a.var(), a, b));
        }
        return new Result(d, List.copyOf(ops), List.copyOf(states), List.copyOf(steps));
    }

    private static State state(int index, Op op, double[] v, Domain dom, boolean today, Days d, VarCalc calc) {
        int[] at = switch (dom) {
            case FROM -> d.fromAt();
            case TO -> d.toAt();
            case COMMON -> commonAt(d);
        };
        double[] c = new double[at.length];
        for (int i = 0; i < at.length; i++) {
            c[i] = v[at[i]];
        }
        VarCalc.Measures m = calc.measures(c);
        return new State(index, op, v, dom, today, m.var(), m.varScenario() < 0 ? -1 : at[m.varScenario()], m.k(), c.length, m, at);
    }

    private static int[] commonAt(Days d) {
        int[] out = new int[d.commonCount()];
        int i = 0;
        for (int u = 0; u < d.size(); u++) {
            if (d.common(u)) {
                out[i++] = u;
            }
        }
        return out;
    }

    /** The VaR of a state less a part (a vector on the union, NaN read as zero): the state's incremental VaR of that part is V − this. */
    static double varWithout(State s, double[] part, VarCalc calc) {
        double[] c = s.compact();
        double[] p = s.compact(part);
        for (int i = 0; i < c.length; i++) {
            c[i] -= p[i];
        }
        return calc.var(c);
    }

    /** A part's component VaR in a state (its share of the state's VaR; the parts of a state add up to its VaR). */
    static double component(State s, double[] part) {
        return VarMath.component(s.compact(part), s.measures().tail(), s.measures().scale());
    }

    private static double[] diff(double[] a, double[] b) {
        double[] out = a.clone();
        sub(out, b);
        return out;
    }

    private static void add(double[] acc, double[] v) {
        for (int u = 0; u < acc.length; u++) {
            if (!Double.isNaN(v[u])) {
                acc[u] += v[u];
            }
        }
    }

    private static void sub(double[] acc, double[] v) {
        for (int u = 0; u < acc.length; u++) {
            if (!Double.isNaN(v[u])) {
                acc[u] -= v[u];
            }
        }
    }
}
