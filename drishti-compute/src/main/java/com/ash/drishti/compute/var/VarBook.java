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
import com.ash.drishti.compute.vector.VectorStore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One business date of the VaR engine in memory (VAR_DATA_MODEL.md sections 11 to 13): every trade's P&amp;L vector in a
 * {@link VectorStore}, ordered by the primary hierarchy (legal entity, desk, book, position, trade) so a book and a position are each
 * one run of ordinals; the trades' and the positions' attributes column by column; the hierarchies' nodes with their members; the
 * node aggregates (every book's and node's vector, summed once when the date is loaded); and the nodes' official history.
 *
 * <p>The vector grain is the trade: a position's vector is the sum of its trades' (a run of ordinals), so a trade, a position, a book
 * and any basket of trades or positions are all exact, and a long and a short in one position net in the sum. Immutable once built,
 * except the cache of position vectors; any number of requests read it at once.
 */
public final class VarBook {

    /** The trades' text columns the engine keeps (from the pnl-vector rows), in this order. */
    public static final List<String> TRADE_TEXTS = List.of("POSITION_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "PORTFOLIO",
            "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY", "COUNTERPARTY", "DIRECTION", "INSTRUMENT_ID");
    public static final List<String> TRADE_NUMBERS = List.of("QUANTITY", "PV");
    /** The positions' columns (from the position records). */
    public static final List<String> POSITION_TEXTS = List.of("INSTRUMENT_ID", "INSTRUMENT", "DIRECTION", "LEGAL_ENTITY", "DIVISION", "DESK",
            "TT_BOOK", "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY", "COUNTERPARTY", "STRATEGY", "TAGS", "STATUS");
    public static final List<String> POSITION_NUMBERS = List.of("QUANTITY", "MARKET_VALUE", "NOTIONAL", "TRADE_COUNT");

    /** A node of a hierarchy: its books and its members (the union of its books' runs). */
    public record Node(String id, String hierarchy, String level, String name, String parent, List<String> children, List<String> books,
            double limit, OrdinalSet members) {}

    /** A node's official figures over time (var-node), oldest first. */
    public record NodeHistory(LocalDate[] dates, double[] var, double[] es, double[] hypo, double[] limit) {}

    private final LocalDate businessDate;
    private final String scenarioSet;
    private final List<LocalDate> scenarioDates;
    private final VectorStore store;
    private final String[] tradeIds;
    private final Map<String, Integer> tradeOrdinal;
    private final Columns trades;
    private final int[] positionOf;
    private final String[] positionIds;
    private final int[] posFrom;
    private final int[] posTo;
    private final Map<String, Integer> positionIndex;
    private final Columns positions;
    private final Map<String, Node> nodes;
    private final Map<String, int[]> bookRange;
    private final Map<String, double[]> aggregates;
    private final Map<String, NodeHistory> history;
    private final Map<String, double[]> officialVectors;
    private final int failed;
    private final long loadMillis;
    private final Map<Integer, double[]> positionCache = new ConcurrentHashMap<>();

    VarBook(Builder b, VectorStore store, String[] tradeIds, Columns trades, int[] positionOf, String[] positionIds, int[] posFrom, int[] posTo,
            Columns positions, Map<String, Node> nodes, Map<String, int[]> bookRange, Map<String, double[]> aggregates, long loadMillis) {
        this.businessDate = b.businessDate;
        this.scenarioSet = b.scenarioSet;
        this.scenarioDates = List.copyOf(b.scenarioDates);
        this.store = store;
        this.tradeIds = tradeIds;
        this.tradeOrdinal = new HashMap<>(tradeIds.length * 2);
        for (int i = 0; i < tradeIds.length; i++) {
            tradeOrdinal.put(tradeIds[i], i);
        }
        this.trades = trades;
        this.positionOf = positionOf;
        this.positionIds = positionIds;
        this.posFrom = posFrom;
        this.posTo = posTo;
        this.positionIndex = new HashMap<>(positionIds.length * 2);
        for (int i = 0; i < positionIds.length; i++) {
            positionIndex.put(positionIds[i], i);
        }
        this.positions = positions;
        this.nodes = nodes;
        this.bookRange = bookRange;
        this.aggregates = aggregates;
        this.history = Map.copyOf(b.history);
        this.officialVectors = Map.copyOf(b.officialVectors);
        this.failed = b.failed;
        this.loadMillis = loadMillis;
    }

