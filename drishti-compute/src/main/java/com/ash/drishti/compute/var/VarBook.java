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

import com.ash.drishti.compute.vector.MappedMatrix;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.compute.vector.PermutedVectors;
import com.ash.drishti.compute.vector.RowSpill;
import com.ash.drishti.compute.vector.VectorStore;
import com.ash.drishti.compute.vector.Vectors;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One business date of the VaR engine in memory (VAR_GUIDE.md section 25): every trade's P&amp;L vector in a
 * {@link VectorStore}, ordered by the primary hierarchy (legal entity, desk, book, position, trade) so a book and a position are each
 * one run of ordinals; the trades' and the positions' attributes column by column; the hierarchies' nodes with their members; the
 * node aggregates (every book's and node's vector, summed once when the date is loaded). Positions are derived here from the trades'
 * {@code POSITION_ID} (set by the loader from the configured rules, {@link PositionRules}): each is one run of the sorted trades.
 *
 * <p>The vector grain is the trade: a position's vector is the sum of its trades' (a run of ordinals), so a trade, a position, a book
 * and any basket of trades or positions are all exact, and a long and a short in one position net in the sum. Immutable once built,
 * except the cache of position vectors; any number of requests read it at once.
 *
 * <p><b>Two tiers</b> (VAR_GUIDE.md section 26). <em>Heap</em>: every trade's vector in float64 {@code double[]} chunks. <em>Mapped</em>
 * (the default in the server): the load streams the rows to a local spill file, sums the aggregates from it in float64, writes the date's
 * hot file ({@link VarHotFile}: float32 position rows in hierarchy order, the trades of positions of several trades, the aggregates in
 * float64) and maps it; the heap keeps the aggregates, columns and links. Sums read float32 rows into float64 accumulators; a node's
 * figures come from its float64 aggregate, so they are the lake's.
 */
public final class VarBook {

    /** The trades' text columns the engine keeps (from the pnl-vector rows), in this order. */
    public static final List<String> TRADE_TEXTS = List.of("POSITION_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "PORTFOLIO",
            "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY", "COUNTERPARTY", "DIRECTION", "INSTRUMENT_ID", "SECURITY_ID", "INSTRUMENT", "STRATEGY",
            "TAGS", "POSITION_RULE", "PV_CURRENCY");
    public static final List<String> TRADE_NUMBERS = List.of("QUANTITY", "PV");
    /**
     * The positions' columns, derived from their trades when the date is built (VAR_GUIDE.md section 15.6): the first trade's instrument,
     * hierarchy, product and rule; the direction of the net quantity; the counterparty when every trade has the same one; the tags of
     * every trade; and the sums (signed quantity, market value from the trades' PV, notional as the gross quantity, trade counts).
     */
    public static final List<String> POSITION_TEXTS = List.of("INSTRUMENT_ID", "INSTRUMENT", "DIRECTION", "LEGAL_ENTITY", "DIVISION", "DESK",
            "TT_BOOK", "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY", "COUNTERPARTY", "STRATEGY", "TAGS", "STATUS", "SECURITY_ID",
            "POSITION_RULE", "POSITION_TYPE");
    public static final List<String> POSITION_NUMBERS = List.of("QUANTITY", "MARKET_VALUE", "NOTIONAL", "TRADE_COUNT", "LONG_TRADES",
            "SHORT_TRADES");

    /** A node of a hierarchy: its books and its members (the union of its books' runs). */
    public record Node(String id, String hierarchy, String level, String name, String parent, List<String> children, List<String> books,
            double limit, OrdinalSet members) {}

    /** The same trade id twice on one date: the date is refused whole (VAR_GUIDE.md section 20.3). */
    public static final class DuplicateTrade extends IllegalStateException {
        private final String tradeId;

        public DuplicateTrade(String tradeId) {
            super("the trade " + tradeId + " has two P&L vectors on one business date: one row per trade, date and scenario set");
            this.tradeId = tradeId;
        }

        public String tradeId() {
            return tradeId;
        }
    }

    /**
     * A mapped date's hot file: the file, its matrices (float32 positions; the trades of positions of several trades, or null), the
     * digest of the inputs it was built from, the server's own record (JSON) and when it was written.
     */
    public record Hot(Path file, MappedMatrix positions, MappedMatrix trades, String digest, String meta, long writtenAt) {}

