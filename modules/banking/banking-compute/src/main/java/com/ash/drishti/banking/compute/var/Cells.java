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
package com.ash.drishti.banking.compute.var;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The cells of a business date (VAR_GUIDE.md section 15.14): the runs of trades that share a book and the values of every level column
 * of the configured hierarchies ({@link HierarchySpec}). A cell is the unit the hierarchies are built from: each cell is one run of the
 * sorted trades, summed once into its own aggregate, and a node's members are its cells' runs (a node holding every cell of a book holds
 * the book's run). With no level column but the book there are no cells: the book is the unit.
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

    /** The cell columns of a configuration: every level column of every hierarchy but the book (empty: no cells). */
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
            String own = t.text("TT_BOOK", i);
            String book = own == null ? "" : own;                     // a row with no book is still counted: under a (Blank) node
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
            if (!book.equals(prevBook)) {
                books.putIfAbsent(book, new int[] {i, i + 1});
                prevBook = book;
            }
            books.get(book)[1] = i + 1;
            if (withCells) {
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
     * The display name of a node from a reference entity: the level's name ({@code desk}, {@code book}, {@code legal-entity}) is the
     * reference kind, the level value its id. Null when there is none (the node is then named by its value).
     */
    @FunctionalInterface
    public interface Names {

        /** No reference entities: every node named by its own value. */
        Names NONE = (level, value) -> null;

        String of(String level, String value);
    }

    /**
     * A hierarchy's nodes from the units it is built from ({@code units}: the cells, or the books when the date has no cells, in hierarchy
     * order): its root, then every distinct path of the level columns' values, each node holding the units under it. An empty value is a
     * (Blank) node at that level ({@link HierarchySpec#blank}), the deeper levels beneath it. A node that holds every cell of a book holds
     * the book itself ({@code books}: the date's books' runs), so its aggregate is the book's, summed once. The children of a node are in id
     * order. Leaf ids ({@code ids: leaf}) are refused when a value has two parents.
     */
    static void derive(HierarchySpec s, Columns t, Map<String, int[]> units, Map<String, int[]> books, Map<String, Double> limits, Names names,
            List<String[]> rows, Map<String, List<String>> nodeUnits, Map<String, Double> nodeLimits) {
        String root = s.rootNodeId();
        rows.add(new String[] {root, s.id(), HierarchySpec.ROOT_LEVEL, s.rootName(), null});
        List<String[]> mine = new ArrayList<>();
        Map<String, List<String>> held = new HashMap<>();
        derivePaths(s, t, units, limits, names == null ? Names.NONE : names, mine, held, nodeLimits);
        if (units != books) {
            Map<String, Integer> cellsOfBook = new HashMap<>();
            units.keySet().forEach(u -> cellsOfBook.merge(bookOf(u), 1, Integer::sum));
            held.replaceAll((id, us) -> whole(us, cellsOfBook, books));
        }
        nodeUnits.putAll(held);
        // the nodes in id order, so each node's children are too: a parent's aggregate is summed in one order, the same on every server
        mine.sort(java.util.Comparator.comparing((String[] r) -> r[0]));
        rows.addAll(mine);
    }

    /** A node's units with every book whose cells it holds all of written as the book (its cells are one run of it: the same trades). */
    private static List<String> whole(List<String> us, Map<String, Integer> cellsOfBook, Map<String, int[]> books) {
        Map<String, Integer> mine = new LinkedHashMap<>();
        us.forEach(u -> mine.merge(bookOf(u), 1, Integer::sum));
        List<String> out = new ArrayList<>(us.size());
        Set<String> done = new java.util.HashSet<>();
        for (String u : us) {
            String b = bookOf(u);
            if (books.containsKey(b) && mine.get(b).equals(cellsOfBook.get(b))) {
                if (done.add(b)) {
                    out.add(b);
                }
            } else {
                out.add(u);
            }
        }
        return out;
    }

    private static void derivePaths(HierarchySpec s, Columns t, Map<String, int[]> units, Map<String, Double> limits, Names names,
            List<String[]> rows, Map<String, List<String>> nodeUnits, Map<String, Double> nodeLimits) {
        String root = s.rootNodeId();
        List<String> all = new ArrayList<>();
        nodeUnits.put(root, all);
        nodeLimits.put(root, limits.getOrDefault(root, Double.NaN));
        Map<String, String> parentOf = new HashMap<>();
        Map<String, String> named = new HashMap<>();
        int k = s.levels().size();
        for (Map.Entry<String, int[]> e : units.entrySet()) {
            int o = e.getValue()[0];
            List<String> values = new ArrayList<>(k);
            for (String c : s.levels()) {
                String v = t.has(c) ? t.text(c, o) : null;
                values.add(HierarchySpec.blank(v) ? null : v);
            }
            all.add(e.getKey());
            String parent = root;
            for (int d = 1; d <= k; d++) {
                String id = s.nodeId(values, d);
                List<String> mine = nodeUnits.get(id);
                if (mine == null) {
                    String v = values.get(d - 1);
                    String level = s.levelNames().get(d - 1);
                    String name = v == null ? s.blankName() : named.computeIfAbsent(level + "\u0000" + v, x -> {
                        String n = names.of(level, v);
                        return n == null || n.isBlank() ? v : n;
                    });
                    rows.add(new String[] {id, s.id(), level, name, parent});
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
