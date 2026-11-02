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
package com.ash.drishti.server.cube;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.server.admission.AdmissionGate;
import com.ash.drishti.server.admission.Work;
import com.ash.drishti.server.security.Principal;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The shared cube core (MR_CUBE_LENS.md section 4): a view becomes a pivot of cells, row totals, column totals and a grand total, each one
 * set of members whose figures the cube's {@link CubeSource} computes from that set's own aggregate. So a non-additive total (VaR, ES, a
 * capital formula) is always the union's own figure, re-aggregated from the members' vectors or inputs, never a sum of cells; the gap is shown
 * as diversification (Σ cells − total). A view of R × C cells is R × C + R + C + 1 sets, asked of the source in one batch (on a sharded date
 * one call per owner for all of them).
 *
 * <p>Around it: the cell budget ({@code drishti.cube.max-cells}, refused with the count, {@code DRS-5602}), busy protection for large views
 * ({@code DRS-9506}), the cell cache (aggregates by the source's cache key and the set, weighed in bytes and held in the one memory budget),
 * provenance ids (the view's shared provenance and each cell's members and scope, kept for the popover), sparklines (the same view on earlier
 * open dates) and the drill (a cell's members split by the next dimension below).
 */
public final class CubeEngine {

    private final Map<String, CubeSource<?>> sources = new LinkedHashMap<>();
    private final CubeProperties props;
    private final Cache<String, Object> cells;
    private final Cache<String, Map<String, Object>> provenance;
    private volatile AdmissionGate gate;

    public CubeEngine(CubeProperties props, List<CubeSource<?>> sources) {
        this.props = props;
        for (CubeSource<?> s : sources) {
            this.sources.put(s.id(), s);
        }
        this.cells = Caffeine.newBuilder().maximumWeight((long) props.cacheMb() << 20).weigher((String k, Object v) -> weight(k, v))
                .recordStats().build();
        this.provenance = Caffeine.newBuilder().maximumSize(props.provenanceKept()).build();
    }

    private static int weight(String key, Object v) {
        long b = 64L + 2L * key.length() + (v instanceof double[] d ? 16L + 8L * d.length : 256L);
        return (int) Math.min(Integer.MAX_VALUE, b);
    }

    /** The cell cache, for the one memory budget ({@code engine.answers}). */
    public Cache<String, Object> cache() {
        return cells;
    }

    public CubeProperties props() {
        return props;
    }

    /** Busy protection for large views (OPERATIONS.md section 12.5); null in tests that do not set it. */
    public void admission(AdmissionGate g) {
        this.gate = g;
    }

    /** The cubes this server runs. */
    public List<String> cubes() {
        return List.copyOf(sources.keySet());
    }

    @SuppressWarnings("unchecked")
    public <A> CubeSource<A> source(String cube) {
        CubeSource<?> s = sources.get(cube == null ? "" : cube);
        if (s == null) {
            throw new DrishtiException(ErrorCode.CUBE_OFF, "the " + (cube == null ? "" : cube.toUpperCase(java.util.Locale.ROOT))
                    + " cube is not running on this server" + (sources.isEmpty() ? " (no cube is switched on: drishti.cube.mr.enabled)"
                            : "; the cubes here: " + String.join(", ", sources.keySet())));
        }
        return (CubeSource<A>) s;
    }

    /** One axis entry: a row or a column, its coordinates, labels and members. */
    record Axis(String key, Map<String, String> coords, List<String> labels, OrdinalSet set) {}

    /** A view computed: the axes, the sets' figures, the source's universe and what it took. */
    public record Pivot(CubeView view, CubeSource.Universe<?> universe, List<CubeMeasure> measures, List<Axis> rows, List<Axis> columns,
            Map<String, Map<String, Object>> entries, Map<String, Object> headline, long computeMs, int cached, int sets, List<String> notes) {

        /** The entry of row {@code r} and column {@code c} ({@code -1}: the total of that axis). */
        public Map<String, Object> entry(int r, int c) {
            return entries.get(r + "|" + c);
        }
    }

    /** The view's answer: the pivot, totals, diversification, provenance id, sparklines when asked. */
    public Map<String, Object> query(Principal p, CubeView view) {
        Pivot pv = pivot(p, view, true);
        Map<String, Object> out = answer(pv);
        if (view.history() > 0) {
            sparklines(p, view, pv, out);
        }
        return out;
    }

    /** The view computed (its measures defaulted and checked), every set through the cache and one batch of the source. */
    public <A> Pivot pivot(Principal p, CubeView asked, boolean admit) {
        long t0 = System.nanoTime();
        CubeSource<A> src = source(asked.cube());
        List<String> ids = asked.measures().isEmpty() ? src.defaultMeasures() : asked.measures();
        Map<String, CubeMeasure> known = new LinkedHashMap<>();
        src.measures().forEach(m -> known.put(m.id(), m));
        List<CubeMeasure> measures = new ArrayList<>();
        for (String id : ids) {
            CubeMeasure m = known.get(id);
            if (m == null) {
                throw CubeView.bad(id + " is not a measure of the " + src.title() + " here; its measures: " + String.join(", ", known.keySet()));
            }
            measures.add(m);
        }
        CubeView view = new CubeView(asked.cube(), asked.scope(), asked.rows(), asked.columns(), measures.stream().map(CubeMeasure::id).toList(),
                asked.filters(), asked.date(), asked.lens(), asked.sort(), asked.history());
        CubeSource.Universe<A> u = src.open(p, view);
        List<String> notes = new ArrayList<>();
        OrdinalSet members = u.members();
        for (Map.Entry<String, List<String>> f : view.filters().entrySet()) {
            Map<String, OrdinalSet> part = u.partition(f.getKey());
            OrdinalSet keep = OrdinalSet.empty();
            for (String v : f.getValue()) {
                OrdinalSet s = part.get(v);
                if (s == null) {
                    notes.add(f.getKey() + "=" + v + ": no member of the view has it");
                } else {
                    keep = keep.or(s);
                }
            }
            members = members.and(keep);
        }
        List<Axis> rows = axis(u, view.rows(), members);
        List<Axis> cols = axis(u, view.columns(), members);
        boolean hasRows = !view.rows().isEmpty();
        boolean hasCols = !view.columns().isEmpty();
        // the sets: the grand total, each row's, each column's, each cell's (an empty cell is no set: the desk has no member there)
        List<String> keys = new ArrayList<>();
        List<CubeSource.Cell> cellsAsked = new ArrayList<>();
        keys.add("-1|-1");
        cellsAsked.add(new CubeSource.Cell("total", Map.of(), members, u.scopeOf(Map.of())));
        if (hasRows) {
            for (int r = 0; r < rows.size(); r++) {
                keys.add(r + "|-1");
                cellsAsked.add(new CubeSource.Cell("row", rows.get(r).coords(), rows.get(r).set(), u.scopeOf(rows.get(r).coords())));
            }
        }
        if (hasCols) {
            for (int c = 0; c < cols.size(); c++) {
                keys.add("-1|" + c);
                cellsAsked.add(new CubeSource.Cell("column", cols.get(c).coords(), cols.get(c).set(), u.scopeOf(cols.get(c).coords())));
            }
        }
        if (hasRows && hasCols) {
            for (int r = 0; r < rows.size(); r++) {
                for (int c = 0; c < cols.size(); c++) {
                    OrdinalSet s = rows.get(r).set().and(cols.get(c).set());
                    if (!s.isEmpty()) {
                        Map<String, String> coords = new LinkedHashMap<>(rows.get(r).coords());
                        coords.putAll(cols.get(c).coords());
                        keys.add(r + "|" + c);
                        cellsAsked.add(new CubeSource.Cell("cell", coords, s, u.scopeOf(coords)));
                    }
                }
            }
        }
        int count = (hasRows ? rows.size() : 0) * Math.max(1, hasCols ? cols.size() : 0) + (hasRows ? rows.size() : 0)
                + (hasCols ? cols.size() : 0) + 1;
        if (count > props.maxCells()) {
            throw new DrishtiException(ErrorCode.CUBE_TOO_MANY_CELLS, "the view has " + count + " cells with its totals ("
                    + rows.size() + " rows × " + Math.max(1, cols.size()) + " columns), more than the budget of " + props.maxCells()
                    + " (drishti.cube.max-cells): narrow it with a filter or fewer dimensions; a view is never cut short");
        }
        if (admit && gate != null && count > props.busyCells()) {
            gate.admit(Work.CUBE_VIEW, p == null ? "" : p.user(), "A cube view of " + count + " cells");
        }
        // the aggregates: from the cache, the misses in one batch
        List<A> aggs = new ArrayList<>(cellsAsked.size());
        List<Integer> missing = new ArrayList<>();
        List<OrdinalSet> missingSets = new ArrayList<>();
        String base = src.id() + "|" + u.cacheKey() + "|";
        int hits = 0;
        for (int i = 0; i < cellsAsked.size(); i++) {
            @SuppressWarnings("unchecked")
            A hit = (A) cells.getIfPresent(base + cellsAsked.get(i).set().digest());
            aggs.add(hit);
            if (hit == null) {
                missing.add(i);
                missingSets.add(cellsAsked.get(i).set());
            } else {
                hits++;
            }
        }
        if (!missingSets.isEmpty()) {
            List<A> got = u.aggregates(missingSets);
            for (int k = 0; k < missing.size(); k++) {
                int i = missing.get(k);
                aggs.set(i, got.get(k));
                cells.put(base + cellsAsked.get(i).set().digest(), got.get(k));
            }
        }
        A total = aggs.get(0);
        List<String> measureIds = view.measures();
        Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
        for (int i = 0; i < cellsAsked.size(); i++) {
            CubeSource.Cell cell = cellsAsked.get(i);
            CubeSource.Figures f = u.figures(measureIds, aggs.get(i), cell, total);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("kind", cell.kind());
            e.put("coords", cell.coords());
            e.put("members", cell.set().size());
            e.put("scope", cell.scope());
            e.put("values", rounded(f.values()));
            if (!f.notes().isEmpty()) {
                e.put("notes", f.notes());
            }
            e.put("cached", missing.contains(i) ? Boolean.FALSE : Boolean.TRUE);
            entries.put(keys.get(i), e);
        }
        Map<String, Object> headline = u.headline(total);
        // sort the rows by the first measure's row total when asked
        if (hasRows && !"none".equals(view.sort()) && !measureIds.isEmpty()) {
            String m = measureIds.get(0);
            List<Integer> order = new ArrayList<>();
            for (int r = 0; r < rows.size(); r++) {
                order.add(r);
            }
            Comparator<Integer> by = Comparator.comparingDouble(r -> {
                Object v = values(entries.get(r + "|-1")).get(m);
                return v instanceof Number n ? n.doubleValue() : Double.NEGATIVE_INFINITY;
            });
            order.sort("desc".equals(view.sort()) ? by.reversed() : by);
            List<Axis> sorted = new ArrayList<>();
            Map<String, Map<String, Object>> moved = new LinkedHashMap<>();
            for (int to = 0; to < order.size(); to++) {
                int from = order.get(to);
                sorted.add(rows.get(from));
                for (int c = -1; c < cols.size(); c++) {
                    Map<String, Object> e = entries.get(from + "|" + c);
                    if (e != null) {
                        moved.put(to + "|" + c, e);
                    }
                }
            }
            entries.keySet().removeIf(k -> !k.startsWith("-1|"));
            entries.putAll(moved);
            rows = sorted;
        }
        return new Pivot(view, u, measures, rows, cols, entries, headline, (System.nanoTime() - t0) / 1_000_000, hits, cellsAsked.size(), notes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(Map<String, Object> e) {
        return e == null ? Map.of() : (Map<String, Object>) e.get("values");
    }

    /** Money to the cent, a share to 1e-6: the answer's figures (the source's are exact; the API rounds once, here). */
    private static Map<String, Object> rounded(Map<String, Double> v) {
        Map<String, Object> out = new LinkedHashMap<>();
        v.forEach((k, x) -> out.put(k, x == null || x.isNaN() || x.isInfinite() ? null
                : k.endsWith("Use") || k.endsWith("Pct") ? Math.round(x * 1e6) / 1e6 : Math.round(x * 100) / 100.0));
        return out;
    }

    /** The members split by each dimension in turn (a nested axis), each entry the intersection, empty ones dropped. */
    private static <A> List<Axis> axis(CubeSource.Universe<A> u, List<String> dims, OrdinalSet members) {
        List<Axis> out = new ArrayList<>();
        if (dims.isEmpty()) {
            return out;
        }
        out.add(new Axis("", Map.of(), List.of(), members));
        for (String d : dims) {
            Map<String, OrdinalSet> part = u.partition(d);
            List<Axis> next = new ArrayList<>();
            for (Axis a : out) {
                for (Map.Entry<String, OrdinalSet> e : part.entrySet()) {
                    OrdinalSet x = a.set().and(e.getValue());
                    if (!x.isEmpty()) {
                        Map<String, String> coords = new LinkedHashMap<>(a.coords());
                        coords.put(d, e.getKey());
                        List<String> labels = new ArrayList<>(a.labels());
                        labels.add(u.label(d, e.getKey()));
                        next.add(new Axis(a.key().isEmpty() ? e.getKey() : a.key() + " › " + e.getKey(), coords, labels, x));
                    }
                }
            }
            out = next;
        }
        return out;
    }

    /** The pivot as the API answers it. */
    public Map<String, Object> answer(Pivot pv) {
        CubeView view = pv.view();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cube", view.cube());
        out.put("text", view.text());
        out.put("view", view.toMap());
        out.put("date", String.valueOf(pv.universe().date()));
        out.put("canonical", pv.universe().canonical());
        out.put("measures", pv.measures());
        out.put("rowDimensions", view.rows());
        out.put("columnDimensions", view.columns());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int r = 0; r < pv.rows().size(); r++) {
            rows.add(axisEntry(pv.rows().get(r), pv.entry(r, -1)));
        }
        List<Map<String, Object>> cols = new ArrayList<>();
        for (int c = 0; c < pv.columns().size(); c++) {
            cols.add(axisEntry(pv.columns().get(c), pv.entry(-1, c)));
        }
        List<Map<String, Object>> cells = new ArrayList<>();
        for (int r = 0; r < pv.rows().size(); r++) {
            for (int c = 0; c < pv.columns().size(); c++) {
                Map<String, Object> e = pv.entry(r, c);
                if (e != null) {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("r", r);
                    x.put("c", c);
                    x.putAll(e);
                    x.remove("kind");
                    cells.add(x);
                }
            }
        }
        out.put("rows", rows);
        out.put("columns", cols);
        out.put("cells", cells);
        out.put("total", pv.entry(-1, -1));
        out.put("diversification", diversification(pv));
        out.put("headline", pv.headline());
        String id = "CV-" + UUID.randomUUID().toString().substring(0, 13);
        Map<String, Object> prov = new LinkedHashMap<>(pv.universe().provenance());
        prov.put("view", view.text());
        prov.put("sets", pv.sets());
        prov.put("fromCache", pv.cached());
        prov.put("computeMs", pv.computeMs());
        Map<String, Object> kept = new LinkedHashMap<>();
        kept.put("shared", prov);
        kept.put("entries", pv.entries());
        kept.put("rows", pv.rows().stream().map(Axis::key).toList());
        kept.put("columns", pv.columns().stream().map(Axis::key).toList());
        provenance.put(id, kept);
        out.put("provenance", id);
        out.put("provenanceShared", prov);
        out.put("cellCount", pv.sets());
        out.put("computeMs", pv.computeMs());
        out.put("fromCache", pv.cached());
        if (!pv.notes().isEmpty()) {
            out.put("notes", pv.notes());
        }
        return out;
    }

    private static Map<String, Object> axisEntry(Axis a, Map<String, Object> e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", a.key());
        m.put("labels", a.labels());
        if (e != null) {
            m.putAll(e);
            m.remove("kind");
        }
        return m;
    }

    /**
     * Σ cells − total for every non-additive measure: per row (its cells against the row's own total), per column, and for the grand total
     * against the rows' and the columns' totals (the desks sum to more than the bank: the diversification the total row shows).
     */
    private static Map<String, Object> diversification(Pivot pv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (CubeMeasure m : pv.measures()) {
            if (m.kind().additive()) {
                continue;
            }
            String id = m.id();
            List<Double> byRow = new ArrayList<>();
            for (int r = 0; r < pv.rows().size(); r++) {
                byRow.add(gap(pv, id, r, -1, true));
            }
            List<Double> byCol = new ArrayList<>();
            for (int c = 0; c < pv.columns().size(); c++) {
                byCol.add(gap(pv, id, -1, c, false));
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("rows", byRow);
            d.put("columns", byCol);
            d.put("totalAcrossRows", sumAxis(pv, id, true));
            d.put("totalAcrossColumns", sumAxis(pv, id, false));
            out.put(id, d);
        }
        return out;
    }

    private static Double gap(Pivot pv, String id, int r, int c, boolean alongRow) {
        Object t = values(pv.entry(r, c)).get(id);
        if (!(t instanceof Number total) || (alongRow ? pv.columns().isEmpty() : pv.rows().isEmpty())) {
            return null;
        }
        double s = 0;
        int n = alongRow ? pv.columns().size() : pv.rows().size();
        for (int i = 0; i < n; i++) {
            Object v = values(alongRow ? pv.entry(r, i) : pv.entry(i, c)).get(id);
            if (v instanceof Number x) {
                s += x.doubleValue();
            }
        }
        return Math.round((s - total.doubleValue()) * 100) / 100.0;
    }

    private static Double sumAxis(Pivot pv, String id, boolean rows) {
        Object t = values(pv.entry(-1, -1)).get(id);
        int n = rows ? pv.rows().size() : pv.columns().size();
        if (!(t instanceof Number total) || n == 0) {
            return null;
        }
        double s = 0;
        for (int i = 0; i < n; i++) {
            Object v = values(rows ? pv.entry(i, -1) : pv.entry(-1, i)).get(id);
            if (v instanceof Number x) {
                s += x.doubleValue();
            }
        }
        return Math.round((s - total.doubleValue()) * 100) / 100.0;
    }

    /** Each cell's first measure on the earlier open dates (and this one), the same view computed on each. */
    private void sparklines(Principal p, CubeView view, Pivot pv, Map<String, Object> out) {
        if (pv.sets() > props.sparklineMax()) {
            out.put("sparklineNote", "no sparklines: " + pv.sets() + " cells, more than drishti.cube.sparkline-max (" + props.sparklineMax() + ")");
            return;
        }
        CubeSource<?> src = source(view.cube());
        List<LocalDate> dates = new ArrayList<>(src.historyDates(p, view.on(pv.universe().date()), view.history()));
        String m = pv.view().measures().isEmpty() ? null : pv.view().measures().get(0);
        if (m == null) {
            return;
        }
        Map<String, List<Object>> series = new LinkedHashMap<>();
        List<String> used = new ArrayList<>();
        for (LocalDate d : dates) {
            Pivot other;
            try {
                other = d.equals(pv.universe().date()) ? pv : pivot(p, new CubeView(view.cube(), view.scope(), view.rows(), view.columns(),
                        List.of(m), view.filters(), d, view.lens(), "none", 0), false);
            } catch (RuntimeException e) {
                continue;                                              // a date that cannot be answered now: left out of the line
            }
            used.add(d.toString());
            Map<String, Object> byCoords = new LinkedHashMap<>();
            other.entries().values().forEach(e -> byCoords.put(String.valueOf(e.get("coords")), values(e).get(m)));
            for (Map<String, Object> e : pv.entries().values()) {
                series.computeIfAbsent(String.valueOf(e.get("coords")), k -> new ArrayList<>()).add(byCoords.get(String.valueOf(e.get("coords"))));
            }
        }
        out.put("sparklineDates", used);
        out.put("sparklineMeasure", m);
        attach(out.get("rows"), series);
        attach(out.get("columns"), series);
        attach(out.get("cells"), series);
        Object t = out.get("total");
        if (t instanceof Map<?, ?> tm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> total = new LinkedHashMap<>((Map<String, Object>) tm);
            total.put("spark", series.get(String.valueOf(total.get("coords"))));
            out.put("total", total);
        }
    }

    @SuppressWarnings("unchecked")
    private static void attach(Object list, Map<String, List<Object>> series) {
        if (list instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> e = (Map<String, Object>) o;
                e.put("spark", series.get(String.valueOf(e.get("coords"))));
            }
        }
    }

    /**
     * One cell's right rail and drill: its figures, the source's detail (histogram, VaR scenario, ES tail) and its members split by the
     * dimension below the row's last one ({@code row}, {@code column}: the axis keys of the answer; blank: that axis's total).
     */
    public <A> Map<String, Object> cell(Principal p, CubeView view, String row, String column) {
        Pivot pv = pivot(p, view, false);
        int r = indexOf(pv.rows(), row);
        int c = indexOf(pv.columns(), column);
        Map<String, Object> e = pv.entry(r, c);
        if (e == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the view has no cell " + (row == null ? "" : row) + " × "
                    + (column == null ? "" : column) + ": no member of the view is there");
        }
        @SuppressWarnings("unchecked")
        CubeSource.Universe<A> u = (CubeSource.Universe<A>) pv.universe();
        @SuppressWarnings("unchecked")
        Map<String, String> coords = (Map<String, String>) e.get("coords");
        OrdinalSet set = r >= 0 && c >= 0 ? pv.rows().get(r).set().and(pv.columns().get(c).set()) : r >= 0 ? pv.rows().get(r).set()
                : c >= 0 ? pv.columns().get(c).set() : pv.rows().isEmpty() ? u.members() : union(pv.rows());
        A agg = u.aggregates(List.of(set)).get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cell", e);
        out.put("detail", u.detail(agg, new CubeSource.Cell(String.valueOf(e.get("kind")), coords, set, (String) e.get("scope"))));
        CubeSource<A> src = source(view.cube());
        String last = !pv.view().rows().isEmpty() ? pv.view().rows().get(pv.view().rows().size() - 1) : null;
        String below = src.drillBelow(last);
        if (below != null && !coords.containsKey(below)) {
            Pivot drill = pivot(p, view.drilled(coords, List.of(below), List.of()), false);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("dimension", below);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < drill.rows().size(); i++) {
                rows.add(axisEntry(drill.rows().get(i), drill.entry(i, -1)));
            }
            d.put("rows", rows);
            d.put("view", drill.view().toMap());
            out.put("drill", d);
        } else {
            out.put("drill", Map.of("note", "the last level: open the trade (TRD), its P&L vector (PNLV) or the scenario matrix (SMAT)"));
        }
        return out;
    }

    private static OrdinalSet union(List<Axis> axes) {
        OrdinalSet s = OrdinalSet.empty();
        for (Axis a : axes) {
            s = s.or(a.set());
        }
        return s;
    }

    private static int indexOf(List<Axis> axes, String key) {
        if (key == null || key.isBlank()) {
            return -1;
        }
        for (int i = 0; i < axes.size(); i++) {
            if (axes.get(i).key().equals(key)) {
                return i;
            }
        }
        throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no row or column " + key + " in the view");
    }

    /**
     * The provenance of an answer's cell ({@code cell}: {@code r|c}, the indexes of the answer, {@code -1} for a total; blank: the view's
     * shared provenance only). Kept for the {@code drishti.cube.provenance-kept} answers last given.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> provenance(String id, String cell) {
        Map<String, Object> kept = provenance.getIfPresent(id == null ? "" : id);
        if (kept == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "provenance " + id + " is not kept any more (the answers last given keep "
                    + "theirs): ask the view again");
        }
        Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) kept.get("shared"));
        out.put("id", id);
        if (cell != null && !cell.isBlank()) {
            Map<String, Object> e = ((Map<String, Map<String, Object>>) kept.get("entries")).get(cell.strip());
            if (e == null) {
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the answer " + id + " has no cell " + cell);
            }
            out.put("cell", e);
        }
        return out;
    }

    /** Every measure and the view's dimensions (the chips and the type-ahead). */
    public Map<String, Object> describe(Principal p, CubeView view) {
        CubeSource<?> src = source(view.cube());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cube", src.id());
        out.put("title", src.title());
        out.put("measures", src.measures());
        out.put("defaultMeasures", src.defaultMeasures());
        CubeSource.Universe<?> u = src.open(p, view);
        out.put("date", String.valueOf(u.date()));
        out.put("dimensions", u.dimensions());
        out.put("maxCells", props.maxCells());
        out.put("busyCells", props.busyCells());
        return out;
    }
}