    /**
     * Where a mapped load writes: the spill of the lake's rows (a temporary file, deleted after the build), the hot file being written
     * (in the folder of its final name, moved there when complete) and its final name, and the digest of the inputs.
     */
    public record HotTarget(Path spill, Path part, Path file, String digest) {}

    private final LocalDate businessDate;
    private final String scenarioSet;
    private final List<LocalDate> scenarioDates;
    private final Vectors store;
    private final Hot hot;
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
    private final Map<String, Integer> tradeUpper;
    private final Map<String, Integer> positionUpper;
    private final int failed;
    private final long loadMillis;
    private final Map<Integer, double[]> positionCache = new ConcurrentHashMap<>();
    private final CurrencyTotals totals;
    /** The morning book this one layers an intraday batch on (VAR_GUIDE.md section 8.4), or this book itself. */
    private final VarBook morning;
    /** The intraday layer, or null (a morning book). */
    private final VarIntraday layer;

    VarBook(LocalDate businessDate, String scenarioSet, List<LocalDate> scenarioDates, Vectors store, String[] tradeIds, Columns trades,
            int[] positionOf, String[] positionIds, int[] posFrom, int[] posTo, Columns positions, Map<String, Node> nodes,
            Map<String, int[]> bookRange, Map<String, double[]> aggregates, int failed, long loadMillis, Hot hot, CurrencyTotals totals) {
        this(businessDate, scenarioSet, scenarioDates, store, tradeIds, trades, positionOf, positionIds, posFrom, posTo, positions, nodes, bookRange,
                aggregates, failed, loadMillis, hot, totals, null, null);
    }

