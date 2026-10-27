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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The cells of a business date (VAR_GUIDE.md section 25, 2.9.5): the runs of trades that share a book and the values of every level
 * column of the configured column hierarchies ({@link HierarchySpec#columns()}). A cell is the unit the column hierarchies are built from,
 * as a book is the unit of the risk-hierarchy ones: each cell is one run of the sorted trades, summed once into its own aggregate, and a
 * column node's members are its cells' runs. With no column hierarchy there are no cells: the book is the unit, as before 2.9.5.
 *
 * <p>A cell is named {@code <book>|<value>|<value>...} (the cell columns' values in order), so it never collides with a book's name.
 * A position never crosses a cell (decision D4): a position whose trades sit in two cells is split, each part named
 * {@code <position>@<values>} (the cell's own values, joined with {@code /}), and counted.
 */
public final class Cells {

    /** The columns the trades are sorted by before the cell columns: a cell column among them adds nothing to the sort. */
    public static final List<String> SORTED = List.of("LEGAL_ENTITY", "DESK", "TT_BOOK");
    /** Between a cell's book and its values. */
    public static final String SEP = "|";

    private Cells() {}

    /** The cell columns of a configuration: every level column of every column hierarchy but the book (empty: no cells). */
    public static List<String> columns(List<HierarchySpec> specs) {
        List<String> out = new ArrayList<>(HierarchySpec.levelColumns(specs));
        out.remove("TT_BOOK");
        return List.copyOf(out);
    }

    /** The cell columns the sort adds after {@link #SORTED} (those it does not already sort by). */
    static List<String> sortExtras(List<String> cellColumns) {
        return cellColumns.stream().filter(c -> !SORTED.contains(c)).toList();
    }

    /** The text columns of a book with these cell columns: the engine's, then each cell column it does not keep already. */
    public static List<String> textColumns(List<String> cellColumns) {
        LinkedHashSet<String> out = new LinkedHashSet<>(VarBook.TRADE_TEXTS);
        out.addAll(cellColumns);
        return List.copyOf(out);
    }

    /** A cell's name from its book and its values (null: an empty value). */
    static String name(String book, List<String> values) {
        StringBuilder b = new StringBuilder(book == null ? "" : book);
        for (String v : values) {
            b.append(SEP).append(v == null ? "" : v);
        }
        return b.toString();
    }

    /** True when a name is a cell's (not a book's). */
    public static boolean isCell(String name) {
        return name != null && name.contains(SEP);
    }

    /** The book a cell is part of (a book's own name for a book). */
    public static String bookOf(String cellOrBook) {
        int i = cellOrBook == null ? -1 : cellOrBook.indexOf(SEP);
        return i < 0 ? cellOrBook : cellOrBook.substring(0, i);
    }

    /** The values of row {@code o}'s cell columns. */
    static List<String> values(Columns t, List<String> cellColumns, int o) {
        List<String> v = new ArrayList<>(cellColumns.size());
        for (String c : cellColumns) {
            v.add(t.text(c, o));
        }
        return v;
    }

    /** The suffix a split position's part takes: its cell's own values (those the sort does not hold; all of them when none). */
    static String suffix(List<String> cellColumns, List<String> values) {
        List<String> own = new ArrayList<>();
        for (int i = 0; i < cellColumns.size(); i++) {
            if (!SORTED.contains(cellColumns.get(i))) {
                own.add(values.get(i) == null ? "" : values.get(i));
            }
        }
        if (own.isEmpty()) {
            values.forEach(v -> own.add(v == null ? "" : v));
        }
        return String.join("/", own);
    }

    /**
     * What the sorted trades make: the books' runs, the cells' runs (none without cell columns), the positions' runs (a position split at
     * a cell's edge), the position of each trade, and the trades' {@code POSITION_ID} after a split (null: unchanged).
     */
    record Layout(Map<String, int[]> books, Map<String, int[]> cells, List<String> positionIds, List<int[]> runs, int[] positionOf,
            String[] renamed, int split) {}

    static Layout layout(Columns t, String[] tradeIds, List<String> cellColumns) {
        int n = tradeIds.length;
        Map<String, int[]> books = new LinkedHashMap<>();
        Map<String, int[]> cells = new LinkedHashMap<>();
        List<String> pids = new ArrayList<>();
        List<int[]> runs = new ArrayList<>();
        List<String> runCell = new ArrayList<>();
        int[] posOf = new int[n];
        String prevPos = null;
        String prevBook = null;
        String prevCell = null;
        boolean withCells = !cellColumns.isEmpty();
        for (int i = 0; i < n; i++) {
            String p = t.text("POSITION_ID", i);
            String pid = p == null ? "POS-" + tradeIds[i] : p;
            String book = t.text("TT_BOOK", i);
            String cell = withCells ? name(book, values(t, cellColumns, i)) : null;
            if (!pid.equals(prevPos) || withCells && !Objects.equals(cell, prevCell)) {
                pids.add(pid);
                runs.add(new int[] {i, i + 1});
                runCell.add(cell);
                prevPos = pid;
            } else {
                runs.get(runs.size() - 1)[1] = i + 1;
            }
            posOf[i] = pids.size() - 1;
            if (book != null) {
                if (!book.equals(prevBook)) {
                    books.putIfAbsent(book, new int[] {i, i + 1});
                    prevBook = book;
                }
                books.get(book)[1] = i + 1;
            }
            if (withCells && book != null) {
                if (!cell.equals(prevCell)) {
                    cells.putIfAbsent(cell, new int[] {i, i + 1});
                }
                cells.get(cell)[1] = i + 1;
            }
            prevCell = cell;
        }
        String[] renamed = null;
        int split = 0;
        if (withCells) {
            Map<String, Set<String>> cellsOf = new HashMap<>();
            for (int k = 0; k < pids.size(); k++) {
                cellsOf.computeIfAbsent(pids.get(k), x -> new LinkedHashSet<>()).add(String.valueOf(runCell.get(k)));
            }
            Set<String> counted = new java.util.HashSet<>();
            for (int k = 0; k < pids.size(); k++) {
                String pid = pids.get(k);
                if (cellsOf.get(pid).size() > 1) {
                    if (counted.add(pid)) {
                        split++;
                    }
                    String part = pid + "@" + suffix(cellColumns, values(t, cellColumns, runs.get(k)[0]));
                    pids.set(k, part);
                    if (renamed == null) {
                        renamed = new String[n];
                    }
                    for (int o = runs.get(k)[0]; o < runs.get(k)[1]; o++) {
                        renamed[o] = part;
                    }
                }
            }
        }
        return new Layout(books, cells, pids, runs, posOf, renamed, split);
    }

    /**
     * A column hierarchy's nodes from the units it is built from ({@code units}: the cells, or the books when the date has no cells, in
     * hierarchy order): its root, then every distinct path of the level columns' values, each node holding the units under it. The
     * children of a node are in the order they first appear. Leaf ids ({@code ids: leaf}) are refused when a value has two parents.
     */
    static void derive(HierarchySpec s, Columns t, Map<String, int[]> units, Map<String, Double> limits, List<String[]> rows,
            Map<String, List<String>> nodeUnits, Map<String, Double> nodeLimits) {
        String root = s.rootNodeId();
        rows.add(new String[] {root, s.id(), HierarchySpec.ROOT_LEVEL, s.rootName(), null});
        List<String> all = new ArrayList<>();
        nodeUnits.put(root, all);
        nodeLimits.put(root, limits.getOrDefault(root, Double.NaN));
        Map<String, String> parentOf = new HashMap<>();
        int k = s.levels().size();
        for (Map.Entry<String, int[]> e : units.entrySet()) {
            int o = e.getValue()[0];
            List<String> values = new ArrayList<>(k);
            for (String c : s.levels()) {
                String v = t.text(c, o);
                values.add(v == null ? "" : v);
            }
            all.add(e.getKey());
            String parent = root;
            for (int d = 1; d <= k; d++) {
                String id = s.nodeId(values, d);
                List<String> mine = nodeUnits.get(id);
                if (mine == null) {
                    rows.add(new String[] {id, s.id(), s.levelNames().get(d - 1), values.get(d - 1), parent});
                    mine = new ArrayList<>();
                    nodeUnits.put(id, mine);
                    nodeLimits.put(id, limits.getOrDefault(id, Double.NaN));
                    parentOf.put(id, parent);
                } else if (s.leafIds() && !parent.equals(parentOf.get(id))) {
                    throw new IllegalStateException("the hierarchy " + s.id() + " names its nodes by their own value (ids: leaf), but "
                            + values.get(d - 1) + " sits under both " + parentOf.get(id) + " and " + parent + " on this date: use ids: path");
                }
                if (mine.isEmpty() || !mine.get(mine.size() - 1).equals(e.getKey())) {
                    mine.add(e.getKey());
                }
                parent = id;
            }
        }
    }
}
