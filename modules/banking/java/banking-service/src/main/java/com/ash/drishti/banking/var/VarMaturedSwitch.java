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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The explorer's matured-items switch in words (VAR_GUIDE.md, "Matured items"; the approved mock-up var-matured A): the block an answer
 * carries when it is asked with {@code matured=true}. Off (the default) the answers are the official ones, unchanged; on, the figure of
 * the scope with its matured records added and the label every panel and export shows ({@code includes 14 matured positions}). The
 * official record, captures and limit use never follow it: limit use stays on the official figure.
 */
final class VarMaturedSwitch {

    /** Said with every figure that includes matured items. */
    static final String NOT_OFFICIAL = "Not the official figure: the official record, captures and limit use leave matured items out.";

    private VarMaturedSwitch() {}

    /** The grain the date's inputs came in, as the switch names it: positions, trades, or items when both came. */
    static String grainWord(VarIntake.Summary sum) {
        java.util.Set<String> grains = new java.util.TreeSet<>();
        if (sum != null && sum.inputs() != null) {
            sum.inputs().values().forEach(d -> grains.add(String.valueOf(d.get("grain"))));
        }
        return grains.size() == 1 && grains.contains("position") ? "positions" : grains.size() == 1 && grains.contains("trade") ? "trades" : "items";
    }

    /** The label of a figure with matured items: {@code includes 14 matured positions}, {@code ... and 3 matured trades}. */
    static String label(int positions, int trades) {
        String p = positions == 0 ? "" : positions + " matured position" + (positions == 1 ? "" : "s");
        String t = trades == 0 ? "" : trades + " matured trade" + (trades == 1 ? "" : "s");
        return "includes " + (p.isEmpty() ? t.isEmpty() ? "no matured items" : t : t.isEmpty() ? p : p + " and " + t);
    }

    /** The answer's {@code matured} block: the figure with matured items beside the official one, the label, or why none are added. */
    static Map<String, Object> block(VarService.Scoped official, VarService.WithMatured w, String grain) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("included", true);
        m.put("grain", grain);
        m.put("positions", w.positions());
        m.put("trades", w.trades());
        m.put("items", w.items());
        m.put("label", label(w.positions(), w.trades()));
        m.put("var", VarViews.round(w.scoped().measures().var()));
        m.put("es", VarViews.round(w.scoped().measures().es()));
        m.put("officialVar", VarViews.round(official.measures().var()));
        m.put("officialEs", VarViews.round(official.measures().es()));
        m.put("difference", VarViews.round(w.scoped().measures().var()) - VarViews.round(official.measures().var()));
        if (w.why() != null) {
            m.put("why", w.why());
        }
        m.put("note", NOT_OFFICIAL);
        return m;
    }

    /** A tree row's cell: the node's VaR and ES with its matured items, and what they add; null when none are in the node. */
    static Map<String, Object> cell(VarService.Scoped official, VarService.WithMatured w) {
        if (w.items() == 0) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("var", VarViews.round(w.scoped().measures().var()));
        m.put("es", VarViews.round(w.scoped().measures().es()));
        m.put("add", VarViews.round(w.scoped().measures().var()) - VarViews.round(official.measures().var()));
        m.put("positions", w.positions());
        m.put("trades", w.trades());
        m.put("label", label(w.positions(), w.trades()));
        return m;
    }
}