    public LocalDate businessDate() {
        return businessDate;
    }

    public String scenarioSet() {
        return scenarioSet;
    }

    public List<LocalDate> scenarioDates() {
        return scenarioDates;
    }

    public VectorStore store() {
        return store;
    }

    public int width() {
        return store.width();
    }

    public int tradeCount() {
        return tradeIds.length;
    }

    public int positionCount() {
        return positionIds.length;
    }

    /** Trades the batch has no vector for (counted and shown, never taken as zero). */
    public int failed() {
        return failed;
    }

    public long loadMillis() {
        return loadMillis;
    }

    public String tradeId(int ordinal) {
        return tradeIds[ordinal];
    }

    /** The ordinal of a trade, -1 when the date has no vector for it. */
    public int tradeOrdinal(String id) {
        return tradeOrdinal.getOrDefault(id, -1);
    }

    public Columns trades() {
        return trades;
    }

    /** The position index of a trade's ordinal. */
    public int positionOf(int ordinal) {
        return positionOf[ordinal];
    }

    public String positionId(int index) {
        return positionIds[index];
    }

    public int positionIndex(String id) {
        return positionIndex.getOrDefault(id, -1);
    }

    /** A position's trades: the run {@code [from, to)} of ordinals. */
    public int positionFrom(int index) {
        return posFrom[index];
    }

    public int positionTo(int index) {
        return posTo[index];
    }

    public OrdinalSet positionMembers(int index) {
        return OrdinalSet.range(posFrom[index], posTo[index]);
    }

    public Columns positions() {
        return positions;
    }

    /** A position's vector: the sum of its trades' (cached; a few thousand values per position at most). */
    public double[] positionVector(int index) {
        return positionCache.computeIfAbsent(index, i -> {
            double[] acc = new double[store.width()];
            store.addRangeInto(acc, posFrom[i], posTo[i]);
            return acc;
        });
    }

    /** A trade's vector (a copy). */
    public double[] tradeVector(int ordinal) {
        double[] v = new double[store.width()];
        store.copy(ordinal, v);
        return v;
    }

    public Map<String, Node> nodes() {
        return nodes;
    }

    public Node node(String id) {
        return nodes.get(id);
    }

    /** A book's run of ordinals ({@code [from, to)}), or null for a book with no trade valued. */
    public int[] bookRange(String book) {
        return bookRange.get(book);
    }

    /** The positions of a book, in order. */
    public List<Integer> positionsOfBook(String book) {
        int[] r = bookRange.get(book);
        List<Integer> out = new ArrayList<>();
        if (r == null) {
            return out;
        }
        int last = -1;
        for (int o = r[0]; o < r[1]; o++) {
            int p = positionOf[o];
            if (p != last) {
                out.add(p);
                last = p;
            }
        }
        return out;
    }

    /** A node's vector (summed when the date was loaded); a book's too, under its node id or its book name. */
    public double[] aggregate(String nodeOrBook) {
        return aggregates.get(nodeOrBook);
    }

    public NodeHistory history(String nodeId) {
        return history.get(nodeId);
    }

    /** The node's vector as the batch's precomputation stored it (var-node), to compare with this engine's sum; null when none. */
    public double[] officialVector(String nodeId) {
        return officialVectors.get(nodeId);
    }

    /** The root of a hierarchy ({@code MGMT~BANK}), or null. */
    public Node root(String hierarchy) {
        return nodes.values().stream().filter(n -> n.hierarchy().equals(hierarchy) && n.parent() == null).findFirst().orElse(null);
    }

    /** Every ordinal of the date. */
    public OrdinalSet all() {
        return OrdinalSet.range(0, tradeIds.length);
    }

