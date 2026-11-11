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
package com.ash.drishti.bicompute.core;

import com.ash.drishti.kernel.api.pivot.CellKey;
import com.ash.drishti.kernel.api.pivot.Dim;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.kernel.api.pivot.Windows;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Show values as (HOW_IT_FITS.md section A26.11) and the shares, ranks and windows over an answer's cells (RACHANA_REFERENCE.md section F5,
 * {@link Windows}): each figure shown is computed from the finished figures of the answer's cells, after the cells, Top N and Others are
 * made, so a total is never broken and no rows are read again. The raw figure stays beside the shown one ({@code raw}).
 */
final class ShowAs {

    private ShowAs() {}

    @SuppressWarnings("unchecked")
    static void apply(PivotCore.Computation c, List<Map<String, Object>> rows, List<Map<String, Object>> cols,
            Map<String, Map<String, Object>> cells) {
        String grandRow = c.rows.root.key();
        String grandCol = c.cols.root.key();
        Map<String, Map<String, Object>> rowsByKey = byKey(rows);
        Map<String, Map<String, Object>> colsByKey = byKey(cols);
        // a cell's key is "row key|column key", and a key of several coordinates has "|" in it too: split by the keys themselves
        Map<String, String[]> parts = new LinkedHashMap<>();
        for (String rk : rowsByKey.keySet()) {
            for (String ck : colsByKey.keySet()) {
                if (cells.containsKey(rk + "|" + ck)) {
                    parts.put(rk + "|" + ck, new String[] {rk, ck});
                }
            }
        }
        for (PivotView.Value pv : c.view.values()) {
            String how = PivotView.low(pv.showAs());
            if (how == null) {
                continue;
            }
            String label = pv.label();
            Map<String, Double> rawAll = new LinkedHashMap<>();
            cells.forEach((k, e) -> rawAll.put(k, PivotAnswer.v(cells, k, label)));
            Window w = how.startsWith("diff:") || how.startsWith("pct-change:") ? window(c, pv.showAs()) : null;
            for (Map.Entry<String, Map<String, Object>> x : cells.entrySet()) {
                String[] rc = parts.get(x.getKey());
                if (rc == null) {
                    continue;
                }
                Double raw = rawAll.get(x.getKey());
                Double shown;
                if (how.startsWith("pct-") && w == null) {
                    Double base = switch (how) {
                        case "pct-grand" -> rawAll.get(grandRow + "|" + grandCol);
                        case "pct-row" -> rawAll.get(rc[0] + "|" + grandCol);
                        case "pct-column" -> rawAll.get(grandRow + "|" + rc[1]);
                        case "pct-parent-row" -> rawAll.get(parentKey(rows, rc[0]) + "|" + rc[1]);
                        default -> throw bad(pv.showAs());
                    };
                    shown = raw == null || base == null || base == 0 ? null : raw / base;
                } else if ("rank".equals(how)) {
                    shown = rank(rows, rawAll, rc[0], rc[1]);
                } else if (how.startsWith("ntile:")) {
                    shown = ntile(rowsByKey, rawAll, rc[0], rc[1], Integer.parseInt(how.substring(6)));
                } else if (w != null) {
                    shown = w.apply(rowsByKey, colsByKey, rawAll, rc[0], rc[1]);
                } else {
                    throw bad(pv.showAs());
                }
                Map<String, Object> e = x.getValue();
                ((Map<String, Object>) e.computeIfAbsent("raw", k -> new LinkedHashMap<>())).put(label, raw);
                ((Map<String, Object>) e.get("values")).put(label, shown);
            }
        }
    }

    private static RuntimeException bad(String showAs) {
        Windows.check(showAs);
        return PivotView.bad("show-as '" + showAs + "' is not computed here");
    }

