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

import com.ash.drishti.compute.vector.ShardedVectors;
import com.ash.drishti.compute.vector.Vectors;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A sharded business date put together on one member (VAR_SHARDING.md section 5): the shards' books, each built by its owners from the
 * shard's rows alone, one after the other in key order. Because a shard is a range of the hot file's own order (legal entity, desk), the
 * shards' trades side by side are the whole date's trades in the order one server would sort them; the positions, books, cells and nodes
 * are then derived by the same code as a whole date's ({@link Cells#layout}, {@link Cells#derive}, {@link VarBook#nodes}), every book's and
 * cell's aggregate is the one its shard summed (float64, from the lake's rows), and every node's is summed from them bottom-up as
 * {@link VarBook.Builder} does: a node's figures are the unsharded date's to the bit. The rows stay where they are: the shards held here
 * are read from their mapped files, the others summed by their owners ({@link ShardedVectors}).
 *
 * <p>A date whose data does not cut cleanly is refused rather than answered differently: a book (or a cell) whose trades sit under two
 * shards (one book under two desks), or, with cells, one position id under two shards.
 */
public final class ShardAssembly {

    /** A date cannot be put together from its shards (the data does not cut along the shard key); the reason in words. */
    public static final class NotShardable extends IllegalStateException {
        public NotShardable(String why) {
            super(why);
        }
    }

    /**
     * One shard's book as the assembly needs it: from a shard book held here ({@link #of}) or from the skeleton an owner sent
     * ({@link ShardSkeleton#read}).
     *
     * @param shard its index in the date's shard map
     * @param digest the digest of the inputs it was built from (its hot file's)
     * @param meta the server's record of its load (JSON)
     * @param range its range in the shard map (JSON, the {@code RANG} section)
     * @param units its books' and cells' runs, in its own ordinals
     * @param aggregates its books', cells' and nodes' vectors (float64)
     * @param rows its rows when held here, else null
     */
    public record Part(int shard, String digest, String meta, String range, String scenarioSet, List<LocalDate> scenarioDates,
            String[] tradeIds, Columns trades, Map<String, int[]> units, Map<String, double[]> aggregates, int failed, int split,
            List<String> cellColumns, CurrencyTotals totals, Vectors rows) {

        public int size() {
            return tradeIds.length;
        }

        /** A shard book built or opened here (its rows mapped from its hot file). */
        public static Part of(int shard, VarBook b) {
            VarBook.Hot h = b.hot();
            return new Part(shard, h == null ? "" : h.digest(), h == null ? "" : h.meta(), h == null ? null : h.range(), b.scenarioSet(),
                    b.scenarioDates(), b.tradeIdsArray(), b.trades(), b.bookRanges(), b.morningAggregates(), b.failed(), b.splitPositions(),
                    b.cellColumns(), b.currencyTotals(), b.store());
        }

        /** The same part without its rows (a shard this member does not hold). */
        public Part withoutRows() {
            return new Part(shard, digest, meta, range, scenarioSet, scenarioDates, tradeIds, trades, units, aggregates, failed, split, cellColumns,
                    totals, null);
        }
    }

    /**
     * What the date's nodes are built from, as {@link VarBook.Builder} takes them: the hierarchy rows ({@code id, hierarchy, level, name,
     * parent}) with their books and limits, and the hierarchies built from the trades' columns with their limits.
     */
    public record Nodes(List<String[]> rows, Map<String, List<String>> books, Map<String, Double> limits, List<HierarchySpec> columnSpecs,
            Map<String, Double> columnLimits) {

        /** Collects the nodes of a book as its builder did (a reporting currency's book: the USD book's nodes, limits converted). */
        public static Nodes of(VarBook b, double limitDivisor) {
            List<String[]> rows = new ArrayList<>();
            Map<String, List<String>> books = new HashMap<>();
            Map<String, Double> limits = new HashMap<>();
            for (VarBook.Node n : b.nodes().values()) {
                rows.add(new String[] {n.id(), n.hierarchy(), n.level(), n.name(), n.parent()});
                books.put(n.id(), n.books());
                limits.put(n.id(), Double.isNaN(n.limit()) ? Double.NaN : n.limit() / limitDivisor);
            }
            return new Nodes(rows, books, limits, List.of(), Map.of());
        }
    }

