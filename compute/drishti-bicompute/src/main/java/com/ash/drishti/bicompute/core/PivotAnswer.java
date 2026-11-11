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
import com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall;
import com.ash.drishti.kernel.api.pivot.Kind;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A computed view as the API answers it (HOW_IT_FITS.md section A26.2): the rows and columns as shown (nested levels in display order, with
 * headers and subtotals below or above each open group, Others and the grand totals), every visible cell's finished figures keyed by
 * {@code rowKey|colKey}, the show-as figures, diversification beside holistic totals, and the provenance shared by every cell.
 */
final class PivotAnswer {

    private PivotAnswer() {}

    static Map<String, Object> build(PivotCore core, PivotCore.Computation c) {
        PivotView view = c.view;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", view.source());
        out.put("title", c.catalogue.title());
        out.put("provider", c.provider.id());
        out.put("date", c.catalogue.date());
        out.put("version", c.provider.version(c.viewer, view.source(), c.catalogue.date()));
        out.put("view", view.toMap());
        List<Map<String, Object>> measures = new ArrayList<>();
        for (int i = 0; i < c.functions.size(); i++) {
            FunctionCall f = c.functions.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("id", f.id());
            m.put("label", f.label());
            m.put("kind", f.fn().kind().word());
            m.put("plainSum", f.fn().plainSum());
            m.put("exact", f.fn().kind() != Kind.SKETCH && c.work.sampled == 0);
            if (f.fn() instanceof com.ash.drishti.bicompute.functions.Sketches.Sketch sk) {
                m.put("error", sk.error());
                m.put("errorValue", sk.errorValue());
            }
            m.put("showAs", view.values().get(i).showAs());
            m.put("unit", c.unit(f));
            measures.add(m);
        }
        out.put("measures", measures);
        out.put("rowDimensions", c.rows.dims.stream().map(Dim::id).toList());
        out.put("columnDimensions", c.cols.dims.stream().map(Dim::id).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        emitRows(c, c.rows.root, rows, "above".equals(view.subtotals()));
        List<Map<String, Object>> cols = new ArrayList<>();
        emitCols(c, c.cols.root, cols);
        Map<String, Map<String, Object>> cells = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            if ("header".equals(r.get("kind"))) {
                continue;
            }
            for (Map<String, Object> col : cols) {
                @SuppressWarnings("unchecked")
                Map<String, String> coords = new TreeMap<>((Map<String, String>) r.get("coords"));
                @SuppressWarnings("unchecked")
                Map<String, String> cc = (Map<String, String>) col.get("coords");
                coords.putAll(cc);
                PivotCore.Cell cell = c.cells.get(CellKey.of(coords));
                if (cell == null) {
                    continue;
                }
                Map<String, Object> e = values(core, c, cell);
                e.put("coords", coords);
                e.put("key", CellKey.of(coords).id());
                cells.put(r.get("key") + "|" + col.get("key"), e);
            }
        }
        ShowAs.apply(c, rows, cols, cells);
        diversification(c, rows, cells, cols);
        out.put("rows", rows);
        out.put("columns", cols);
        out.put("cells", cells);
        int visible = c.visibleCells();
        out.put("cellCount", visible);
        Map<String, Object> computed = new LinkedHashMap<>();
        computed.put("sets", c.work.setsAsked);
        computed.put("fromCache", c.work.setsFromCache);
        computed.put("queries", c.work.queries);
        computed.put("rowsRead", c.work.rowsRead);
        computed.put("providerMs", c.work.providerMs);
        computed.put("ms", c.millis());
        computed.put("lazy", "only open nodes are computed; closed nodes' children are not");
        if (c.work.sampled > 0) {
            computed.put("sampled", c.work.sampled);
        }
        out.put("computed", computed);
        boolean anySketch = c.functions.stream().anyMatch(f -> f.fn().kind() == Kind.SKETCH);
        out.put("exactness", c.work.sampled > 0 ? "approximate" : anySketch ? "partial" : "exact");
        out.put("computedAs", computedAs(c, anySketch));
        String id = "pv:" + (c.catalogue.date() == null ? "current" : c.catalogue.date()) + ":"
                + CellKey.hash(view.toMap().toString() + System.nanoTime()).substring(0, 10);
        out.put("provenance", id);
        out.put("provenanceShared", shared(c, id));
        if (!c.work.notes.isEmpty()) {
            out.put("notes", c.work.notes);
        }
        return out;
    }

