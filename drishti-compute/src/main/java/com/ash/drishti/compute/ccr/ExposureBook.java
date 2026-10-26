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

import com.ash.drishti.compute.vector.MappedMatrix;
import com.ash.drishti.compute.vector.Vectors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One business date of the exposure engine in memory (EXP.md section 7.1): the run's grid, every netting set with its CSA terms and its
 * value, exposure and negative exposure matrices (float32 rows of the date's memory-mapped hot file, or float64 rows on the heap), and
 * the index of the trades (ordered by netting set, then trade id, so a set's trades are one run of ordinals). Trade paths are not kept:
 * they are read from the lake when a scope needs them ({@link TradePaths}). Built by {@link Builder} from the date's rows as the lake
 * streams them, or reopened from the hot file. Safe for any number of concurrent readers.
 */
public final class ExposureBook implements AutoCloseable {

    /**
     * A netting set.
     *
     * @param from its first trade ordinal, {@code to} one past its last
     * @param v0 its value today (the sum of its trades' MtM on the business date)
     * @param lifeYears its longest remaining maturity in years
     * @param flags what the load found about it ({@code incomplete}, {@code MPOR_MISMATCH}, {@code standalone} ...)
     */
    public record SetInfo(String id, String counterparty, String legalEntity, CsaTerms csa, double v0, int from, int to, double lifeYears,
            List<String> flags) {}

    /** A trade of the date (its row's columns). */
    public record TradeInfo(String id, int set, String counterparty, String legalEntity, String desk, String book, String assetClass,
            String product, String sourceSystem, String maturity, double notional, double mtm0, double lifeYears) {}

    /** Where the trades' paths come from when a scope needs them (the lake): {@code G x P} per trade, an absent row zero. */
    @FunctionalInterface
    public interface TradePaths {
        Map<String, double[]> read(java.util.Collection<String> tradeIds);
    }

    /** What the builder needs that the rows do not carry: each set's CSA terms and holdings, each trade's MtM today, a set's flags. */
    public interface Reference {
        CsaTerms terms(String set);

        /** The trade's MtM on the business date; NaN when unknown (then its paths' mean at the first grid date stands in). */
        double mtm0(String trade);

        default List<String> flags(String set) {
            return List.of();
        }
    }

    private final ExposureGrid grid;
    private final int paths;
    private final String runId;
    private final String batchId;
    private final Map<String, Object> run;
    private final List<SetInfo> sets;
    private final List<TradeInfo> trades;
    private final Map<String, Integer> setIndex = new HashMap<>();
    private final Map<String, Integer> tradeIndex = new HashMap<>();
    private final Map<String, Integer> tradeIndexLower = new HashMap<>();
    private final Vectors v;
    private final Vectors e;
    private final Vectors n;
    private final ExposureMath.Model model;
    private final String tier;
    private final ExposureHotFile.Opened hot;
    private final Map<String, Object> intake;
    /** Answers the calc keeps per canonical scope and parameters (the date's own cache: dropped with it). */
    final Map<String, Object> memo = new ConcurrentHashMap<>();

    ExposureBook(ExposureGrid grid, int paths, String runId, String batchId, Map<String, Object> run, List<SetInfo> sets, List<TradeInfo> trades,
            Vectors v, Vectors e, Vectors n, ExposureMath.Model model, String tier, ExposureHotFile.Opened hot, Map<String, Object> intake) {
        this.grid = grid;
        this.paths = paths;
        this.runId = runId;
        this.batchId = batchId;
        this.run = run == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(run));
        this.sets = List.copyOf(sets);
        this.trades = List.copyOf(trades);
        for (int s = 0; s < sets.size(); s++) {
            setIndex.put(sets.get(s).id(), s);
        }
        for (int t = 0; t < trades.size(); t++) {
            tradeIndex.put(trades.get(t).id(), t);
            tradeIndexLower.putIfAbsent(trades.get(t).id().toLowerCase(Locale.ROOT), t);
        }
        this.v = v;
        this.e = e;
        this.n = n;
        this.model = model;
        this.tier = tier;
        this.hot = hot;
        this.intake = intake == null ? Map.of() : intake;
    }

    public ExposureGrid grid() {
        return grid;
    }

    public LocalDate businessDate() {
        return grid.businessDate();
    }

    /** P, the paths of the run. */
    public int paths() {
        return paths;
    }

    public String runId() {
        return runId;
    }

    public String batchId() {
        return batchId;
    }

    /** The run's record (its {@code simulation-run} row). */
    public Map<String, Object> run() {
        return run;
    }

    public List<SetInfo> sets() {
        return sets;
    }

    public SetInfo set(int s) {
        return sets.get(s);
    }

    public int setOrdinal(String id) {
        Integer i = id == null ? null : setIndex.get(id.toUpperCase(Locale.ROOT));
        if (i == null && id != null) {
            i = setIndex.get(id);
        }
        return i == null ? -1 : i;
    }

    public List<TradeInfo> trades() {
        return trades;
    }

    public TradeInfo trade(int t) {
        return trades.get(t);
    }

    /** A trade's ordinal by its id, exactly or ignoring case (a booking system's ids are text); -1 when the date has none. */
    public int tradeOrdinal(String id) {
        Integer i = tradeIndex.get(id);
        if (i == null && id != null) {
            i = tradeIndexLower.get(id.toLowerCase(Locale.ROOT));
        }
        return i == null ? -1 : i;
    }

    /** The default collateral model the exposure matrices were built with. */
    public ExposureMath.Model model() {
        return model;
    }

    /** {@code mapped} (the hot file's float32 rows) or {@code heap} (float64). */
    public String tier() {
        return tier;
    }

    public ExposureHotFile.Opened hot() {
        return hot;
    }

    /** What the load counted (rows rejected, trades incomplete, failed, proxied ...). */
    public Map<String, Object> intake() {
        return intake;
    }

    /** A set's value matrix, {@code G x P}, in double. */
    public double[] value(int s) {
        double[] out = new double[grid.size() * paths];
        rows(v, s * grid.size(), grid.size(), out);
        return out;
    }

    /** {@code acc += E} of set s ({@code G_primary x P}). */
    public void addExposure(int s, double[] acc) {
        addRows(e, s * grid.primaryCount(), grid.primaryCount(), acc);
    }

    /** {@code acc += N} of set s. */
    public void addNegative(int s, double[] acc) {
        addRows(n, s * grid.primaryCount(), grid.primaryCount(), acc);
    }

    /** A set's exposure matrix ({@code G_primary x P}) under the default model. */
    public double[] exposure(int s) {
        double[] out = new double[grid.primaryCount() * paths];
        addExposure(s, out);
        return out;
    }

    /** {@code acc += max(V, 0)} of set s at the primary dates (its exposure without collateral). */
    public void addPositive(int s, double[] acc) {
        double[] row = new double[paths];
        int base = s * grid.size();
        for (int i = 0; i < grid.primaryCount(); i++) {
            v.copy(base + grid.primaryAt(i), row);
            for (int j = 0; j < paths; j++) {
                acc[i * paths + j] += Math.max(row[j], 0);
            }
        }
    }

    private void rows(Vectors m, int first, int count, double[] out) {
        double[] row = new double[paths];
        for (int r = 0; r < count; r++) {
            m.copy(first + r, row);
            System.arraycopy(row, 0, out, r * paths, paths);
        }
    }

    private void addRows(Vectors m, int first, int count, double[] acc) {
        double[] row = new double[paths];
        for (int r = 0; r < count; r++) {
            m.copy(first + r, row);
            int off = r * paths;
            for (int j = 0; j < paths; j++) {
                acc[off + j] += row[j];
            }
        }
    }

    /** Bytes the matrices take where they are kept. */
    public long matrixBytes() {
        return v.bytes() + e.bytes() + n.bytes();
    }

    @Override
    public void close() {
        if (hot != null) {
            hot.v().close();
            hot.e().close();
            hot.n().close();
        }
    }

    /** Unmaps the hot file, then deletes it (a restated date: its inputs changed). */
    public void closeAndDelete() {
        if (hot == null) {
            return;
        }
        hot.v().close();
        hot.e().close();
        hot.n().closeThen(() -> {
            try {
                Files.deleteIfExists(hot.file());
            } catch (IOException ex) {
                // removed at the next start
            }
        });
    }

    // ------------------------------------------------------------------------------------------------------------- building a date

    /** Raised when a date's matrices would exceed the memory budget (never loaded in part). */
    public static final class BudgetExceeded extends IllegalStateException {
        public BudgetExceeded(String m) {
            super(m);
        }
    }

    /**
     * Builds a date from its rows as the lake streams them (in any order): each row's values added into its netting set's value matrix
     * in double; at the end each set's collateral, exposure and negative exposure under the default model, then the hot file (or the
     * heap). A trade with no netting set is a set of its own ({@code TRD~<trade>}, standalone).
     */
    public static final class Builder {
        private final ExposureGrid grid;
        private final int paths;
        private final String runId;
        private final String batchId;
        private final Map<String, Object> run;
        private final ExposureMath.Model model;
        private final long budgetBytes;
        private final Map<String, double[]> values = new TreeMap<>();
        private final Map<String, String[]> setCols = new HashMap<>();
        private final Map<String, TradeRow> tradeRows = new HashMap<>();
        private long bytes;

        private static final class TradeRow {
            String set;
            String[] cols;
            String maturity;
            double notional;
            int lastIndex = -1;
            double firstMean = Double.NaN;
            final java.util.BitSet seen = new java.util.BitSet();
        }

        public Builder(ExposureGrid grid, int paths, String runId, String batchId, Map<String, Object> run, ExposureMath.Model model, long budgetBytes) {
            this.grid = grid;
            this.paths = paths;
            this.runId = runId;
            this.batchId = batchId;
            this.run = run;
            this.model = model;
            this.budgetBytes = budgetBytes <= 0 ? Long.MAX_VALUE : budgetBytes;
        }

        public int paths() {
            return paths;
        }

        /**
         * One row: a trade's values at grid index k.
         *
         * @param cols the trade's columns: counterparty, legal entity, desk, book, asset class, product, source system
         */
        public void row(String trade, String nettingSet, String[] cols, String maturity, double notional, int k, double[] values, int length) {
            String set = nettingSet == null || nettingSet.isBlank() ? "TRD~" + trade : nettingSet.trim();
            TradeRow t = tradeRows.computeIfAbsent(trade, x -> new TradeRow());
            if (t.set == null) {
                t.set = set;
                t.cols = cols.clone();
                t.maturity = maturity;
                t.notional = notional;
            } else if (!t.set.equals(set)) {
                throw new IllegalArgumentException("the trade " + trade + " is in two netting sets on one date (" + t.set + ", " + set + ")");
            }
            if (t.seen.get(k)) {
                throw new IllegalArgumentException("the trade " + trade + " has two rows of grid index " + k + " on one date");
            }
            t.seen.set(k);
            t.lastIndex = Math.max(t.lastIndex, k);
            setCols.putIfAbsent(set, new String[] {cols[0], cols[1]});
            double[] acc = this.values.get(set);
            if (acc == null) {
                long need = (long) grid.size() * paths * Double.BYTES;
                if (bytes + need > budgetBytes) {
                    throw new BudgetExceeded("the netting sets' value matrices of " + grid.businessDate() + " need more than " + budgetBytes / (1 << 20)
                            + " MB");
                }
                bytes += need;
                acc = new double[grid.size() * paths];
                this.values.put(set, acc);
            }
            int off = k * paths;
            for (int j = 0; j < length; j++) {
                acc[off + j] += values[j];
            }
            if (k == 0) {
                double s = 0;
                for (int j = 0; j < length; j++) {
                    s += values[j];
                }
                t.firstMean = length == 0 ? 0 : s / length;
            }
        }

        /** A trade whose row failed (no values): it still belongs to its set (counted, flagged by the caller). */
        public void failed(String trade, String nettingSet, String[] cols, String maturity, double notional) {
            String set = nettingSet == null || nettingSet.isBlank() ? "TRD~" + trade : nettingSet.trim();
            TradeRow t = tradeRows.computeIfAbsent(trade, x -> new TradeRow());
            if (t.set == null) {
                t.set = set;
                t.cols = cols.clone();
                t.maturity = maturity;
                t.notional = notional;
            }
            setCols.putIfAbsent(set, new String[] {cols[0], cols[1]});
            if (!this.values.containsKey(set)) {
                bytes += (long) grid.size() * paths * Double.BYTES;
                this.values.put(set, new double[grid.size() * paths]);
            }
        }

        public int trades() {
            return tradeRows.size();
        }

        public int sets() {
            return values.size();
        }

        /** Builds the date: into a hot file at {@code hotFile} (mapped float32), or on the heap (float64) when it is null. */
        public ExposureBook build(Reference ref, Path hotFile, String digest, Map<String, Object> intake) throws IOException {
            List<String> setIds = new ArrayList<>(values.keySet());
            Map<String, List<String>> members = new HashMap<>();
            tradeRows.forEach((id, t) -> members.computeIfAbsent(t.set, x -> new ArrayList<>()).add(id));
            List<SetInfo> setInfos = new ArrayList<>();
            List<TradeInfo> tradeInfos = new ArrayList<>();
            List<double[][]> rows = new ArrayList<>();
            int p = paths;
            for (int s = 0; s < setIds.size(); s++) {
                String id = setIds.get(s);
                List<String> ts = members.getOrDefault(id, new ArrayList<>());
                Collections.sort(ts);
                int from = tradeInfos.size();
                double v0 = 0;
                double life = 0;
                for (String tid : ts) {
                    TradeRow t = tradeRows.get(tid);
                    double m0 = ref.mtm0(tid);
                    if (Double.isNaN(m0)) {
                        m0 = Double.isNaN(t.firstMean) ? 0 : t.firstMean;
                    }
                    double tl = lifeOf(t);
                    life = Math.max(life, tl);
                    v0 += m0;
                    tradeInfos.add(new TradeInfo(tid, s, t.cols[0], t.cols[1], t.cols[2], t.cols[3], t.cols[4], t.cols[5], t.cols[6], t.maturity,
                            t.notional, m0, tl));
                }
                CsaTerms csa = id.startsWith("TRD~") ? CsaTerms.NONE : ref.terms(id);
                List<String> flags = new ArrayList<>(ref.flags(id));
                if (id.startsWith("TRD~")) {
                    flags.add("standalone");
                }
                String[] sc = setCols.getOrDefault(id, new String[2]);
                setInfos.add(new SetInfo(id, sc[0], sc[1], csa == null ? CsaTerms.NONE : csa, v0, from, tradeInfos.size(), life, List.copyOf(flags)));
                double[] vv = values.get(id);
                double[][] enc = ExposureMath.exposure(vv, grid, p, csa == null ? CsaTerms.NONE : csa, model);
                rows.add(new double[][] {vv, enc[0], enc[1]});
            }
            if (hotFile == null) {
                int g = grid.size();
                int gp = grid.primaryCount();
                Dense dv = new Dense(setIds.size() * g, p);
                Dense de = new Dense(setIds.size() * gp, p);
                Dense dn = new Dense(setIds.size() * gp, p);
                for (int s = 0; s < rows.size(); s++) {
                    dv.put(s * g, rows.get(s)[0]);
                    de.put(s * gp, rows.get(s)[1]);
                    dn.put(s * gp, rows.get(s)[2]);
                }
                return new ExposureBook(grid, p, runId, batchId, run, setInfos, tradeInfos, dv, de, dn, model, "heap", null, intake);
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("grid", grid.describe());
            meta.put("run", run);
            meta.put("model", model.text());
            meta.put("intake", intake);
            meta.put("sets", setInfos.stream().map(Builder::describe).toList());
            meta.put("trades", tradeInfos.stream().map(Builder::describe).toList());
            ExposureHotFile.write(hotFile, new ExposureHotFile.Header(ExposureHotFile.VERSION, grid.businessDate(), p, grid.size(), grid.primaryCount(),
                    setIds.size(), runId, digest, batchId), meta, rows);
            return open(hotFile);
        }

        private double lifeOf(TradeRow t) {
            if (t.maturity != null && t.maturity.length() >= 10) {
                try {
                    return Math.max(0, ChronoUnit.DAYS.between(grid.businessDate(), LocalDate.parse(t.maturity.substring(0, 10))) / 365.0);
                } catch (java.time.format.DateTimeParseException ex) {
                    // the last grid date with a row instead
                }
            }
            return t.lastIndex < 0 ? 0 : grid.time(t.lastIndex);
        }

        private static Map<String, Object> describe(SetInfo s) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("counterparty", s.counterparty());
            m.put("legalEntity", s.legalEntity());
            m.put("csa", s.csa());
            m.put("v0", s.v0());
            m.put("from", s.from());
            m.put("to", s.to());
            m.put("lifeYears", s.lifeYears());
            m.put("flags", s.flags());
            return m;
        }

        private static Map<String, Object> describe(TradeInfo t) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("set", t.set());
            m.put("counterparty", t.counterparty());
            m.put("legalEntity", t.legalEntity());
            m.put("desk", t.desk());
            m.put("book", t.book());
            m.put("assetClass", t.assetClass());
            m.put("product", t.product());
            m.put("sourceSystem", t.sourceSystem());
            m.put("maturity", t.maturity());
            m.put("notional", t.notional());
            m.put("mtm0", t.mtm0());
            m.put("lifeYears", t.lifeYears());
            return m;
        }
    }

    /** A date reopened from its hot file (mapped; no lake read). */
    @SuppressWarnings("unchecked")
    public static ExposureBook open(Path hotFile) throws IOException {
        ExposureHotFile.Opened o = ExposureHotFile.open(hotFile);
        Map<String, Object> m = o.meta();
        ExposureGrid grid = ExposureGrid.fromDescription((Map<String, Object>) m.get("grid"));
        List<SetInfo> sets = new ArrayList<>();
        for (Object x : (List<Object>) m.get("sets")) {
            Map<String, Object> s = (Map<String, Object>) x;
            Map<String, Object> c = (Map<String, Object>) s.get("csa");
            CsaTerms csa = c == null ? CsaTerms.NONE : new CsaTerms((String) c.get("csaId"), num(c.get("thThem")), num(c.get("thUs")), num(c.get("mtaThem")),
                    num(c.get("mtaUs")), num(c.get("ia")), num(c.get("rounding")), (int) num(c.get("mporDays")), (int) num(c.get("frequencyDays")),
                    Boolean.TRUE.equals(c.get("margined")), num(c.get("heldToday")), num(c.get("imHeld")));
            sets.add(new SetInfo((String) s.get("id"), (String) s.get("counterparty"), (String) s.get("legalEntity"), csa, num(s.get("v0")),
                    (int) num(s.get("from")), (int) num(s.get("to")), num(s.get("lifeYears")), (List<String>) s.get("flags")));
        }
        List<TradeInfo> trades = new ArrayList<>();
        for (Object x : (List<Object>) m.get("trades")) {
            Map<String, Object> t = (Map<String, Object>) x;
            trades.add(new TradeInfo((String) t.get("id"), (int) num(t.get("set")), (String) t.get("counterparty"), (String) t.get("legalEntity"),
                    (String) t.get("desk"), (String) t.get("book"), (String) t.get("assetClass"), (String) t.get("product"), (String) t.get("sourceSystem"),
                    (String) t.get("maturity"), num(t.get("notional")), num(t.get("mtm0")), num(t.get("lifeYears"))));
        }
        ExposureHotFile.Header h = o.header();
        return new ExposureBook(grid, h.paths(), h.runId(), h.batchId(), (Map<String, Object>) m.get("run"), sets, trades, o.v(), o.e(), o.n(),
                ExposureMath.Model.of(String.valueOf(m.get("model"))), "mapped", o, (Map<String, Object>) m.get("intake"));
    }

    private static double num(Object o) {
        return o instanceof Number x ? x.doubleValue() : 0;
    }

    /** Rows of one width in float64 on the heap. */
    static final class Dense implements Vectors {
        private final double[] data;
        private final int rows;
        private final int width;

        Dense(int rows, int width) {
            this.rows = rows;
            this.width = width;
            this.data = new double[rows * width];
        }

        void put(int firstRow, double[] values) {
            System.arraycopy(values, 0, data, firstRow * width, values.length);
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int rows() {
            return rows;
        }

        @Override
        public int chunkCount() {
            return 1;
        }

        @Override
        public long bytes() {
            return (long) data.length * Double.BYTES;
        }

        @Override
        public double value(int row, int j) {
            return data[row * width + j];
        }

        @Override
        public void copy(int row, double[] out) {
            System.arraycopy(data, row * width, out, 0, width);
        }

        @Override
        public void addInto(double[] acc, int row) {
            addRangeInto(acc, row, row + 1);
        }

        @Override
        public void addRangeInto(double[] acc, int from, int to) {
            for (int r = from; r < to; r++) {
                int off = r * width;
                for (int j = 0; j < width; j++) {
                    acc[j] += data[off + j];
                }
            }
        }
    }

    /** Whether the matrices are a mapped file's. */
    public boolean mapped() {
        return v instanceof MappedMatrix;
    }
}