    /**
     * The sum of the vectors of {@code set}, planned (VAR_DATA_MODEL.md section 11.3): from the top of the primary hierarchy down, a node
     * the set covers whole is its aggregate; a node it covers but for a few trades is the aggregate minus those; a node it does not touch
     * is skipped; a book it covers in part sums the set's runs in it. Whatever the plan, the vector is the same (to the bit for whole
     * numbers, within rounding for others); the cost is the number of aggregates and uncovered rows, not the size of the nodes.
     */
    public double[] vector(OrdinalSet set, ParallelSums sums) {
        double[] acc = new double[store.width()];
        Node root = root("MGMT");
        if (root == null || set.isEmpty()) {
            return set.isEmpty() ? acc : sums.sum(store, set);
        }
        Map<String, Integer> covered = new HashMap<>();
        boolean anyWhole = false;
        for (Map.Entry<String, int[]> e : bookRange.entrySet()) {
            int c = set.countIn(e.getValue()[0], e.getValue()[1]);
            covered.put(e.getKey(), c);
            anyWhole |= c > 0 && c == e.getValue()[1] - e.getValue()[0];
        }
        if (!anyWhole) {                       // scattered leaves (a basket, a rule): no aggregate helps; one parallel pass over them
            return sums.sum(store, set);
        }
        plan(root, set, acc, covered, sums);
        // trades in no book of the hierarchy (none in a consistent batch) are summed as leaves
        OrdinalSet rest = set.andNot(root.members());
        if (!rest.isEmpty()) {
            VarMath.addInto(acc, sums.sum(store, rest));
        }
        return acc;
    }

    private int count(Node n, Map<String, Integer> covered) {
        int c = 0;
        for (String b : n.books()) {
            c += covered.getOrDefault(b, 0);
        }
        return c;
    }

    private int size(Node n) {
        int s = 0;
        for (String b : n.books()) {
            int[] r = bookRange.get(b);
            s += r == null ? 0 : r[1] - r[0];
        }
        return s;
    }