    /** The first entry of each key (a node's header, total and line share one key and one path). */
    private static Map<String, Map<String, Object>> byKey(List<Map<String, Object>> entries) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) {
            out.putIfAbsent(String.valueOf(e.get("key")), e);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> path(Map<String, Object> entry) {
        return entry == null ? List.of() : (List<List<String>>) entry.get("path");
    }

    static String parentKey(List<Map<String, Object>> rows, String key) {
        for (Map<String, Object> r : rows) {
            if (key.equals(r.get("key"))) {
                List<List<String>> path = path(r);
                if (path.isEmpty()) {
                    return key;
                }
                Map<String, String> m = new TreeMap<>();
                path.subList(0, path.size() - 1).forEach(p -> m.put(p.get(0), p.get(1)));
                return CellKey.of(m).canonical();
            }
        }
        return key;
    }

    private static Double rank(List<Map<String, Object>> rows, Map<String, Double> raw, String rowKey, String colKey) {
        String parent = parentKey(rows, rowKey);
        Double mine = raw.get(rowKey + "|" + colKey);
        if (mine == null || parent.equals(rowKey)) {
            return mine == null ? null : 1.0;
        }
        int r = 1;
        for (Map<String, Object> other : rows) {
            String k = String.valueOf(other.get("key"));
            if (!k.equals(rowKey) && !"header".equals(other.get("kind")) && !"total".equals(other.get("kind")) && parent.equals(parentKey(rows, k))
                    && !k.equals(parent)) {
                Double v = raw.get(k + "|" + colKey);
                if (v != null && v > mine) {
                    r++;
                }
            }
        }
        return (double) r;
    }

    /** True for a member line (a node, open or closed), false for the grand total and Others. */
    private static boolean member(Map<String, Object> e) {
        return e != null && !path(e).isEmpty() && !"others".equals(e.get("kind")) && e.get("others") == null;
    }

    /** The keys of the members on the same level under the same parent as {@code key} (itself included), in the order shown. */
    private static List<String> siblings(Map<String, Map<String, Object>> byKey, String key) {
        List<List<String>> mine = path(byKey.get(key));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : byKey.entrySet()) {
            List<List<String>> p = path(e.getValue());
            if (member(e.getValue()) && p.size() == mine.size() && p.subList(0, p.size() - 1).equals(mine.subList(0, mine.size() - 1))
                    && p.get(p.size() - 1).get(0).equals(mine.get(mine.size() - 1).get(0))) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** The bucket 1..n of the row's figure among its siblings in the same column (ascending, ties at their mean rank). */
    private static Double ntile(Map<String, Map<String, Object>> rows, Map<String, Double> raw, String rowKey, String colKey, int n) {
        Double mine = raw.get(rowKey + "|" + colKey);
        if (mine == null || !member(rows.get(rowKey))) {
            return null;
        }
        int below = 0;
        int equal = 0;
        int count = 0;
        for (String k : siblings(rows, rowKey)) {
            Double v = raw.get(k + "|" + colKey);
            if (v == null) {
                continue;
            }
            count++;
            if (v < mine) {
                below++;
            } else if (v.equals(mine)) {
                equal++;
            }
        }
        double meanRank = below + (equal + 1) / 2.0;
        return (double) Windows.ntile(meanRank, count, n);
    }

    /** A diff or pct-change: how far back, along which dimension, on which axis. */
    private record Window(int n, String dim, boolean onRows, boolean pct) {

        Double apply(Map<String, Map<String, Object>> rows, Map<String, Map<String, Object>> cols, Map<String, Double> raw, String rowKey,
                String colKey) {
            Map<String, Map<String, Object>> axis = onRows ? rows : cols;
            String key = onRows ? rowKey : colKey;
            Map<String, Object> e = axis.get(key);
            List<List<String>> p = path(e);
            if (!member(e) || !p.get(p.size() - 1).get(0).equals(dim)) {
                return null;
            }
            List<String> sib = new ArrayList<>(siblings(axis, key));
            sib.sort(Comparator.comparing((String k) -> last(axis, k), ShowAs::natural));
            int i = sib.indexOf(key);
            if (i < n) {
                return null;
            }
            String before = sib.get(i - n);
            Double now = raw.get(rowKey + "|" + colKey);
            Double then = raw.get(onRows ? before + "|" + colKey : rowKey + "|" + before);
            return Windows.change(now, then, pct);
        }

        private static String last(Map<String, Map<String, Object>> axis, String k) {
            List<List<String>> p = path(axis.get(k));
            return p.get(p.size() - 1).get(1);
        }
    }

    /** Members in their natural order: numbers as numbers, everything else (ISO dates, months, names) as text. */
    static int natural(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException | NullPointerException e) {
            return String.valueOf(a).compareTo(String.valueOf(b));
        }
    }

    /** The order dimension of a diff or pct-change, found on the rows or the columns of the view; refused in words when on neither. */
    private static Window window(PivotCore.Computation c, String how) {
        String[] parts = how.split(":", 3);
        int n = Integer.parseInt(parts[1]);
        String order = parts[2];
        String fn = parts[0].equalsIgnoreCase("diff") ? "diff" : "pct_change";
        String onRows = find(c.rows.dims, order);
        if (onRows != null) {
            return new Window(n, onRows, true, !"diff".equals(fn));
        }
        String onCols = find(c.cols.dims, order);
        if (onCols != null) {
            return new Window(n, onCols, false, !"diff".equals(fn));
        }
        Set<String> all = new LinkedHashSet<>();
        c.rows.dims.forEach(d -> all.add(d.id()));
        c.cols.dims.forEach(d -> all.add(d.id()));
        throw PivotView.bad(fn + " goes back along a dimension of the view; " + order + " is on neither the rows nor the columns ("
                + (all.isEmpty() ? "none" : String.join(", ", all)) + ")");
    }

    private static String find(List<Dim> dims, String order) {
        for (Dim d : dims) {
            if (d.id().equalsIgnoreCase(order)) {
                return d.id();
            }
        }
        for (Dim d : dims) {
            if (d.field().equalsIgnoreCase(order)) {
                return d.id();
            }
        }
        return null;
    }
}