    private ShardAssembly() {}

    /**
     * The date from its shards, in shard order.
     *
     * @param remote how the shards not held here are summed
     * @param loadMillis what putting the date together took
     */
    public static VarBook assemble(LocalDate date, List<Part> parts, Nodes nodeInputs, ShardedVectors.Remote remote, long loadMillis) {
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("a date has at least one shard");
        }
        Part head = parts.get(0);
        for (Part p : parts) {
            if (!Objects.equals(p.scenarioSet(), head.scenarioSet()) || !p.scenarioDates().equals(head.scenarioDates())) {
                throw new NotShardable("shard " + p.shard() + " was built with the scenario set " + p.scenarioSet() + ", shard " + head.shard()
                        + " with " + head.scenarioSet() + ": one run per date");
            }
            if (!p.cellColumns().equals(head.cellColumns())) {
                throw new NotShardable("shard " + p.shard() + " has the cell columns " + p.cellColumns() + ", shard " + head.shard() + " "
                        + head.cellColumns() + ": the shards were built under different hierarchies");
            }
        }
        int width = head.scenarioDates().size();
        List<String> cellColumns = head.cellColumns();
        // the trades, shard after shard
        int n = 0;
        for (Part p : parts) {
            n += p.size();
        }
        String[] tids = new String[n];
        int[] start = new int[parts.size()];
        java.util.Set<String> seen = new java.util.HashSet<>(n * 2);
        int at = 0;
        for (int k = 0; k < parts.size(); k++) {
            start[k] = at;
            for (String id : parts.get(k).tradeIds()) {
                if (!seen.add(id)) {
                    throw new VarBook.DuplicateTrade(id);              // as one server refuses it: the trade would count twice
                }
                tids[at++] = id;
            }
        }
        Columns tcols = Columns.concat(parts.stream().map(Part::trades).toList());
        checkOrder(parts, start, tcols, tids, cellColumns);
        // the runs, positions and cells exactly as a whole date's
        Cells.Layout lay = Cells.layout(tcols, tids, cellColumns);
        if (lay.renamed() != null) {
            String pid = null;
            for (String r : lay.renamed()) {
                if (r != null) {
                    pid = r;
                    break;
                }
            }
            throw new NotShardable("the position " + pid + " has trades under two shards (two desks) and a hierarchy built from columns: the "
                    + "date is not sharded along legal entity and desk; shard it by legal entity only, or switch sharding off");
        }
        Map<String, int[]> units = new LinkedHashMap<>(lay.books());
        units.putAll(lay.cells());
        Map<String, double[]> agg = new ConcurrentHashMap<>();
        for (Map.Entry<String, int[]> e : units.entrySet()) {
            agg.put(e.getKey(), unitAggregate(e.getKey(), e.getValue(), parts, start));
        }
        List<String> pids = lay.positionIds();
        List<int[]> runs = lay.runs();
        Columns.Builder pc = new Columns.Builder(VarBook.POSITION_TEXTS, VarBook.POSITION_NUMBERS);
        int[] from = new int[pids.size()];
        int[] to = new int[pids.size()];
        for (int k = 0; k < pids.size(); k++) {
            from[k] = runs.get(k)[0];
            to[k] = runs.get(k)[1];
            VarBook.Builder.derivePosition(pc, tcols, from[k], to[k]);
        }
        List<String[]> rows = new ArrayList<>(nodeInputs.rows());
        Map<String, List<String>> nodeUnits = new HashMap<>(nodeInputs.books());
        Map<String, Double> limits = new HashMap<>(nodeInputs.limits());
        for (HierarchySpec s : nodeInputs.columnSpecs()) {
            Cells.derive(s, tcols, cellColumns.isEmpty() ? lay.books() : lay.cells(), nodeInputs.columnLimits(), rows, nodeUnits, limits);
        }
        Map<String, VarBook.Node> nodes = VarBook.nodes(rows, nodeUnits, limits, units);
        for (VarBook.Node node : nodes.values()) {
            if (node.parent() == null) {
                VarBook.Builder.sumUp(node, nodes, agg, width);       // bottom-up, as one server sums them: a node's vector to the bit
            }
        }
        int failed = 0;
        int split = 0;
        List<CurrencyTotals> totals = new ArrayList<>();
        List<ShardedVectors.Part> store = new ArrayList<>();
        for (int k = 0; k < parts.size(); k++) {
            Part p = parts.get(k);
            failed += p.failed();
            split += p.split();
            totals.add(p.totals());
            store.add(new ShardedVectors.Part(p.shard(), start[k], start[k] + p.size(), p.rows()));
        }
        CurrencyTotals ct = totals.stream().allMatch(Objects::isNull) ? null : CurrencyTotals.merge(totals);
        return new VarBook(date, head.scenarioSet(), head.scenarioDates(), new ShardedVectors(width, store, remote), tids, tcols, lay.positionOf(),
                pids.toArray(new String[0]), from, to, pc.build(), nodes, units, agg, failed, loadMillis, null, ct, cellColumns, split);
    }

    /** A book's or cell's vector: the one its shard summed, its run checked against the shard's own (one shard holds it whole). */
    private static double[] unitAggregate(String name, int[] run, List<Part> parts, int[] start) {
        for (int k = 0; k < parts.size(); k++) {
            int[] own = parts.get(k).units().get(name);
            if (own == null) {
                continue;
            }
            if (own[0] + start[k] != run[0] || own[1] + start[k] != run[1]) {
                throw new NotShardable("the book " + name + " has trades under two shards (one book under two legal entities or desks): the date "
                        + "is not sharded along its shard key; correct the book's hierarchy columns, or switch sharding off");
            }
            double[] v = parts.get(k).aggregates().get(name);
            if (v == null) {
                throw new NotShardable("shard " + parts.get(k).shard() + " has no aggregate for " + name);
            }
            return v;
        }
        throw new NotShardable("no shard holds the book " + name);
    }

    /** Each shard's first trade comes after the one before's last in the hot file's order (the shards are ranges of it). */
    private static void checkOrder(List<Part> parts, int[] start, Columns t, String[] tids, List<String> cellColumns) {
        List<String> keys = new ArrayList<>(Cells.SORTED);
        keys.addAll(Cells.sortExtras(cellColumns));
        keys.add("POSITION_ID");
        int prevLast = -1;
        int prevShard = -1;
        for (int k = 0; k < parts.size(); k++) {
            if (parts.get(k).size() == 0) {
                continue;
            }
            int first = start[k];
            if (prevLast >= 0 && compare(t, keys, tids, prevLast, first) > 0) {
                throw new NotShardable("shard " + parts.get(k).shard() + " starts before shard " + prevShard + " ends (" + t.text("LEGAL_ENTITY", first)
                        + "/" + t.text("DESK", first) + "): the shards were cut from different data");
            }
            prevLast = start[k] + parts.get(k).size() - 1;
            prevShard = parts.get(k).shard();
        }
    }

    private static int compare(Columns c, List<String> keys, String[] ids, int a, int b) {
        for (String k : keys) {
            String sa = c.text(k, a);
            String sb = c.text(k, b);
            int r = (sa == null ? "" : sa).compareTo(sb == null ? "" : sb);
            if (r != 0) {
                return r;
            }
        }
        return ids[a].compareTo(ids[b]);
    }
}
