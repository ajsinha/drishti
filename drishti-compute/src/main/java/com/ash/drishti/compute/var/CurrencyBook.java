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

import com.ash.drishti.compute.vector.ParallelSums;

/**
 * A business date's book in another reporting currency (VAR_GUIDE.md section 15.10), built from the date's USD book: every trade's
 * vector converted by {@link ScenarioFx#convert} with its USD value today (the {@code PV} column), its {@code PV} at today's rate, the
 * nodes' VaR limits at today's rate, the failed trades counted as before. The trades, positions and nodes are the USD book's, in the same
 * order (the builder sorts them the same way), so a scope resolves to the same members in every currency.
 */
public final class CurrencyBook {

    /**
     * The converted book, and how many trades had no USD value today ({@code PV} empty): their P&amp;L is converted at each scenario's
     * rate only (the move of a value the batch did not state is not there to convert).
     */
    public record Converted(VarBook book, int withoutPv) {}

    private CurrencyBook() {}

    /** Converts {@code usd} into the currency of {@code rep} through {@code target} (a heap or a mapped builder of the same date). */
    public static Converted convert(VarBook usd, ScenarioFx.Rates rep, VarBook.Builder target, ParallelSums sums) {
        int w = usd.width();
        if (rep.scenarios().length != w) {
            throw new IllegalArgumentException(rep.currency() + " has " + rep.scenarios().length + " scenario rates; the date has " + w + " scenarios");
        }
        Columns tc = usd.trades();
        String[] texts = new String[VarBook.TRADE_TEXTS.size()];
        double[] numbers = new double[VarBook.TRADE_NUMBERS.size()];
        int pvAt = VarBook.TRADE_NUMBERS.indexOf("PV");
        double[] v = new double[w];
        double[] out = new double[w];
        int withoutPv = 0;
        for (int o = 0; o < usd.tradeCount(); o++) {
            for (int c = 0; c < texts.length; c++) {
                String col = VarBook.TRADE_TEXTS.get(c);
                texts[c] = tc.has(col) ? tc.text(col, o) : null;
            }
            for (int c = 0; c < numbers.length; c++) {
                String col = VarBook.TRADE_NUMBERS.get(c);
                numbers[c] = tc.has(col) ? tc.number(col, o) : Double.NaN;
            }
            double pv = numbers[pvAt];
            if (Double.isNaN(pv)) {
                withoutPv++;
            }
            usd.store().copy(o, v);
            ScenarioFx.convert(Double.isNaN(pv) ? 0 : pv, v, w, rep, out);
            numbers[pvAt] = Double.isNaN(pv) ? Double.NaN : pv / rep.base();
            target.trade(usd.tradeId(o), out, w, texts, numbers);
        }
        target.failedRows(usd.failed());
        for (VarBook.Node n : usd.nodes().values()) {
            target.node(n.id(), n.hierarchy(), n.level(), n.name(), n.parent(), n.books(), Double.isNaN(n.limit()) ? Double.NaN : n.limit() / rep.base());
        }
        return new Converted(target.build(sums), withoutPv);
    }
}
