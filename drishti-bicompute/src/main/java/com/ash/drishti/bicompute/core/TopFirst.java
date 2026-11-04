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
package com.ash.drishti.bicompute.core;

import com.ash.drishti.bicompute.core.AggregationPlan.FunctionCall;
import com.ash.drishti.bicompute.functions.Sketches;
import com.ash.drishti.bicompute.functions.SpaceSaving;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A level's top N chosen before the level is read (PIVOT_ENGINE.md sections 9.5 and 11; phase P3): when the view asks ≈ and a level shows the
 * top N by a count of rows, one Space-Saving summary of the level's dimension (10·n counters, {@code drishti.pivot.sketches.top-capacity-factor})
 * is made for the parent's cell, the members it ranks first are the level, and the rest is one Others; only those members' cells are read.
 * Membership is ≈ with a stated bound (every count over by at most the summary's floor; a member whose share of the rows is over 1/(10·n)
 * is always listed); the figures shown are exact, each from its own rows. Exact (the default, and on request) reads every member and ranks
 * them exactly, as before.
 *
 * <p>Only where it is honest: a count of rows ({@code count()}, or {@code count(x)} of the level's own field), a plain text field (no date
 * grouping or width bins: the summary's values are the field's text), a provider that evaluates the built-ins (never VaR), never on a
 * sample. Anything else ranks exactly and the answer says so.
 */
final class TopFirst {

    private TopFirst() {}

    /** The groups to evaluate this round: an eligible level replaced by its chosen members and its Others; the rest unchanged. */
    static List<Axis.Group> choose(PivotCore.Computation c, List<Axis.Group> groups, Axis.Group otherTotal) {
        if (!c.scale.approximate() || c.scale.sampled() || groups.isEmpty() || c.view.top().isEmpty()) {
            return groups;
        }
        List<Axis.Group> out = new ArrayList<>();
        for (Axis.Group g : groups) {
            PivotView.Top top = g.dim() == null || g.others() || g.only() != null ? null : c.view.top().get(g.dim());
            String why = top == null ? "" : eligible(c, g.dim(), top);
            if (top == null || !why.isEmpty()) {
                if (top != null) {
                    note(c, g.dim() + ": top " + top.n() + " ranked exactly (" + why + ")");
                }
                out.add(g);
                continue;
            }
            SpaceSaving ss = summary(c, g, otherTotal, top.n());
            if (ss == null || ss.floor() == 0 && ss.top(Integer.MAX_VALUE).size() <= top.n()) {
                out.add(g);                                   // every member fits: nothing to choose, the level is read whole and exactly
                continue;
            }
            List<String> kept = ss.top(top.n()).stream().map(SpaceSaving.Counted::value).toList();
            out.add(new Axis.Group(g.parent(), g.dim(), false, List.of(), kept));
            out.add(new Axis.Group(g.parent(), g.dim(), true, kept));
            String words = String.format(Locale.ROOT, "%s: the top %d by %s chosen by a Space-Saving summary of %d counters over %,d rows "
                    + "(each count over by at most %,d; a member over 1/%d of the rows is always listed); the figures shown are exact; ask "
                    + "exact to rank every member", g.dim(), top.n(), top.by() == null ? "count" : top.by(), ss.capacity(), ss.n(),
                    ss.floor(), ss.capacity());
            c.topChosen.put(g.parent().key() + "\u0001" + g.dim(), words);
            note(c, words);
        }
        return out;
    }

    /** Empty when the level's top N may be chosen by the summary, else why not (in words). */
    static String eligible(PivotCore.Computation c, String dim, PivotView.Top top) {
        Dim d = c.dims.get(dim);
        if (d == null || d.group() != null || d.width() > 0 || !"string".equals(c.types.get(d.field()))) {
            return "a summary ranks a text field's own values; " + dim + " is grouped or not text";
        }
        if (!c.provider.capabilities().builtIns() || "var".equals(c.provider.id())) {
            return "the source's own measures are never approximated";
        }
        FunctionCall f = c.functions.get(c.measureIndex(top.by(), false));
        boolean rows = "count".equals(f.fn().name()) && (f.inputs().isEmpty() || f.inputs().get(0).equals(d.field()));
        if (!rows) {
            return "a summary ranks by a count of rows; " + f.label() + " is not one";
        }
        return "";
    }

    /** The parent's Space-Saving summary of the level's field, from the provider (through the hooks: a rollup, the cluster), or null. */
    static SpaceSaving summary(PivotCore.Computation c, Axis.Group g, Axis.Group otherTotal, int n) {
        String field = c.dims.get(g.dim()).field();
        CellSet parent = new Axis.Group(g.parent(), null, false, List.of()).cross(otherTotal);
        FunctionCall call = new FunctionCall("top_n≈(" + n + ", " + field + ")", "top " + n + " of " + field, new Sketches.Top(n, false), List.of(field));
        AggregationPlan p = c.plan(List.of(parent)).with(List.of(parent), List.of(call));
        Partials parts = c.run(p);
        c.work.queries += parts.stats().queries();
        c.work.statements.addAll(parts.stats().statements());
        for (Partials.SetResult r : parts.sets()) {
            for (Partials.Entry e : r.cells().values()) {
                if (e.states() != null && e.states().length > 0 && e.states()[0] instanceof SpaceSaving s) {
                    return s;
                }
            }
        }
        return null;
    }

    private static void note(PivotCore.Computation c, String words) {
        if (!c.work.notes.contains(words)) {
            c.work.notes.add(words);
        }
    }
}