    /** A book with an intraday layer over {@code morning} ({@link VarIntraday}): its cancelled trades are in no index. */
    VarBook(LocalDate businessDate, String scenarioSet, List<LocalDate> scenarioDates, Vectors store, String[] tradeIds, Columns trades,
            int[] positionOf, String[] positionIds, int[] posFrom, int[] posTo, Columns positions, Map<String, Node> nodes,
            Map<String, int[]> bookRange, Map<String, double[]> aggregates, int failed, long loadMillis, Hot hot, CurrencyTotals totals,
            VarBook morning, VarIntraday layer) {
        this.totals = totals;
        this.morning = morning == null ? this : morning;
        this.layer = layer;
        this.businessDate = businessDate;
        this.scenarioSet = scenarioSet;
        this.scenarioDates = List.copyOf(scenarioDates);
        this.store = store;
        this.hot = hot;
        this.tradeIds = tradeIds;
        this.tradeOrdinal = new HashMap<>(tradeIds.length * 2);
        for (int i = 0; i < tradeIds.length; i++) {
            if (layer == null || !layer.cancelled(i)) {
                tradeOrdinal.put(tradeIds[i], i);
            }
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
        this.tradeUpper = upperIndex(tradeIds, layer == null ? null : layer.cancelledSet());
        this.positionUpper = upperIndex(positionIds, null);
        this.failed = failed;
        this.loadMillis = loadMillis;
    }

    public LocalDate businessDate() {
        return businessDate;
    }

    /**
     * Every node's per-trade-currency totals (VAR_GUIDE.md section 15.11): built with a USD book whose builder was given the date's FX
     * ({@link Builder#currencyRates}); null for a book built without (one in another reporting currency).
     */
    public CurrencyTotals currencyTotals() {
        return totals;
    }

    /** The morning book: this one without its intraday layer (the official captures read it, VAR_GUIDE.md section 8.4). */
    public VarBook morning() {
        return morning;
    }

    /** The intraday layer on the morning book, or null. */
    public VarIntraday intraday() {
        return layer;
    }

    // the arrays an intraday layer copies and extends (VarIntraday), never changed in place
    String[] tradeIdsArray() {
        return tradeIds;
    }

    int[] positionOfArray() {
        return positionOf;
    }

    String[] positionIdsArray() {
        return positionIds;
    }

    int[] posFromArray() {
        return posFrom;
    }

    int[] posToArray() {
        return posTo;
    }

    Map<String, int[]> bookRanges() {
        return bookRange;
    }

    /** The aggregates the plan reads (a morning book's: as loaded). */
    Map<String, double[]> morningAggregates() {
        return aggregates;
    }

    double[] morningAggregate(String key) {
        return aggregates.get(key);
    }

    /** True when trade {@code o} was cancelled by an intraday batch (its P&amp;L removed; it is in no scope). */
    public boolean cancelled(int o) {
        return layer != null && layer.cancelled(o);
    }

    public String scenarioSet() {
        return scenarioSet;
    }

    public List<LocalDate> scenarioDates() {
        return scenarioDates;
    }

    /** The trades' vectors (heap rows, or the hot file's rows served position by position). */
    public Vectors store() {
        return store;
    }

    /** {@code mapped} (the hot file) or {@code heap}. */
    public String tier() {
        return hot == null ? "heap" : "mapped";
    }

    /** The hot file of a mapped date, or null. */
    public Hot hot() {
        return hot;
    }

    /** Bytes of vectors the date holds: float64 rows on the heap, or the hot file's float32 matrices (mapped, resident when used). */
    public long vectorBytes() {
        return store.bytes();
    }

    /** Bytes the aggregates take on the heap (float64, one row per book and node). */
    public long aggregateBytes() {
        return (long) aggregates.size() * store.width() * Double.BYTES;
    }

    /** Unmaps a mapped date once no request reads it (its evicition); a heap date has nothing to release. */
    public void close() {
        if (hot != null) {
            hot.positions().close();
            if (hot.trades() != null) {
                hot.trades().close();
            }
        }
    }

    /** As {@link #close}, then deletes the hot file once both matrices are unmapped (a stale date, or the disk tier's eviction). */
    public void closeAndDelete() {
        if (hot == null) {
            return;
        }
        java.util.concurrent.atomic.AtomicInteger open = new java.util.concurrent.atomic.AtomicInteger(hot.trades() == null ? 1 : 2);
        Runnable delete = () -> {
            if (open.decrementAndGet() == 0) {
                try {
                    Files.deleteIfExists(hot.file());
                } catch (IOException e) {
                    // removed at the next start or shutdown
                }
            }
        };
        hot.positions().closeThen(delete);
        if (hot.trades() != null) {
            hot.trades().closeThen(delete);
        }
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

    /** Ids by their upper-case form, for a lookup that ignores case; two ids equal in upper case map to -1 (ambiguous). */
    private static Map<String, Integer> upperIndex(String[] ids, java.util.BitSet skip) {
        Map<String, Integer> out = new HashMap<>(ids.length * 2);
        for (int i = 0; i < ids.length; i++) {
            if (skip != null && skip.get(i)) {
                continue;
            }
            out.merge(ids[i].toUpperCase(java.util.Locale.ROOT), i, (a, x) -> -1);
        }
        return out;
    }

    public String tradeId(int ordinal) {
        return tradeIds[ordinal];
    }

    /** The ordinal of a trade, -1 when the date has no vector for it. */
    public int tradeOrdinal(String id) {
        return tradeOrdinal.getOrDefault(id, -1);
    }

    /**
     * The ordinal of a trade written in any case: the id as written when the date has it, else the one id equal to it ignoring case
     * (a booking system's ids are text and may be lower case; the scope keeps what was typed). -1 when none, or when two ids differ
     * only in case.
     */
    public int tradeOrdinalAnyCase(String id) {
        int o = tradeOrdinal(id);
        return o >= 0 ? o : tradeUpper.getOrDefault(id.toUpperCase(java.util.Locale.ROOT), -1);
    }

    /** A position's index written in any case, as {@link #tradeOrdinalAnyCase}. */
    public int positionIndexAnyCase(String id) {
        int p = positionIndex(id);
        return p >= 0 ? p : positionUpper.getOrDefault(id.toUpperCase(java.util.Locale.ROOT), -1);
    }

    /** The trades whose text column {@code column} holds one of {@code values}, compared ignoring case: a scope by a trade attribute. */
    public OrdinalSet tradesWhere(String column, java.util.Collection<String> values) {
        String[] dict = trades.dictionary(column);
        boolean[] wanted = new boolean[dict.length];
        boolean any = false;
        for (int c = 0; c < dict.length; c++) {
            for (String v : values) {
                if (dict[c].equalsIgnoreCase(v)) {
                    wanted[c] = true;
                    any = true;
                }
            }
        }
        OrdinalSet.Builder b = new OrdinalSet.Builder();
        if (any) {
            for (int o = 0; o < tradeIds.length; o++) {
                int code = trades.code(column, o);
                if (code >= 0 && wanted[code] && !cancelled(o)) {
                    b.add(o);
                }
            }
        }
        return b.build();
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
        OrdinalSet m = OrdinalSet.range(posFrom[index], posTo[index]);
        return layer == null ? m : layer.members(index, m);
    }

    public Columns positions() {
        return positions;
    }

    /**
     * A position's vector: the sum of its trades' (on the heap: cached, a few thousand values per position at most; mapped: its float32
     * row of the hot file, read when asked).
     */
    public double[] positionVector(int index) {
        if (layer != null) {
            double[] acc = new double[store.width()];
            positionMembers(index).forEachRun((f, t) -> store.addRangeInto(acc, f, t));
            return acc;
        }
        if (hot != null) {
            double[] v = new double[store.width()];
            hot.positions().copy(index, v);
            return v;
        }
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
        if (layer != null) {
            out.addAll(layer.positionsOfBook(book, r));
            return out;
        }
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
        double[] a = aggregates.get(nodeOrBook);
        return layer == null || a == null ? a : layer.withAdded(nodeOrBook, a);
    }

    /** The root of a hierarchy ({@code MGMT~BANK}), or null. */
    public Node root(String hierarchy) {
        return nodes.values().stream().filter(n -> n.hierarchy().equals(hierarchy) && n.parent() == null).findFirst().orElse(null);
    }

    /** Every ordinal of the date. */
    public OrdinalSet all() {
        OrdinalSet a = OrdinalSet.range(0, tradeIds.length);
        return layer == null ? a : a.andNot(layer.cancelledOrdinals());
    }

    /**
     * The sum of the vectors of {@code set}, planned (VAR_GUIDE.md section 25): from the top of the primary hierarchy down, a node
     * the set covers whole is its aggregate; a node it covers but for a few trades is the aggregate minus those; a node it does not touch
     * is skipped; a book it covers in part sums the set's runs in it. Whatever the plan, the vector is the same (to the bit for whole
     * numbers, within rounding for others); the cost is the number of aggregates and uncovered rows, not the size of the nodes.
     */
    public double[] vector(OrdinalSet set, ParallelSums sums) {
        if (layer != null && layer.added() > 0 && set.intersects(layer.addedOrdinals())) {
            // the trades added during the day are summed on their own; the morning's ordinals go through the plan
            double[] acc = plannedVector(set.andNot(layer.addedOrdinals()), sums);
            set.and(layer.addedOrdinals()).forEach(o -> store.addInto(acc, o));
            return acc;
        }
        return plannedVector(set, sums);
    }

    private double[] plannedVector(OrdinalSet set, ParallelSums sums) {
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

    /**
     * The nodes of the hierarchies from their rows ({@code id, hierarchy, level, name, parent}), books and limits: children from the
     * parents, each node's books those the date has trades in, its members the union of their runs.
     */
    static Map<String, Node> nodes(List<String[]> nodeRows, Map<String, List<String>> nodeBooks, Map<String, Double> nodeLimits,
            Map<String, int[]> books) {
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

    // ---------------------------------------------------------------------------------------------------------------------- building

    /**
     * Gathers one business date's rows in any order, then sorts them into hierarchy order and sums the aggregates: on the heap, or
     * (with a {@link HotTarget}) through a spill file into the date's hot file, which is then mapped.
     */
    public static final class Builder {
        private final LocalDate businessDate;
        private final String scenarioSet;
        private final List<LocalDate> scenarioDates;
        private final VectorStore.Builder vectors;
        private final RowSpill spill;
        private final HotTarget target;
        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        private final List<String> ids = new ArrayList<>();
        private final Columns.Builder tradeCols = new Columns.Builder(TRADE_TEXTS, TRADE_NUMBERS);
        private final java.util.Set<String> seen = new java.util.HashSet<>();
        private final List<String[]> nodeRows = new ArrayList<>();
        private final Map<String, List<String>> nodeBooks = new HashMap<>();
        private final Map<String, Double> nodeLimits = new HashMap<>();
        private int failed;
        private String batchId = "";
        private String meta = "";
        private java.util.function.Function<String, ScenarioFx.Rates> currencyRates;

        /** A date held on the heap, in float64 chunks of {@code rowsPerChunk} rows, within {@code budgetBytes} (8 bytes a value). */
        public Builder(LocalDate businessDate, String scenarioSet, List<LocalDate> scenarioDates, int rowsPerChunk, long budgetBytes) {
            this.businessDate = businessDate;
            this.scenarioSet = scenarioSet;
            this.scenarioDates = scenarioDates;
            this.vectors = new VectorStore.Builder(scenarioDates.size(), rowsPerChunk, budgetBytes);
            this.spill = null;
            this.target = null;
        }

        /**
         * A date written to its hot file and mapped: the rows go to {@code target.spill()} as they arrive, within {@code budgetBytes}
         * measured as the float32 hot copy (4 bytes a value).
         */
        public Builder(LocalDate businessDate, String scenarioSet, List<LocalDate> scenarioDates, long budgetBytes, HotTarget target) {
            this.businessDate = businessDate;
            this.scenarioSet = scenarioSet;
            this.scenarioDates = scenarioDates;
            this.vectors = null;
            this.target = target;
            this.spill = new RowSpill(target.spill(), scenarioDates.size(), budgetBytes, (long) scenarioDates.size() * Float.BYTES);
        }

        public int width() {
            return scenarioDates.size();
        }

        /** The run the vectors come from (written in the hot file's header). */
        public Builder batchId(String id) {
            this.batchId = id == null ? "" : id;
            return this;
        }

        /**
         * The date's FX by currency (null: none), so the build also sums every node's PVs per trade currency ({@link CurrencyTotals});
         * a book built without has no totals.
         */
        public Builder currencyRates(java.util.function.Function<String, ScenarioFx.Rates> rates) {
            this.currencyRates = rates;
            return this;
        }

        /** The server's own record of the date (JSON: what the load counted, the inputs' versions), kept in the hot file. */
        public Builder meta(String json) {
            this.meta = json == null ? "" : json;
            return this;
        }

        /**
         * A trade's vector and attributes ({@link #TRADE_TEXTS}, {@link #TRADE_NUMBERS} order); a row with no vector is counted failed.
         * The same trade id twice is refused ({@link DuplicateTrade}): the date would count it twice in every node. Rows may arrive from
         * several reader threads.
         */
        public void trade(String id, double[] values, int length, String[] texts, double[] numbers) {
            lock.lock();
            try {
                if (!seen.add(id)) {
                    throw new DuplicateTrade(id);
                }
                if (length < 0) {
                    failed++;
                    return;
                }
                if (spill != null) {
                    spill.add(values, length);
                } else {
                    vectors.add(values, length);
                }
                ids.add(id);
                tradeCols.add(texts, numbers);
            } finally {
                lock.unlock();
            }
        }

        /** Trades the batch had no vector for, counted as failed (a book built from another date's book carries its count over). */
        public Builder failedRows(int n) {
            lock.lock();
            try {
                failed += Math.max(0, n);
            } finally {
                lock.unlock();
            }
            return this;
        }

        /** A node of a hierarchy, with the books beneath it. */
        public void node(String id, String hierarchy, String level, String name, String parent, List<String> books, double limit) {
            nodeRows.add(new String[] {id, hierarchy, level, name, parent});
            nodeBooks.put(id, List.copyOf(books));
            nodeLimits.put(id, limit);
        }

        public int trades() {
            return ids.size();
        }

        /** Gives up a load that failed: the spill file is deleted. */
        public void discard() {
            if (spill != null) {
                spill.close();
            }
        }

        /** Sorts, permutes, indexes and sums; a mapped date also writes its hot file and maps it. */
        public VarBook build(ParallelSums sums) {
            long t0 = System.nanoTime();
            Columns cols = tradeCols.build();
            int n = ids.size();
            int[] order = sortOrder(cols, n);
            boolean sorted = true;
            for (int i = 0; i < n && sorted; i++) {
                sorted = order[i] == i;
            }
            Columns tcols = sorted ? cols : cols.permuted(order);
            String[] tids = new String[n];
            for (int i = 0; i < n; i++) {
                tids[i] = ids.get(order[i]);
            }
            // positions: runs of POSITION_ID in the sorted trades (a trade without one is a position of its own)
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
                derivePosition(pc, tcols, from[k], to[k]);
            }
            Map<String, Node> nodes = nodes(nodeRows, nodeBooks, nodeLimits, books);
            String[] positionIds = pids.toArray(new String[0]);
            Columns pcols = pc.build();
            if (spill == null) {
                VectorStore store = sorted ? vectors.build() : vectors.permuted(order);
                Map<String, double[]> agg = aggregates(store, books, nodes, sums);
                CurrencyTotals ct = currencyRates == null ? null : CurrencyTotals.build(store, tcols, books, nodes, currencyRates);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                return new VarBook(businessDate, scenarioSet, scenarioDates, store, tids, tcols, posOf, positionIds, from, to, pcols, nodes, books,
                        agg, failed, ms, null, ct);
            }
            try {
                MappedMatrix lake = spill.finish();
                Vectors rows = sorted ? lake : new PermutedVectors(lake, order);
                Map<String, double[]> agg = aggregates(rows, books, nodes, sums);
                CurrencyTotals ct = currencyRates == null ? null : CurrencyTotals.build(rows, tcols, books, nodes, currencyRates);
                VarHotFile.write(target.part(), new VarHotFile.Content(businessDate, scenarioSet, scenarioDates, tids, tcols, positionIds, from,
                        to, pcols, books, nodes, agg, failed, batchId, target.digest(), meta, ct), rows, sums);
                Files.move(target.part(), target.file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException("the hot file of " + businessDate + " could not be written: " + e.getMessage(), e);
            } finally {
                spill.close();
                try {
                    Files.deleteIfExists(target.part());
                } catch (IOException e) {
                    // the part file is in the date's folder, removed with it
                }
            }
            try {
                return VarHotFile.open(target.file(), (System.nanoTime() - t0) / 1_000_000);
            } catch (IOException e) {
                throw new UncheckedIOException("the hot file of " + businessDate + " could not be opened: " + e.getMessage(), e);
            }
        }

        /** Every book's vector (one pass per book run, on the pool), then every node's bottom-up from the roots; all in float64. */
        private static Map<String, double[]> aggregates(Vectors store, Map<String, int[]> books, Map<String, Node> nodes, ParallelSums sums) {
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
            return agg;
        }

        /**
         * A position's columns from its run of trades (VAR_GUIDE.md section 15.6: Drishti derives positions; nothing upstream sends them): the
         * first trade's instrument, hierarchy, product and rule; the direction of the net quantity; the counterparty when every trade
         * has the same one (else empty); the tags of every trade; the signed and gross quantities, the market value (the trades' PV)
         * and the counts.
         */
        private static void derivePosition(Columns.Builder pc, Columns t, int from, int to) {
            int[] m = new int[to - from];
            for (int i = 0; i < m.length; i++) {
                m[i] = from + i;
            }
            Object[] row = positionRow(t, m);
            pc.add((String[]) row[0], (double[]) row[1]);
        }

        /** A position's texts and numbers ({@link #POSITION_TEXTS}, {@link #POSITION_NUMBERS}) from its member trades (in order). */
        static Object[] positionRow(Columns t, int[] members) {
            int from = members.length == 0 ? 0 : members[0];
            String[] texts = new String[POSITION_TEXTS.size()];
            for (int c = 0; c < texts.length; c++) {
                String col = POSITION_TEXTS.get(c);
                texts[c] = t.has(col) ? t.text(col, from) : null;
            }
            double q = 0;
            double mv = 0;
            double gross = 0;
            int longs = 0;
            int shorts = 0;
            boolean anyPv = false;
            String cpty = t.text("COUNTERPARTY", from);
            java.util.TreeSet<String> tags = new java.util.TreeSet<>();
            for (int o : members) {
                double tq = t.number("QUANTITY", o);
                if (!Double.isNaN(tq)) {
                    q += tq;
                    gross += Math.abs(tq);
                    longs += tq > 0 ? 1 : 0;
                    shorts += tq < 0 ? 1 : 0;
                }
                double pv = t.number("PV", o);
                if (!Double.isNaN(pv)) {
                    mv += pv;
                    anyPv = true;
                }
                if (cpty != null && !cpty.equals(t.text("COUNTERPARTY", o))) {
                    cpty = "";
                }
                String tg = t.text("TAGS", o);
                if (tg != null && !tg.isEmpty()) {
                    tags.addAll(List.of(tg.split("\\|")));
                }
            }
            if (texts[POSITION_TEXTS.indexOf("INSTRUMENT_ID")] == null) {
                texts[POSITION_TEXTS.indexOf("INSTRUMENT_ID")] = t.text("POSITION_ID", from);
            }
            texts[POSITION_TEXTS.indexOf("DIRECTION")] = q > 0 ? "LONG" : q < 0 ? "SHORT" : "FLAT";
            texts[POSITION_TEXTS.indexOf("COUNTERPARTY")] = cpty;
            texts[POSITION_TEXTS.indexOf("TAGS")] = String.join("|", tags);
            texts[POSITION_TEXTS.indexOf("STATUS")] = q == 0 ? "flat" : "open";
            texts[POSITION_TEXTS.indexOf("POSITION_TYPE")] = members.length > 1 ? "fungible" : "single-trade";
            return new Object[] {texts, new double[] {q, anyPv ? mv : Double.NaN, gross, members.length, longs, shorts}};
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
