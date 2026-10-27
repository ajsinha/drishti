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

import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.compute.vector.Vectors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Per-trade-currency totals of every hierarchy node (VAR_GUIDE.md section 15.11, product owner 2026-10-27): for each currency {@code c}
 * the node's trades are priced in ({@code PV_CURRENCY}: a scenario-PV row's {@code CURRENCY}; USD for a P&amp;L vector), the sum of their
 * <b>native</b> PVs under each scenario {@code PVsum_c(s)} and today {@code PVbase_c}. A node's P&amp;L in any reporting currency {@code X}
 * is then exact and cheap, one pass over a handful of currencies instead of the node's trades:
 *
 * <pre>
 *   P&amp;L_X(s) = Σ_c [ PVsum_c(s) × FX_c→X(s) − PVbase_c × FX_c→X(base) ],   FX_c→X = FX_c / FX_X  (USD per unit, cross through USD)
 * </pre>
 *
 * the same, term by term, as converting each trade ({@link ScenarioFx#convert}) and summing: the conversion is linear in the PVs. Built
 * with the USD book from the rows as they were read (float64), kept in its hot file ({@code CTOT}); any other scope's totals are summed
 * from its trades' rows when asked ({@link #of}).
 *
 * <p>A trade's native PV is its USD value recovered from the row: {@code PV(s) = (pv + P&L_USD(s)) / FX_c(s)}, {@code PV(base) = pv /
 * FX_c(base)} with {@code pv} its USD value today (its {@code PV}; none: 0, its P&amp;L alone). That is the received PV up to float64
 * rounding (about 1e-16 of it). A trade whose currency has no rates is summed in USD (its USD value), which converts the same.
 */
public final class CurrencyTotals {

    /** The trade column naming the currency a trade's PVs came in (VarBook.TRADE_TEXTS). */
    public static final String COLUMN = "PV_CURRENCY";

    /**
     * One scope's sums.
     *
     * @param currencies the trade currencies, sorted
     * @param pv per currency, the native PV sum under each scenario
     * @param base per currency, the native PV sum today
     * @param trades per currency, how many trades
     */
    public record Sums(String[] currencies, double[][] pv, double[] base, int[] trades) {

        /** The P&amp;L in {@code target} under every scenario (see the class); null when a currency lacks rates on the date. */
        public double[] in(ScenarioFx fx, String target) {
            ScenarioFx.Rates x = fx.rates(target).orElse(null);
            if (x == null) {
                return null;
            }
            int w = fx.width();
            double[] out = new double[w];
            for (int i = 0; i < currencies.length; i++) {
                ScenarioFx.Rates c = fx.rates(currencies[i]).orElse(null);
                if (c == null) {
                    return null;
                }
                double today = base[i] * (c.base() / x.base());
                double[] v = pv[i];
                for (int s = 0; s < w; s++) {
                    out[s] += v[s] * (c.scenarios()[s] / x.scenarios()[s]) - today;
                }
            }
            return out;
        }

        /** The native PV sum today of one currency (0 when the scope has no trade in it). */
        public double base(String currency) {
            for (int i = 0; i < currencies.length; i++) {
                if (currencies[i].equals(currency)) {
                    return base[i];
                }
            }
            return 0;
        }

        /** Bytes the sums take on the heap. */
        long bytes() {
            long b = 0;
            for (double[] v : pv) {
                b += (long) v.length * Double.BYTES + Double.BYTES;
            }
            return b;
        }
    }

    private final int width;
    private final Map<String, Sums> byKey;

    CurrencyTotals(int width, Map<String, Sums> byKey) {
        this.width = width;
        this.byKey = Collections.unmodifiableMap(byKey);
    }

    public int width() {
        return width;
    }

    /** A node's sums, or null for a key the date has none for. */
    public Sums get(String node) {
        return byKey.get(node);
    }

    public java.util.Set<String> keys() {
        return byKey.keySet();
    }

    /** Bytes every node's sums take on the heap. */
    public long bytes() {
        long b = 0;
        for (Sums s : byKey.values()) {
            b += s.bytes();
        }
        return b;
    }

    /** A trade's currency for the totals: its {@code PV_CURRENCY}, USD when it has none. */
    static String currencyOf(Columns trades, int o) {
        String c = trades.has(COLUMN) ? trades.text(COLUMN, o) : null;
        return c == null || c.isBlank() ? ScenarioFx.USD : c;
    }

    /** Adds trade {@code o}'s native PVs into {@code acc} ({@code width} scenarios then today). */
    private static void addTrade(double[] row, double pvUsd, ScenarioFx.Rates r, double[] acc, int width) {
        double pv = Double.isNaN(pvUsd) ? 0 : pvUsd;
        if (r == null) {
            for (int s = 0; s < width; s++) {
                acc[s] += pv + row[s];
            }
            acc[width] += pv;
            return;
        }
        for (int s = 0; s < width; s++) {
            acc[s] += (pv + row[s]) / r.scenarios()[s];
        }
        acc[width] += pv / r.base();
    }

    /** The currency every trade's sums go in: its own when it has rates, else USD (its USD value). */
    private static String bucket(String c, Function<String, ScenarioFx.Rates> fx) {
        return c.equals(ScenarioFx.USD) || fx.apply(c) != null ? c : ScenarioFx.USD;
    }

    /**
     * Every node's sums: each book run's trades summed per currency (float64, from {@code rows}, the rows as read), then the nodes from
     * their books bottom-up. {@code fx} gives a currency's rates (null: none; USD needs none).
     */
    static CurrencyTotals build(Vectors rows, Columns trades, Map<String, int[]> books, Map<String, VarBook.Node> nodes,
            Function<String, ScenarioFx.Rates> fx) {
        int w = rows.width();
        Map<String, ScenarioFx.Rates> rates = new HashMap<>();
        Function<String, ScenarioFx.Rates> memo = c -> c.equals(ScenarioFx.USD) ? null : rates.computeIfAbsent(c, fx);
        Map<String, Map<String, double[]>> perBook = new HashMap<>();
        Map<String, Map<String, int[]>> countBook = new HashMap<>();
        double[] row = new double[w];
        for (Map.Entry<String, int[]> e : books.entrySet()) {
            Map<String, double[]> acc = new TreeMap<>();
            Map<String, int[]> n = new TreeMap<>();
            for (int o = e.getValue()[0]; o < e.getValue()[1]; o++) {
                String c = bucket(currencyOf(trades, o), memo);
                rows.copy(o, row);
                addTrade(row, trades.has("PV") ? trades.number("PV", o) : Double.NaN, memo.apply(c), acc.computeIfAbsent(c, k -> new double[w + 1]), w);
                n.computeIfAbsent(c, k -> new int[1])[0]++;
            }
            perBook.put(e.getKey(), acc);
            countBook.put(e.getKey(), n);
        }
        Map<String, Sums> out = new LinkedHashMap<>();
        for (VarBook.Node node : nodes.values()) {
            Map<String, double[]> acc = new TreeMap<>();
            Map<String, int[]> n = new TreeMap<>();
            for (String b : node.books()) {
                Map<String, double[]> bs = perBook.get(b);
                if (bs == null) {
                    continue;
                }
                bs.forEach((c, v) -> VarMath.addInto(acc.computeIfAbsent(c, k -> new double[w + 1]), v));
                countBook.get(b).forEach((c, k) -> n.computeIfAbsent(c, x -> new int[1])[0] += k[0]);
            }
            out.put(node.id(), sums(acc, n, w));
        }
        return new CurrencyTotals(w, out);
    }

    private static Sums sums(Map<String, double[]> acc, Map<String, int[]> n, int w) {
        String[] cs = acc.keySet().toArray(new String[0]);
        double[][] pv = new double[cs.length][];
        double[] base = new double[cs.length];
        int[] trades = new int[cs.length];
        for (int i = 0; i < cs.length; i++) {
            double[] v = acc.get(cs[i]);
            pv[i] = java.util.Arrays.copyOf(v, w);
            base[i] = v[w];
            trades[i] = n.get(cs[i])[0];
        }
        return new Sums(cs, pv, base, trades);
    }

    /**
     * Any scope's sums, from the USD book: its trades grouped by currency, each group's USD rows summed by the book's planner and its
     * USD value today added, then divided by the currency's rates (the native PV sums). A node whole is its stored sums.
     */
    public static Sums of(VarBook usd, OrdinalSet set, ScenarioFx fx, ParallelSums sums) {
        int w = usd.width();
        Columns tc = usd.trades();
        Map<String, OrdinalSet.Builder> groups = new TreeMap<>();
        Map<String, double[]> pvToday = new HashMap<>();
        Function<String, ScenarioFx.Rates> rates = c -> fx.rates(c).orElse(null);
        set.forEach(o -> {
            String c = bucket(currencyOf(tc, o), rates);
            groups.computeIfAbsent(c, k -> new OrdinalSet.Builder()).add(o);
            double pv = tc.has("PV") ? tc.number("PV", o) : Double.NaN;
            pvToday.computeIfAbsent(c, k -> new double[1])[0] += Double.isNaN(pv) ? 0 : pv;
        });
        Map<String, double[]> acc = new TreeMap<>();
        Map<String, int[]> n = new TreeMap<>();
        groups.forEach((c, g) -> {
            OrdinalSet m = g.build();
            double[] v = usd.vector(m, sums);
            double pv = pvToday.get(c)[0];
            ScenarioFx.Rates r = c.equals(ScenarioFx.USD) ? null : rates.apply(c);
            double[] a = new double[w + 1];
            for (int s = 0; s < w; s++) {
                a[s] = r == null ? pv + v[s] : (pv + v[s]) / r.scenarios()[s];
            }
            a[w] = r == null ? pv : pv / r.base();
            acc.put(c, a);
            n.put(c, new int[] {m.size()});
        });
        return sums(acc, n, w);
    }

    /**
     * These totals with changes added (an intraday layer, VAR_GUIDE.md section 8.4): per node and trade currency, the change of its native
     * PVs ({@code N} scenarios, then today). The nodes without a change share their sums.
     */
    public CurrencyTotals withChanges(Map<String, Map<String, double[]>> changes) {
        if (changes.isEmpty()) {
            return this;
        }
        Map<String, Sums> out = new LinkedHashMap<>(byKey);
        changes.forEach((node, byCcy) -> {
            Map<String, double[]> acc = new TreeMap<>();
            Map<String, int[]> n = new TreeMap<>();
            Sums s = byKey.get(node);
            if (s != null) {
                for (int i = 0; i < s.currencies().length; i++) {
                    double[] v = java.util.Arrays.copyOf(s.pv()[i], width + 1);
                    v[width] = s.base()[i];
                    acc.put(s.currencies()[i], v);
                    n.put(s.currencies()[i], new int[] {s.trades()[i]});
                }
            }
            byCcy.forEach((c, d) -> {
                double[] v = acc.computeIfAbsent(c, k -> new double[width + 1]);
                VarMath.addInto(v, d);
                n.computeIfAbsent(c, k -> new int[1]);
            });
            out.put(node, sums(acc, n, width));
        });
        return new CurrencyTotals(width, out);
    }

    // ------------------------------------------------------------------------------------------------------------- the hot file

    /** The {@code CTOT} section: keys, then per key its currencies, each with its trades, today's sum and the scenarios' sums. */
    byte[] write() {
        VarHotFile.Out o = new VarHotFile.Out();
        o.putInt(byKey.size());
        for (Map.Entry<String, Sums> e : byKey.entrySet()) {
            Sums s = e.getValue();
            o.putString(e.getKey()).putInt(s.currencies().length);
            for (int i = 0; i < s.currencies().length; i++) {
                o.putString(s.currencies()[i]).putInt(s.trades()[i]).putDouble(s.base()[i]);
                for (int j = 0; j < width; j++) {
                    o.putDouble(s.pv()[i][j]);
                }
            }
        }
        return o.bytes();
    }

    static CurrencyTotals read(VarHotFile.In in, int width) {
        Map<String, Sums> out = new LinkedHashMap<>();
        for (int k = in.getInt(); k > 0; k--) {
            String key = in.getString();
            int nc = in.getInt();
            List<String> cs = new ArrayList<>(nc);
            double[][] pv = new double[nc][];
            double[] base = new double[nc];
            int[] trades = new int[nc];
            for (int i = 0; i < nc; i++) {
                cs.add(in.getString());
                trades[i] = in.getInt();
                base[i] = in.getDouble();
                pv[i] = new double[width];
                for (int j = 0; j < width; j++) {
                    pv[i][j] = in.getDouble();
                }
            }
            out.put(key, new Sums(cs.toArray(new String[0]), pv, base, trades));
        }
        return new CurrencyTotals(width, out);
    }
}