    /**
     * The view's cells in the grid's order, as {@link #build} lays them out: row by row as shown (a header row shares its total's cells and
     * is skipped), each row's columns left to right. A capped measure is computed for the first of these (PivotProvider.cap).
     */
    @SuppressWarnings("unchecked")
    static List<CellKey> gridOrder(PivotCore.Computation c) {
        List<Map<String, Object>> rows = new ArrayList<>();
        emitRows(c, c.rows.root, rows, "above".equals(c.view.subtotals()));
        List<Map<String, Object>> cols = new ArrayList<>();
        emitCols(c, c.cols.root, cols);
        List<CellKey> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if ("header".equals(r.get("kind"))) {
                continue;
            }
            for (Map<String, Object> col : cols) {
                Map<String, String> coords = new TreeMap<>((Map<String, String>) r.get("coords"));
                coords.putAll((Map<String, String>) col.get("coords"));
                out.add(CellKey.of(coords));
            }
        }
        return out;
    }

    /**
     * How the answer was computed, for the screen's switches and its provenance line (BI_GUIDE.md, the screen's switches): what was
     * asked ({@code exact}, {@code auto} or {@code approximate}; progressive; rollups on or off) and what answered: {@code exact}, the
     * sketches (each measure answered ≈ with its stated error), a level's top N chosen by a summary, a sample, the rollups or nodes that
     * answered, each in words ("≈ from a sketch", "from rollup desk-month", "exact").
     */
    static Map<String, Object> computedAs(PivotCore.Computation c, boolean anySketch) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> asked = new LinkedHashMap<>();
        asked.put("exact", c.scale.word());
        asked.put("progressive", c.scale.progressive());
        asked.put("rollups", c.scale.rollups());
        m.put("asked", asked);
        List<Map<String, Object>> sketches = new ArrayList<>();
        for (FunctionCall f : c.functions) {
            if (f.fn().kind() == Kind.SKETCH && f.fn() instanceof com.ash.drishti.bicompute.functions.Sketches.Sketch sk) {
                sketches.add(Map.of("measure", f.label(), "error", sk.error()));
            }
        }
        m.put("sketches", sketches);
        m.put("topChosen", new ArrayList<>(c.topChosen.values()));
        m.put("routes", new ArrayList<>(c.work.routes));
        m.put("sampled", c.work.sampled);
        List<String> words = new ArrayList<>();
        if (c.work.sampled > 0) {
            words.add(String.format(java.util.Locale.ROOT, "≈ from a %.0f%% sample", c.work.sampled * 100));
        }
        if (anySketch) {
            words.add("≈ from a sketch (" + String.join(", ", sketches.stream().map(x -> String.valueOf(x.get("measure"))).toList()) + ")");
        }
        if (!c.topChosen.isEmpty()) {
            words.add("top N ≈ by a summary, figures exact");
        }
        for (String r : c.work.routes) {
            String rollup = r.startsWith("answered from the rollup ") ? r.substring(25, r.indexOf(' ', 25) < 0 ? r.length() : r.indexOf(' ', 25)) : null;
            words.add(rollup != null ? "from rollup " + rollup : r);
        }
        if (!c.scale.rollups()) {
            words.add("rollups off");
        }
        m.put("words", words.isEmpty() ? "exact" : String.join("; ", words));
        m.put("exact", c.work.sampled == 0 && !anySketch && c.topChosen.isEmpty());
        return m;
    }

    /** A cell's finished figures, members, notes and scope. */
    static Map<String, Object> values(PivotCore core, PivotCore.Computation c, PivotCore.Cell cell) {
        Map<String, Object> e = new LinkedHashMap<>();
        Map<String, Object> vals = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();
        boolean hidden = cell.members < core.settings().minMembersPerCell();
        for (int i = 0; i < c.functions.size(); i++) {
            FunctionCall f = c.functions.get(i);
            Object st = cell.states == null ? null : cell.states[i];
            if (hidden) {
                vals.put(f.label(), null);
                notes.put(f.label(), "fewer than " + core.settings().minMembersPerCell() + " members: hidden (aggregate-only minimum per cell)");
                continue;
            }
            if (cell.notes.containsKey(i)) {
                vals.put(f.label(), null);
                notes.put(f.label(), cell.notes.get(i));
                continue;
            }
            Object v = st == null ? null : f.fn().result(st);
            if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
                v = null;
            }
            vals.put(f.label(), v);
            if (v == null) {
                notes.put(f.label(), st == null ? "not computed" : f.fn().emptyNote());
            }
        }
        e.put("values", vals);
        if (!notes.isEmpty()) {
            e.put("notes", notes);
        }
        e.put("members", cell.members);
        if (cell.scope != null && cell.scope.startsWith("currency ")) {
            e.put("currency", cell.scope.substring(9));
        } else if (cell.scope != null) {
            e.put("scope", cell.scope);
        }
        return e;
    }

    private static String label(PivotCore.Computation c, Axis.Node n) {
        if (n.others) {
            return n.othersCount < 0 ? "Others (≈: the rest after a top N chosen by a summary)"
                    : "Others (" + n.othersCount + " of " + n.parent.levelMembers + ")";
        }
        String name = c.memberName(n.dim(), n.value);
        return name == null ? n.value : name;
    }

    private static Map<String, Object> entry(PivotCore.Computation c, Axis a, Axis.Node n, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", n.key());
        m.put("kind", kind);
        m.put("depth", n.depth());
        m.put("label", n.path.isEmpty() ? "Grand total" : "total".equals(kind) ? label(c, n) + " total" : label(c, n));
        m.put("dim", n.dim());
        m.put("value", n.others ? null : n.value);
        String name = n.others || n.path.isEmpty() ? null : c.memberName(n.dim(), n.value);
        if (name != null) {
            m.put("name", name);                                 // the entity's name; label shows it, value and path keep the id
        }
        m.put("path", n.pairs());
        m.put("coords", n.coords());
        // + opens the next level of the axis; a member on the last level is a leaf and has no + (another field is Expand by…, E)
        m.put("expandable", !n.others && !n.path.isEmpty() && a.defaultChild(n) != null);
        m.put("expanded", n.open() && !n.children.isEmpty());
        m.put("by", n.childDim);
        if (n.others) {
            m.put("others", n.othersCount < 0 ? null : n.othersCount);
            if (n.othersCount < 0) {
                m.put("approximateTop", true);
            }
        }
        return m;
    }

    private static void emitRows(PivotCore.Computation c, Axis.Node n, List<Map<String, Object>> out, boolean above) {
        if (n.path.isEmpty()) {
            if (above || n.children.isEmpty()) {
                out.add(entry(c, c.rows, n, "grand"));
            }
            for (Axis.Node ch : n.children) {
                emitRows(c, ch, out, above);
            }
            if (!above && !n.children.isEmpty()) {
                out.add(entry(c, c.rows, n, "grand"));
            }
            return;
        }
        String kind = n.others ? "others" : "node";
        if (n.open() && !n.children.isEmpty()) {
            if (above) {
                out.add(entry(c, c.rows, n, kind));
                n.children.forEach(ch -> emitRows(c, ch, out, true));
            } else {
                out.add(entry(c, c.rows, n, "header"));
                n.children.forEach(ch -> emitRows(c, ch, out, false));
                out.add(entry(c, c.rows, n, "total"));
            }
        } else {
            out.add(entry(c, c.rows, n, kind));
        }
    }

    private static void emitCols(PivotCore.Computation c, Axis.Node n, List<Map<String, Object>> out) {
        if (n.path.isEmpty()) {
            n.children.forEach(ch -> emitCols(c, ch, out));
            out.add(entry(c, c.cols, n, "grand"));
            return;
        }
        if (n.open() && !n.children.isEmpty()) {
            n.children.forEach(ch -> emitCols(c, ch, out));
            out.add(entry(c, c.cols, n, "total"));
        } else {
            out.add(entry(c, c.cols, n, n.others ? "others" : "node"));
        }
    }

    @SuppressWarnings("unchecked")
    static Double v(Map<String, Map<String, Object>> cells, String key, String label) {
        Map<String, Object> e = cells.get(key);
        if (e == null) {
            return null;
        }
        Object raw = ((Map<String, Object>) e.getOrDefault("raw", Map.of())).get(label);
        Object x = raw != null ? raw : ((Map<String, Object>) e.get("values")).get(label);
        return x instanceof Number n ? n.doubleValue() : null;
    }

    /**
     * Σ children − node for every holistic measure of every open row node (the gap a holistic total shows): at the column total as
     * {@code diversification}, and at every column as {@code diversificationByColumn} (column key to the same figures), so a chart of any
     * column draws the engine's own gap and the browser never adds up the parts. Only when the node and every child have the figure: when
     * one has none (a capped measure past its cells, a cell with a note) the gap is null, never a sum of some of the parts.
     */
    @SuppressWarnings("unchecked")
    private static void diversification(PivotCore.Computation c, List<Map<String, Object>> rows, Map<String, Map<String, Object>> cells,
            List<Map<String, Object>> cols) {
        String grandCol = c.cols.root.key();
        for (Map<String, Object> r : rows) {
            String kind = String.valueOf(r.get("kind"));
            boolean carrier = "total".equals(kind) || "grand".equals(kind) && !c.rows.root.children.isEmpty()
                    || "node".equals(kind) && Boolean.TRUE.equals(r.get("expanded"));
            if (!carrier) {
                continue;
            }
            Axis.Node node = find(c.rows.root, (List<List<String>>) r.get("path"));
            if (node == null || node.children.isEmpty()) {
                continue;
            }
            Map<String, Object> byColumn = new LinkedHashMap<>();
            for (Map<String, Object> col : cols) {
                String colKey = String.valueOf(col.get("key"));
                Map<String, Object> d = gap(c, node, colKey, cells);
                if (!d.isEmpty()) {
                    byColumn.put(colKey, d);
                }
            }
            if (byColumn.get(grandCol) != null) {
                r.put("diversification", byColumn.get(grandCol));
            }
            if (!byColumn.isEmpty()) {
                r.put("diversificationByColumn", byColumn);
            }
        }
    }

    /** Σ children − node at one column, for every holistic measure that has both (empty when none has). */
    private static Map<String, Object> gap(PivotCore.Computation c, Axis.Node node, String colKey, Map<String, Map<String, Object>> cells) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (FunctionCall f : c.functions) {
            if (f.fn().kind() != Kind.HOLISTIC || f.fn().plainSum()) {
                continue;
            }
            if (!cells.containsKey(node.key() + "|" + colKey)) {
                continue;
            }
            Double total = v(cells, node.key() + "|" + colKey, f.label());
            double sum = 0;
            int parts = 0;
            boolean all = total != null;
            for (Axis.Node ch : node.children) {
                if (!cells.containsKey(ch.key() + "|" + colKey)) {
                    continue;                          // no members there: nothing to add
                }
                Double x = v(cells, ch.key() + "|" + colKey, f.label());
                if (x != null) {
                    sum += x;
                    parts++;
                } else {
                    all = false;
                }
            }
            if (all && parts > 0) {
                d.put(f.label(), sum - total);
            } else if (total != null || parts > 0) {
                d.put(f.label(), null);                // a part or the total has no figure (a capped measure): never a partial sum
            }
        }
        return d;
    }

    private static Axis.Node find(Axis.Node root, List<List<String>> path) {
        Axis.Node n = root;
        for (List<String> p : path) {
            Axis.Node next = null;
            for (Axis.Node ch : n.children) {
                if (ch.dim().equals(p.get(0)) && (ch.others ? CellKey.OTHERS : ch.value).equals(p.get(1))) {
                    next = ch;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            n = next;
        }
        return n;
    }

    private static Map<String, Object> shared(PivotCore.Computation c, String id) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("source", c.view.source());
        p.put("title", c.catalogue.title());
        p.put("provider", c.provider.id());
        p.put("date", c.catalogue.date());
        p.put("version", c.provider.version(c.viewer, c.view.source(), c.catalogue.date()));
        List<String> filters = new ArrayList<>();
        c.view.filters().forEach((k, f) -> filters.add(k + " " + f.words()));
        p.put("filters", filters.isEmpty() ? "none" : String.join("; ", filters));
        List<Map<String, Object>> fns = new ArrayList<>();
        for (FunctionCall f : c.functions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", f.label());
            m.put("function", f.id());
            m.put("kind", f.fn().kind().word());
            m.put("how", switch (f.fn().kind()) {
                case MERGEABLE -> "each cell and total from its own rows' state; exact";
                case SKETCH -> "a sketch with a stated error" + (f.fn() instanceof com.ash.drishti.bicompute.functions.Sketches.Sketch sk
                        ? ": " + sk.error() : "") + "; exact on request";
                case HOLISTIC -> "each cell and total recomputed from its own members, never from other cells' figures";
            });
            fns.add(m);
        }
        p.put("functions", fns);
        if (c.view.currency() != null) {
            PivotView.Currency cc = c.view.currency();
            p.put("currency", "money converted into " + cc.report() + " at " + cc.rates() + " (" + cc.source() + "), from each row's "
                    + c.money.currencyField() + "; a row in a currency without a rate is left out");
        } else if (c.money.guard()) {
            p.put("currency", "money in each row's own currency (" + c.money.currencyField() + "); a cell over several currencies shows no money "
                    + "figure until the view converts them");
        }
        p.put("notes", c.catalogue.notes());
        p.put("statements", c.work.statements.size() > 20 ? c.work.statements.subList(0, 20) : c.work.statements);
        p.put("queries", c.work.queries);
        p.put("sets", c.work.setsAsked);
        p.put("fromCache", c.work.setsFromCache);
        p.put("rowsRead", c.work.rowsRead);
        p.put("computeMs", c.millis());
        p.put("viewer", c.viewer.user());
        p.put("at", java.time.Instant.now().toString());
        return p;
    }

    /** Two answers of one view on two dates: the differences per cell and measure, new and gone cells marked. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> compare(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Map<String, Object>> ca = (Map<String, Map<String, Object>>) a.get("cells");
        Map<String, Map<String, Object>> cb = (Map<String, Map<String, Object>>) b.get("cells");
        Map<String, Object> diff = new LinkedHashMap<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(ca.keySet());
        keys.addAll(cb.keySet());
        for (String k : keys) {
            Map<String, Object> x = ca.get(k);
            Map<String, Object> y = cb.get(k);
            Map<String, Object> d = new LinkedHashMap<>();
            if (x == null || y == null) {
                d.put("status", x == null ? "gone" : "new");
            } else {
                Map<String, Object> vx = (Map<String, Object>) x.get("values");
                Map<String, Object> vy = (Map<String, Object>) y.get("values");
                Map<String, Object> delta = new LinkedHashMap<>();
                vx.forEach((m, v) -> delta.put(m, v instanceof Number p && vy.get(m) instanceof Number q ? p.doubleValue() - q.doubleValue() : null));
                d.put("delta", delta);
            }
            diff.put(k, d);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", a.get("date"));
        out.put("otherDate", b.get("date"));
        out.put("answer", a);
        out.put("other", b);
        out.put("diff", diff);
        return out;
    }

    static String estimateWords(PivotProvider.Estimate e, boolean confirm) {
        StringBuilder b = new StringBuilder("This pivot reads ");
        b.append(e.rows() < 0 ? "an unknown number of rows" : "≈" + String.format(Locale.ROOT, "%,d", e.rows()) + " rows");
        if (e.bytes() >= 0) {
            b.append(" (≈").append(bytes(e.bytes())).append(e.ofColumns() > 0 ? ", " + e.columns() + " of " + e.ofColumns() + " columns" : "")
                    .append(")");
        } else if (e.ofColumns() > 0) {
            b.append(" (").append(e.columns()).append(" of ").append(e.ofColumns()).append(" columns)");
        }
        b.append(", ≈").append(e.seconds() < 1 ? "under a second" : String.format(Locale.ROOT, "%.0f s", e.seconds()));
        b.append(confirm ? ". Large: run it, narrow it with a filter, or run it in the background." : ".");
        return b.toString();
    }

    private static String bytes(long b) {
        if (b >= 1L << 30) {
            return String.format(Locale.ROOT, "%.1f GB", b / (double) (1L << 30));
        }
        if (b >= 1L << 20) {
            return String.format(Locale.ROOT, "%.1f MB", b / (double) (1L << 20));
        }
        return String.format(Locale.ROOT, "%.0f KB", Math.max(1, b / 1024.0));
    }
}