    private void plan(Node n, OrdinalSet set, double[] acc, Map<String, Integer> covered, ParallelSums sums) {
        int c = count(n, covered);
        if (c == 0) {
            return;
        }
        int size = size(n);
        double[] agg = aggregates.get(n.id());
        if (c == size && agg != null) {
            VarMath.addInto(acc, agg);
            return;
        }
        int missing = size - c;
        if (agg != null && missing <= Math.max(16, c / 8)) {            // a node minus a few trades: its aggregate minus them
            VarMath.addInto(acc, agg);
            double[] miss = new double[store.width()];
            for (String b : n.books()) {
                int[] r = bookRange.get(b);
                if (r != null && covered.getOrDefault(b, 0) < r[1] - r[0]) {
                    int[] at = {r[0]};                           // the gaps between the set's runs in the book
                    set.forEachRunIn(r[0], r[1], (f, t) -> {
                        store.addRangeInto(miss, at[0], f);
                        at[0] = t;
                    });
                    store.addRangeInto(miss, at[0], r[1]);
                }
            }
            for (int j = 0; j < acc.length; j++) {
                acc[j] -= miss[j];
            }
            return;
        }
        if (n.level().equals("book") || n.children().isEmpty()) {
            for (String b : n.books()) {
                int[] r = bookRange.get(b);
                if (r != null) {
                    set.forEachRunIn(r[0], r[1], (f, t) -> store.addRangeInto(acc, f, t));
                }
            }
            return;
        }
        for (String child : n.children()) {
            Node cn = nodes.get(child);
            if (cn != null) {
                plan(cn, set, acc, covered, sums);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------- building

    /** Gathers one business date's rows in any order, then sorts them into hierarchy order and sums the aggregates. */
    public static final class Builder {
        private final LocalDate businessDate;
        private final String scenarioSet;
        private final List<LocalDate> scenarioDates;
        private final VectorStore.Builder vectors;
        private final List<String> ids = new ArrayList<>();
        private final Columns.Builder tradeCols = new Columns.Builder(TRADE_TEXTS, TRADE_NUMBERS);
        private final Map<String, String[]> positionTexts = new LinkedHashMap<>();
        private final Map<String, double[]> positionNumbers = new LinkedHashMap<>();
        private final List<String[]> nodeRows = new ArrayList<>();
        private final Map<String, List<String>> nodeBooks = new HashMap<>();
        private final Map<String, Double> nodeLimits = new HashMap<>();
        private final Map<String, NodeHistory> history = new HashMap<>();
        private final Map<String, double[]> officialVectors = new HashMap<>();
        private int failed;

        public Builder(LocalDate businessDate, String scenarioSet, List<LocalDate> scenarioDates, int rowsPerChunk, long budgetBytes) {
            this.businessDate = businessDate;
            this.scenarioSet = scenarioSet;
            this.scenarioDates = scenarioDates;
            this.vectors = new VectorStore.Builder(scenarioDates.size(), rowsPerChunk, budgetBytes);
        }

        public int width() {
            return vectors.width();
        }

        /** A trade's vector and attributes ({@link #TRADE_TEXTS}, {@link #TRADE_NUMBERS} order); a row with no vector is counted failed. */
        public synchronized void trade(String id, double[] values, int length, String[] texts, double[] numbers) {
            if (length < 0) {
                failed++;
                return;
            }
            vectors.add(values, length);
            ids.add(id);
            tradeCols.add(texts, numbers);
        }

        /** A position record: its columns ({@link #POSITION_TEXTS}, {@link #POSITION_NUMBERS} order). */
        public void position(String id, String[] texts, double[] numbers) {
            positionTexts.put(id, texts.clone());
            positionNumbers.put(id, numbers.clone());
        }

        /** A node of a hierarchy, with the books beneath it. */
        public void node(String id, String hierarchy, String level, String name, String parent, List<String> books, double limit) {
            nodeRows.add(new String[] {id, hierarchy, level, name, parent});
            nodeBooks.put(id, List.copyOf(books));
            nodeLimits.put(id, limit);
        }

        public void history(String nodeId, NodeHistory h) {
            history.put(nodeId, h);
        }

        public void officialVector(String nodeId, double[] v) {
            officialVectors.put(nodeId, v);
        }

        public int trades() {
            return ids.size();
        }

        /** Sorts, permutes, indexes and sums. */
        public VarBook build(ParallelSums sums) {
            long t0 = System.nanoTime();
            Columns cols = tradeCols.build();
            int n = ids.size();
            int[] order = sortOrder(cols, n);
            boolean sorted = true;
            for (int i = 0; i < n && sorted; i++) {
                sorted = order[i] == i;
            }
            VectorStore store = sorted ? vectors.build() : vectors.permuted(order);
            Columns tcols = sorted ? cols : cols.permuted(order);
            String[] tids = new String[n];
            for (int i = 0; i < n; i++) {
                tids[i] = ids.get(order[i]);
            }
            // positions: runs of POSITION_ID in the sorted trades (a trade whose position has no record makes one from itself)
            List<String> pids = new ArrayList<>();
            List<int[]> runs = new ArrayList<>();
            int[] posOf = new int[n];
            Map<String, int[]> books = new LinkedHashMap<>();
            String prevPos = null;
            String prevBook = null;
            for (int i = 0; i < n; i++) {
                String p = tcols.text("POSITION_ID", i);
                String pid = p == null ? "POS-" + tids[i] : p;
                if (!pid.equals(prevPos)) {
                    pids.add(pid);
                    runs.add(new int[] {i, i + 1});
                    prevPos = pid;
                } else {
                    runs.get(runs.size() - 1)[1] = i + 1;
                }
                posOf[i] = pids.size() - 1;
                String book = tcols.text("TT_BOOK", i);
                if (book != null) {
                    if (!book.equals(prevBook)) {
                        books.putIfAbsent(book, new int[] {i, i + 1});
                        prevBook = book;
                    }
                    books.get(book)[1] = i + 1;
                }
            }
            Columns.Builder pc = new Columns.Builder(POSITION_TEXTS, POSITION_NUMBERS);
            int[] from = new int[pids.size()];
            int[] to = new int[pids.size()];
            for (int k = 0; k < pids.size(); k++) {
                from[k] = runs.get(k)[0];
                to[k] = runs.get(k)[1];
                String[] texts = positionTexts.get(pids.get(k));
                double[] nums = positionNumbers.get(pids.get(k));
                if (texts == null) {                                  // no record: from its first trade
                    int o = from[k];
                    texts = new String[POSITION_TEXTS.size()];
                    for (int c = 0; c < texts.length; c++) {
                        texts[c] = tcols.has(POSITION_TEXTS.get(c)) ? tcols.text(POSITION_TEXTS.get(c), o) : null;
                    }
                    double q = 0;
                    for (int o2 = from[k]; o2 < to[k]; o2++) {
                        q += Double.isNaN(tcols.number("QUANTITY", o2)) ? 0 : tcols.number("QUANTITY", o2);
                    }
                    nums = new double[] {q, Double.NaN, Double.NaN, to[k] - from[k]};
                }
                pc.add(texts, nums);
            }
            Map<String, Node> nodes = nodes(books);
            Map<String, double[]> agg = new ConcurrentHashMap<>();
            List<String> bookNames = new ArrayList<>(books.keySet());
            List<OrdinalSet> bookSets = bookNames.stream().map(b -> OrdinalSet.range(books.get(b)[0], books.get(b)[1])).toList();
            double[][] bookVecs = sums.sums(store, bookSets);
            for (int i = 0; i < bookNames.size(); i++) {
                agg.put(bookNames.get(i), bookVecs[i]);
            }
            for (Node node : nodes.values()) {
                if (node.parent() == null) {
                    sumUp(node, nodes, agg, store.width());
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new VarBook(this, store, tids, tcols, posOf, pids.toArray(new String[0]), from, to, pc.build(), nodes, books, agg, ms);
        }

        private Map<String, Node> nodes(Map<String, int[]> books) {
            Map<String, List<String>> children = new HashMap<>();
            for (String[] r : nodeRows) {
                if (r[4] != null) {
                    children.computeIfAbsent(r[4], k -> new ArrayList<>()).add(r[0]);
                }
            }
            Map<String, Node> out = new LinkedHashMap<>();
            for (String[] r : nodeRows) {
                List<String> bs = nodeBooks.getOrDefault(r[0], List.of()).stream().filter(books::containsKey).toList();
                OrdinalSet.Builder m = new OrdinalSet.Builder();
                for (String b : bs) {
                    m.addRange(books.get(b)[0], books.get(b)[1]);
                }
                out.put(r[0], new Node(r[0], r[1], r[2], r[3], r[4], List.copyOf(children.getOrDefault(r[0], List.of())), bs,
                        nodeLimits.getOrDefault(r[0], Double.NaN), m.build()));
            }
            return out;
        }

        private static double[] sumUp(Node n, Map<String, Node> nodes, Map<String, double[]> agg, int width) {
            double[] acc = new double[width];
            if (n.children().isEmpty()) {
                for (String b : n.books()) {
                    double[] v = agg.get(b);
                    if (v != null) {
                        VarMath.addInto(acc, v);
                    }
                }
            } else {
                for (String c : n.children()) {
                    Node cn = nodes.get(c);
                    if (cn != null) {
                        VarMath.addInto(acc, sumUp(cn, nodes, agg, width));
                    }
                }
            }
            agg.put(n.id(), acc);
            return acc;
        }

        /** Rows ordered by legal entity, desk, book, position and trade id. */
        private int[] sortOrder(Columns c, int n) {
            String[] keys = {"LEGAL_ENTITY", "DESK", "TT_BOOK", "POSITION_ID"};
            int[] idx = new int[n];
            for (int i = 0; i < n; i++) {
                idx[i] = i;
            }
            int[] tmp = new int[n];
            for (int width = 1; width < n; width *= 2) {
                for (int lo = 0; lo < n - width; lo += 2 * width) {
                    int mid = lo + width;
                    int hi = Math.min(lo + 2 * width, n);
                    int i = lo;
                    int j = mid;
                    int k = lo;
                    while (i < mid && j < hi) {
                        if (compare(c, keys, idx[i], idx[j]) <= 0) {
                            tmp[k++] = idx[i++];
                        } else {
                            tmp[k++] = idx[j++];
                        }
                    }
                    while (i < mid) {
                        tmp[k++] = idx[i++];
                    }
                    while (j < hi) {
                        tmp[k++] = idx[j++];
                    }
                    System.arraycopy(tmp, lo, idx, lo, hi - lo);
                }
            }
            return idx;
        }

        private int compare(Columns c, String[] keys, int a, int b) {
            for (String k : keys) {
                int ca = c.code(k, a);
                int cb = c.code(k, b);
                if (ca != cb) {
                    String sa = ca < 0 ? "" : c.dictionary(k)[ca];
                    String sb = cb < 0 ? "" : c.dictionary(k)[cb];
                    int r = sa.compareTo(sb);
                    if (r != 0) {
                        return r;
                    }
                }
            }
            return ids.get(a).compareTo(ids.get(b));
        }
    }
}
