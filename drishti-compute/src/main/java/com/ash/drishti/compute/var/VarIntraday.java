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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * A date's intraday layer on its morning book (VAR_GUIDE.md section 8.4, as built in 2.9.4): the trades a pricing system re-sent during
 * the day (amended, new or cancelled), each trade's current P&amp;L replacing its morning one. Applying a batch is incremental: each updated
 * trade's difference (new − old) is added to its book and to every node above it in every hierarchy, and to the per-trade-currency
 * totals; nothing else is recomputed (REACTIVE_CONNECTORS.md section 10.2). The morning store is never changed (the hot file is written
 * once): an updated row is the stored row plus its difference ({@link OverlayVectors}), a new trade is appended after the morning's
 * ordinals. Immutable: {@link #apply} returns a new book over the same morning book, so a request reading the previous one stays
 * consistent; only the current P&amp;L per trade is kept, never the batches.
 *
 * <p>A trade amended into another book or position is its old ordinal cancelled and a new one added (its old book and position lose it,
 * the new ones gain it). A new trade's position comes from the update's {@code POSITION_ID} (the server derives it by the position rules):
 * an existing position takes it as a member, else it is a position of its own.
 */
public final class VarIntraday {

    /**
     * One trade's update, already in this book's currency.
     *
     * @param tradeId the trade
     * @param pnl its P&amp;L under each scenario, or null: the trade is cancelled (its P&amp;L removed)
     * @param pv its value today (the {@code PV} column), NaN when none
     * @param texts its {@link VarBook#TRADE_TEXTS}, its position id and rule derived
     * @param numbers its {@link VarBook#TRADE_NUMBERS} ({@code PV} as {@code pv})
     * @param pvUsd its value today in USD (for the per-trade-currency totals; NaN: none)
     * @param pnlUsd its USD P&amp;L (the totals; null when cancelled)
     */
    public record Update(String tradeId, double[] pnl, double pv, String[] texts, double[] numbers, double pvUsd, double[] pnlUsd) {}

    /**
     * What the layer holds, for the labels: the updates applied, the trades changed (amended, added, cancelled), the newest
     * {@code COMPUTED_AT} and the batches (the newest 20, and how many).
     */
    public record Info(int updates, int trades, int amended, int added, int cancelled, String lastAt, List<String> batches, int batchCount) {}

    /** A morning trade's P&amp;L as its aggregates hold it (this book's currency): exactly as received for the USD book. */
    public interface Morning {
        double[] row(int ordinal);
    }

    private final VarBook morning;
    private final int t0;
    private final int p0;
    /** Morning ordinal: current − stored row. */
    private final NavigableMap<Integer, double[]> delta;
    /** Every ordinal the layer changed or added: its current P&amp;L (zero once cancelled) and value today in USD and in this currency. */
    private final Map<Integer, double[]> current;
    private final Map<Integer, double[]> currentUsd;
    private final Map<Integer, double[]> pvs;              // {pv here, pv USD}
    private final Map<String, Integer> ordinalOf;          // the trades the layer touched: their live ordinal
    private final BitSet cancelled;
    private final List<String> addedIds;
    private final List<double[]> addedRows;
    private final List<String[]> addedTexts;
    private final List<double[]> addedNumbers;
    private final List<Integer> addedPosition;
    private final Map<Integer, String[]> texts;            // morning ordinal: its texts now
    private final Map<Integer, double[]> numbers;
    private final Map<String, double[]> planner;           // the morning aggregates with the morning trades' changes (the plan reads them)
    private final Map<String, double[]> added;             // per aggregate key, the added trades' current P&L
    private final Map<String, Map<String, double[]>> totals; // per node and trade currency, the native change (N scenarios, then today)
    private final Map<Integer, List<Integer>> extras;      // position: the added ordinals it gained
    private final List<String> newPositions;
    private final Info info;

    private VarIntraday(VarBook morning, NavigableMap<Integer, double[]> delta, Map<Integer, double[]> current, Map<Integer, double[]> currentUsd,
            Map<Integer, double[]> pvs, Map<String, Integer> ordinalOf, BitSet cancelled, List<String> addedIds, List<double[]> addedRows,
            List<String[]> addedTexts, List<double[]> addedNumbers, List<Integer> addedPosition, Map<Integer, String[]> texts,
            Map<Integer, double[]> numbers, Map<String, double[]> planner, Map<String, double[]> added, Map<String, Map<String, double[]>> totals,
            Map<Integer, List<Integer>> extras, List<String> newPositions, Info info) {
        this.morning = morning;
        this.t0 = morning.tradeCount();
        this.p0 = morning.positionCount();
        this.delta = delta;
        this.current = current;
        this.currentUsd = currentUsd;
        this.pvs = pvs;
        this.ordinalOf = ordinalOf;
        this.cancelled = cancelled;
        this.addedIds = addedIds;
        this.addedRows = addedRows;
        this.addedTexts = addedTexts;
        this.addedNumbers = addedNumbers;
        this.addedPosition = addedPosition;
        this.texts = texts;
        this.numbers = numbers;
        this.planner = planner;
        this.added = added;
        this.totals = totals;
        this.extras = extras;
        this.newPositions = newPositions;
        this.info = info;
    }

    private static VarIntraday empty(VarBook m) {
        return new VarIntraday(m, new TreeMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new BitSet(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new ArrayList<>(), new Info(0, 0, 0, 0, 0, null, List.of(), 0));
    }

    /** A copy to change (each map copied once; the vectors in it are replaced, never changed in place). */
    private VarIntraday copy() {
        Map<String, Map<String, double[]>> t = new HashMap<>();
        totals.forEach((k, v) -> t.put(k, new HashMap<>(v)));
        Map<Integer, List<Integer>> ex = new HashMap<>();
        extras.forEach((k, v) -> ex.put(k, new ArrayList<>(v)));
        return new VarIntraday(morning, new TreeMap<>(delta), new HashMap<>(current), new HashMap<>(currentUsd), new HashMap<>(pvs),
                new HashMap<>(ordinalOf), (BitSet) cancelled.clone(), new ArrayList<>(addedIds), new ArrayList<>(addedRows), new ArrayList<>(addedTexts),
                new ArrayList<>(addedNumbers), new ArrayList<>(addedPosition), new HashMap<>(texts), new HashMap<>(numbers), new HashMap<>(planner),
                new HashMap<>(added), t, ex, new ArrayList<>(newPositions), info);
    }

    public Info info() {
        return info;
    }

    // ---------------------------------------------------------------------------------------------------------- what the book reads

    boolean cancelled(int o) {
        return cancelled.get(o);
    }

    BitSet cancelledSet() {
        return cancelled;
    }

    OrdinalSet cancelledOrdinals() {
        return OrdinalSet.of(cancelled.stream().toArray());
    }

    int added() {
        return addedIds.size();
    }

    OrdinalSet addedOrdinals() {
        return OrdinalSet.range(t0, t0 + addedIds.size());
    }

    /** A position's members: its morning run without the cancelled, with the trades added to it. */
    OrdinalSet members(int p, OrdinalSet run) {
        OrdinalSet m = p < p0 ? run : OrdinalSet.empty();
        List<Integer> ex = extras.get(p);
        if (ex != null) {
            m = m.or(OrdinalSet.of(ex.stream().mapToInt(Integer::intValue).toArray()));
        }
        return cancelled.isEmpty() ? m : m.andNot(cancelledOrdinals());
    }

    /** An aggregate with the added trades' P&amp;L. */
    double[] withAdded(String key, double[] planned) {
        double[] a = added.get(key);
        if (a == null) {
            return planned;
        }
        double[] out = planned.clone();
        VarMath.addInto(out, a);
        return out;
    }

    /** A book's positions: its morning ones (in order), then those added in it. */
    List<Integer> positionsOfBook(String book, int[] range) {
        List<Integer> out = new ArrayList<>();
        int last = -1;
        if (range != null) {
            for (int o = range[0]; o < range[1]; o++) {
                int p = morning.positionOf(o);
                if (p != last && !members(p, morning.positionMembers(p)).isEmpty()) {
                    out.add(p);
                }
                last = p;
            }
        }
        for (int k = 0; k < newPositions.size(); k++) {
            int p = p0 + k;
            int o = extras.getOrDefault(p, List.of(-1)).get(0);
            if (o >= 0 && book.equals(addedTexts.get(o - t0)[VarBook.TRADE_TEXTS.indexOf("TT_BOOK")]) && !members(p, OrdinalSet.empty()).isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------- applying

    /**
     * Applies one batch of updates (each trade once: the latest of the batch) to {@code book} (a morning book, or one with a layer),
     * returning the new book. {@code exact} gives a morning trade's P&amp;L as its aggregates hold it; {@code fx} a currency's rates (for
     * the per-trade-currency totals; USD needs none); {@code batches} the batch ids the updates came in, {@code lastAt} their newest
     * {@code COMPUTED_AT}.
     */
    public static VarBook apply(VarBook book, List<Update> updates, Morning exact, Function<String, ScenarioFx.Rates> fx, List<String> batches,
            String lastAt) {
        VarBook m = book.morning();
        VarIntraday l = book.intraday() == null ? empty(m) : book.intraday().copy();
        int w = m.width();
        Map<String, List<String>> keysOfBook = keysOfBook(m);
        int idxBook = VarBook.TRADE_TEXTS.indexOf("TT_BOOK");
        int idxPos = VarBook.TRADE_TEXTS.indexOf("POSITION_ID");
        int idxCcy = VarBook.TRADE_TEXTS.indexOf(CurrencyTotals.COLUMN);
        int idxPv = VarBook.TRADE_NUMBERS.indexOf("PV");
        Set<String> touched = new LinkedHashSet<>();
        int amended = 0;
        for (Update u : updates) {
            touched.add(u.tradeId());
            Integer known = l.ordinalOf.get(u.tradeId());
            int o = known != null ? known : m.tradeOrdinal(u.tradeId());
            boolean live = o >= 0 && !l.cancelled.get(o);
            if (u.pnl() == null) {                                     // a cancel: the trade's P&L removed
                if (live) {
                    l.change(o, null, Double.NaN, Double.NaN, null, null, exact, keysOfBook, fx, w);
                    l.cancelled.set(o);
                    l.ordinalOf.put(u.tradeId(), o);
                }
                continue;
            }
            String[] oldTexts = o < 0 ? null : l.textsOf(o);
            boolean moved = o >= 0 && (!eq(oldTexts[idxBook], u.texts()[idxBook]) || !eq(oldTexts[idxPos], u.texts()[idxPos]));
            if (o >= 0 && !moved) {                                    // amended in place (or revived after a cancel)
                l.cancelled.clear(o);
                l.change(o, u.pnl(), u.pv(), u.pvUsd(), u.pnlUsd(), u.texts(), exact, keysOfBook, fx, w);
                l.setTexts(o, u.texts(), numbers(u, idxPv));
                l.ordinalOf.put(u.tradeId(), o);
                amended++;
                continue;
            }
            if (live) {                                                 // moved to another book or position: the old one cancelled
                l.change(o, null, Double.NaN, Double.NaN, null, null, exact, keysOfBook, fx, w);
                l.cancelled.set(o);
            }
            int n = t0(l) + l.addedIds.size();                          // a new ordinal, after the morning's
            l.addedIds.add(u.tradeId());
            l.addedRows.add(new double[w]);
            l.addedTexts.add(u.texts().clone());
            l.addedNumbers.add(numbers(u, idxPv));
            String pid = u.texts()[idxPos] == null ? "POS-" + u.tradeId() : u.texts()[idxPos];
            int p = m.positionIndex(pid);
            if (p < 0) {
                int k = l.newPositions.indexOf(pid);
                p = k >= 0 ? m.positionCount() + k : m.positionCount() + l.newPositions.size();
                if (k < 0) {
                    l.newPositions.add(pid);
                }
            }
            l.addedPosition.add(p);
            l.extras.computeIfAbsent(p, x -> new ArrayList<>()).add(n);
            l.ordinalOf.put(u.tradeId(), n);
            l.change(n, u.pnl(), u.pv(), u.pvUsd(), u.pnlUsd(), u.texts(), exact, keysOfBook, fx, w);
        }
        List<String> bs = new ArrayList<>(l.info.batches());
        for (String b : batches) {
            bs.remove(b);
            bs.add(b);
        }
        int count = l.info.batchCount() + (int) batches.stream().filter(b -> !l.info.batches().contains(b)).count();
        Set<String> all = new LinkedHashSet<>(l.ordinalOf.keySet());
        String at = l.info.lastAt() == null || lastAt != null && lastAt.compareTo(l.info.lastAt()) > 0 ? lastAt : l.info.lastAt();
        VarIntraday done = new VarIntraday(m, l.delta, l.current, l.currentUsd, l.pvs, l.ordinalOf, l.cancelled, l.addedIds, l.addedRows,
                l.addedTexts, l.addedNumbers, l.addedPosition, l.texts, l.numbers, l.planner, l.added, l.totals, l.extras, l.newPositions,
                new Info(l.info.updates() + updates.size(), all.size(), l.info.amended() + amended, l.addedIds.size(), l.cancelled.cardinality(), at,
                        List.copyOf(bs.subList(Math.max(0, bs.size() - 20), bs.size())), count));
        return done.book();
    }

    private static int t0(VarIntraday l) {
        return l.t0;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static double[] numbers(Update u, int idxPv) {
        double[] n = u.numbers() == null ? new double[VarBook.TRADE_NUMBERS.size()] : u.numbers().clone();
        n[idxPv] = u.pv();
        return n;
    }

    private String[] textsOf(int o) {
        if (o >= t0) {
            return addedTexts.get(o - t0);
        }
        String[] t = texts.get(o);
        return t != null ? t : morning.trades().textsOf(o);
    }

    private void setTexts(int o, String[] t, double[] n) {
        if (o >= t0) {
            addedTexts.set(o - t0, t.clone());
            addedNumbers.set(o - t0, n);
        } else {
            texts.put(o, t.clone());
            numbers.put(o, n);
        }
    }

    /** Every aggregate key a book's trades are summed into: the book, and each node holding it (both hierarchies). */
    private static Map<String, List<String>> keysOfBook(VarBook m) {
        Map<String, List<String>> out = new HashMap<>();
        for (VarBook.Node n : m.morning().nodes().values()) {
            for (String b : n.books()) {
                out.computeIfAbsent(b, k -> new ArrayList<>(List.of(k))).add(n.id());
            }
        }
        return out;
    }

    /**
     * Trade {@code o}'s P&amp;L becomes {@code pnl} (null: zero, cancelled): the difference goes into its book's and nodes' aggregates,
     * its row, and (a USD book) the per-trade-currency totals.
     */
    private void change(int o, double[] pnl, double pv, double pvUsd, double[] pnlUsd, String[] newTexts, Morning exact,
            Map<String, List<String>> keysOfBook, Function<String, ScenarioFx.Rates> fx, int w) {
        boolean base = o < t0;
        double[] old = current.containsKey(o) ? current.get(o) : base ? exact.row(o) : new double[w];
        double[] now = pnl == null ? new double[w] : pnl.clone();
        double[] diff = new double[w];
        for (int s = 0; s < w; s++) {
            diff[s] = now[s] - old[s];
        }
        String[] t = textsOf(o);
        String book = t[VarBook.TRADE_TEXTS.indexOf("TT_BOOK")];
        List<String> keys = book == null ? List.of() : keysOfBook.getOrDefault(book, List.of(book));
        Map<String, double[]> into = base ? planner : added;
        for (String k : keys) {
            double[] a = base ? planner.getOrDefault(k, morning.morningAggregate(k)) : added.get(k);
            if (a == null && base) {
                continue;
            }
            double[] next = a == null ? new double[w] : a.clone();
            VarMath.addInto(next, diff);
            into.put(k, next);
        }
        if (base) {
            double[] stored = new double[w];
            morning.store().copy(o, stored);
            double[] d = new double[w];
            for (int s = 0; s < w; s++) {
                d[s] = now[s] - stored[s];
            }
            delta.put(o, d);
        } else {
            addedRows.set(o - t0, now);
        }
        if (morning.currencyTotals() != null) {                      // the USD book: the per-trade-currency totals follow
            double[] oldUsd = currentUsd.containsKey(o) ? currentUsd.get(o) : base ? old : new double[w];
            double oldPv = pvs.containsKey(o) ? pvs.get(o)[1] : base ? morning.trades().number("PV", o) : Double.NaN;
            String oldCcy = currencyOf(textsOf(o), fx);
            double[] nowUsd = pnlUsd == null ? (pnl == null ? null : now) : pnlUsd;
            String newCcy = newTexts == null ? oldCcy : currencyOf(newTexts, fx);   // a trade may change form (scenario PVs, a P&L vector)
            for (String k : keys) {
                if (morning.nodes().containsKey(k)) {
                    addNative(k, oldCcy, oldUsd, oldPv, fx, -1, w);
                    if (nowUsd != null) {
                        addNative(k, newCcy, nowUsd, pvUsd, fx, 1, w);
                    }
                }
            }
            currentUsd.put(o, nowUsd == null ? new double[w] : nowUsd.clone());
        }
        current.put(o, now);
        pvs.put(o, new double[] {pv, pvUsd});
    }

    private static String currencyOf(String[] texts, Function<String, ScenarioFx.Rates> fx) {
        String c = texts[VarBook.TRADE_TEXTS.indexOf(CurrencyTotals.COLUMN)];
        return c == null || c.isBlank() || !c.equals(ScenarioFx.USD) && fx.apply(c) == null ? ScenarioFx.USD : c;
    }

    /** {@code sign} × a trade's native PVs (from its USD P&amp;L and USD value today) into a node's change in currency {@code c}. */
    private void addNative(String node, String c, double[] usd, double pvUsd, Function<String, ScenarioFx.Rates> fx, int sign, int w) {
        double pv = Double.isNaN(pvUsd) ? 0 : pvUsd;
        ScenarioFx.Rates r = c.equals(ScenarioFx.USD) ? null : fx.apply(c);
        double[] acc = totals.computeIfAbsent(node, k -> new HashMap<>()).get(c);
        double[] next = acc == null ? new double[w + 1] : acc.clone();
        for (int s = 0; s < w; s++) {
            next[s] += sign * (r == null ? pv + usd[s] : (pv + usd[s]) / r.scenarios()[s]);
        }
        next[w] += sign * (r == null ? pv : pv / r.base());
        totals.get(node).put(c, next);
    }

    private static int[] ordinals(OrdinalSet set) {
        int[] out = new int[set.size()];
        int[] i = {0};
        set.forEach(o -> out[i[0]++] = o);
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------- the book it makes

    /** The morning book with this layer: the same morning arrays, the rows overlaid, the columns, positions and nodes with the changes. */
    private VarBook book() {
        VarBook m = morning;
        int na = addedIds.size();
        String[] tids = java.util.Arrays.copyOf(m.tradeIdsArray(), t0 + na);
        for (int k = 0; k < na; k++) {
            tids[t0 + k] = addedIds.get(k);
        }
        Columns tcols = m.trades().edited(texts, numbers, addedTexts, addedNumbers);
        int np = newPositions.size();
        int[] posOf = java.util.Arrays.copyOf(m.positionOfArray(), t0 + na);
        for (int k = 0; k < na; k++) {
            posOf[t0 + k] = addedPosition.get(k);
        }
        String[] pids = java.util.Arrays.copyOf(m.positionIdsArray(), p0 + np);
        int[] from = java.util.Arrays.copyOf(m.posFromArray(), p0 + np);
        int[] to = java.util.Arrays.copyOf(m.posToArray(), p0 + np);
        for (int k = 0; k < np; k++) {
            pids[p0 + k] = newPositions.get(k);
            from[p0 + k] = 0;                                            // its members are its extras
            to[p0 + k] = 0;
        }
        // positions whose members changed: derived again from their trades
        Set<Integer> affected = new LinkedHashSet<>();
        for (Integer o : current.keySet()) {
            affected.add(o < t0 ? m.positionOf(o) : addedPosition.get(o - t0));
        }
        Map<Integer, String[]> ptexts = new HashMap<>();
        Map<Integer, double[]> pnums = new HashMap<>();
        List<String[]> addT = new ArrayList<>();
        List<double[]> addN = new ArrayList<>();
        for (int k = 0; k < np; k++) {
            addT.add(null);
            addN.add(null);
        }
        for (int p : affected) {
            OrdinalSet run = p < p0 ? OrdinalSet.range(m.posFromArray()[p], m.posToArray()[p]) : OrdinalSet.empty();
            int[] mem = ordinals(members(p, run));
            if (mem.length == 0) {
                mem = p < p0 ? new int[] {m.posFromArray()[p]} : new int[] {extras.get(p).get(0)};
            }
            Object[] row = VarBook.Builder.positionRow(tcols, mem);
            if (p < p0) {
                ptexts.put(p, (String[]) row[0]);
                pnums.put(p, (double[]) row[1]);
            } else {
                addT.set(p - p0, (String[]) row[0]);
                addN.set(p - p0, (double[]) row[1]);
            }
        }
        Columns pcols = m.positions().edited(ptexts, pnums, addT, addN);
        // the nodes: their members without the cancelled, with the trades added in their books
        Map<String, List<Integer>> addedByBook = new HashMap<>();
        for (int k = 0; k < na; k++) {
            if (!cancelled.get(t0 + k)) {
                addedByBook.computeIfAbsent(String.valueOf(addedTexts.get(k)[VarBook.TRADE_TEXTS.indexOf("TT_BOOK")]), x -> new ArrayList<>()).add(t0 + k);
            }
        }
        OrdinalSet gone = cancelledOrdinals();
        Map<String, VarBook.Node> nodes = new LinkedHashMap<>();
        for (VarBook.Node n : m.nodes().values()) {
            OrdinalSet mem = n.members().andNot(gone);
            List<Integer> plus = new ArrayList<>();
            for (String b : n.books()) {
                plus.addAll(addedByBook.getOrDefault(b, List.of()));
            }
            if (!plus.isEmpty()) {
                mem = mem.or(OrdinalSet.of(plus.stream().mapToInt(Integer::intValue).toArray()));
            }
            nodes.put(n.id(), new VarBook.Node(n.id(), n.hierarchy(), n.level(), n.name(), n.parent(), n.children(), n.books(), n.limit(), mem));
        }
        Map<String, double[]> agg = new HashMap<>(m.morningAggregates());
        agg.putAll(planner);
        CurrencyTotals ct = m.currencyTotals() == null ? null : m.currencyTotals().withChanges(totals);
        return new VarBook(m.businessDate(), m.scenarioSet(), m.scenarioDates(), new OverlayVectors(m.store(), delta, addedRows), tids, tcols, posOf,
                pids, from, to, pcols, nodes, m.bookRanges(), agg, m.failed(), m.loadMillis(), m.hot(), ct, m, this);
    }
}
