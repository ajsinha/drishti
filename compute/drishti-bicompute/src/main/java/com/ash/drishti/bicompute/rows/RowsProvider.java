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
package com.ash.drishti.bicompute.rows;

import com.ash.drishti.api.ListValues;
import com.ash.drishti.kernel.api.pivot.AggregationPlan;
import com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall;
import com.ash.drishti.kernel.api.pivot.CellKey;
import com.ash.drishti.kernel.api.pivot.CellSet;
import com.ash.drishti.kernel.api.pivot.Dim;
import com.ash.drishti.kernel.api.pivot.Partials;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import com.ash.drishti.kernel.api.pivot.Viewer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A pivot over rows held in memory (ARCHITECTURE.md section 26.10: every component that aggregates calls the engine): a Sutra's {@code pivot}
 * panel over its document's rows, a chart of a table's shown rows. The rows are grouped by each cell set here and every function's state is
 * made by its own {@code add} over a columnar batch of the cell's rows, so the figures are the built-ins' (the same functions the tabular
 * provider computes in SQL). Small by nature (a panel's rows, a page of a table); one provider holds one table.
 */
public final class RowsProvider implements PivotProvider {

    private final String source;
    private final String title;
    private final List<Field> fields;
    private final List<Object[]> rows;
    private final Map<String, Integer> at = new HashMap<>();

    /**
     * @param source the source's id
     * @param title its title
     * @param fields its fields (name, type {@code string}, {@code date} or {@code number}, unit)
     * @param rows the rows, one value per field in order (String, Number or null)
     */
    public RowsProvider(String source, String title, List<Field> fields, List<Object[]> rows) {
        this.source = source;
        this.title = title;
        this.fields = List.copyOf(fields);
        this.rows = rows;
        for (int i = 0; i < fields.size(); i++) {
            at.put(fields.get(i).name(), i);
        }
    }

    @Override
    public String id() {
        return "rows";
    }

    @Override
    public boolean serves(String s) {
        return source.equals(s);
    }

    @Override
    public Map<String, String> sources(Viewer v) {
        return Map.of();
    }

    @Override
    public Catalogue catalogue(Viewer v, String s, String date) {
        return new Catalogue(source, title, fields, List.of(), null, List.of(), version(v, s, date), 0, Map.of("route", "rows held in memory"));
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, false, true, false);
    }

    @Override
    public String version(Viewer v, String s, String date) {
        return "rows@" + Integer.toHexString(System.identityHashCode(rows)) + ":" + rows.size();
    }

    @Override
    public Estimate estimate(Viewer v, AggregationPlan plan) {
        return new Estimate(rows.size(), -1, plan.dims().size(), fields.size(), 0, "rows held in memory");
    }

    private String type(String field) {
        Integer i = at.get(field);
        return i == null ? "string" : fields.get(i).type();
    }

    /**
     * A row's members of a dimension: one, or for a list attribute each distinct value (explode: the row counts under each; an empty
     * list is the blank member), unless the dimension joins it into one label ({@code TAGS:join}).
     */
    List<String> values(Dim d, Object[] row) {
        Integer i = at.get(d.field());
        Object raw = i == null || i >= row.length ? null : row[i];
        if (d.group() == null && (raw instanceof java.util.Collection<?> || "list".equals(type(d.field())))) {
            List<String> items = ListValues.of(raw);
            if (items != null) {
                List<String> out = items.stream().filter(s -> !s.isBlank()).distinct().toList();
                return out.isEmpty() ? List.of(CellKey.BLANK) : out;
            }
        }
        return List.of(value(d, row));
    }

    /** A row's value of a dimension as the cell keys write it (the tabular provider's formatting, for the same answers). */
    String value(Dim d, Object[] row) {
        Integer i = at.get(d.field());
        Object raw = i == null || i >= row.length ? null : row[i];
        if (d.joined() || raw instanceof java.util.Collection<?>) {
            raw = ListValues.joined(raw);                           // a list as one label: "fx, rates"
        }
        if (raw == null || String.valueOf(raw).isBlank()) {
            return CellKey.BLANK;
        }
        if (d.width() > 0) {
            Double x = number(raw);
            if (x == null) {
                return CellKey.BLANK;
            }
            double b = Math.floor(x / d.width()) * d.width();
            return d.width() == Math.rint(d.width()) ? Long.toString((long) b) : Double.toString(b);
        }
        if (d.joined()) {
            return String.valueOf(raw);
        }
        if (d.group() != null || "date".equals(type(d.field()))) {
            String s = String.valueOf(raw);
            LocalDate day;
            try {
                day = LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
            } catch (DateTimeParseException e) {
                return d.group() == null ? s : CellKey.BLANK;
            }
            if (d.group() == null || "day".equals(d.group())) {
                return day.toString();
            }
            return switch (d.group()) {
                case "week" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
                case "month" -> day.toString().substring(0, 7);
                case "quarter" -> day.getYear() + "-Q" + ((day.getMonthValue() - 1) / 3 + 1);
                default -> Integer.toString(day.getYear());
            };
        }
        if ("number".equals(type(d.field()))) {
            Double x = number(raw);
            if (x == null) {
                return String.valueOf(raw);
            }
            return x == Math.floor(x) && Math.abs(x) < 1e15 ? Long.toString(x.longValue()) : Double.toString(x);
        }
        return String.valueOf(raw);
    }

    private static Double number(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(raw).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether a row is in the set: a list attribute's filter keeps a row with any of the chosen values and none of the left-out ones. */
    private boolean keep(AggregationPlan plan, CellSet set, Object[] row) {
        for (Map.Entry<String, AggregationPlan.Filter> f : plan.filters().entrySet()) {
            Dim d = plan.dims().get(f.getKey());
            List<String> v = d == null ? List.of(CellKey.BLANK) : values(d, row);
            if (!f.getValue().in().isEmpty() && v.stream().noneMatch(f.getValue().in()::contains)
                    || v.stream().anyMatch(f.getValue().notIn()::contains)) {
                return false;
            }
        }
        for (Map.Entry<String, String> a : set.at().entrySet()) {
            if (!values(plan.dims().get(a.getKey()), row).contains(a.getValue())) {
                return false;
            }
        }
        for (Map.Entry<String, List<String>> ex : set.exclude().entrySet()) {
            if (values(plan.dims().get(ex.getKey()), row).stream().anyMatch(ex.getValue()::contains)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Partials evaluate(Viewer v, AggregationPlan plan) {
        long t0 = System.nanoTime();
        List<Partials.SetResult> out = new ArrayList<>();
        for (CellSet set : plan.sets()) {
            Map<CellKey, List<Object[]>> groups = new LinkedHashMap<>();
            for (Object[] row : rows) {
                if (!keep(plan, set, row)) {
                    continue;
                }
                // the row's cells: one, or one per value of each list attribute grouped by (explode; a row in several cells)
                List<Map<String, String>> all = new ArrayList<>(List.of(new TreeMap<>()));
                for (String g : set.grouping()) {
                    List<String> vs = values(plan.dims().get(g), row);
                    List<Map<String, String>> next = new ArrayList<>(all.size() * vs.size());
                    for (Map<String, String> c : all) {
                        for (String x : vs) {
                            Map<String, String> m = new TreeMap<>(c);
                            m.put(g, x);
                            next.add(m);
                        }
                    }
                    all = next;
                }
                for (Map<String, String> coords : all) {
                    groups.computeIfAbsent(set.key(coords), k -> new ArrayList<>()).add(row);
                }
            }
            TreeMap<CellKey, Partials.Entry> cells = new TreeMap<>();
            for (Map.Entry<CellKey, List<Object[]>> g : groups.entrySet()) {
                Object[] states = new Object[plan.functions().size()];
                for (int f = 0; f < states.length; f++) {
                    FunctionCall fc = plan.functions().get(f);
                    states[f] = fc.fn().add(fc.fn().start(), batch(fc, g.getValue()));
                }
                cells.put(g.getKey(), new Partials.Entry(g.getValue().size(), states));
            }
            out.add(new Partials.SetResult(set, cells));
        }
        return new Partials(out, new Partials.Stats(plan.sets().size(), List.of(plan.sets().size() + " cell set(s) over " + rows.size()
                + " rows held in memory"), (System.nanoTime() - t0) / 1_000_000, rows.size()));
    }

    /** The function's input columns over a cell's rows. */
    private Batch batch(FunctionCall fc, List<Object[]> members) {
        Object[] cols = new Object[fc.inputs().size()];
        for (int k = 0; k < cols.length; k++) {
            Integer i = at.get(fc.inputs().get(k));
            if (fc.fn().numeric()) {
                double[] x = new double[members.size()];
                for (int r = 0; r < x.length; r++) {
                    Object raw = i == null ? null : members.get(r)[i];
                    Double n = raw == null ? null : number(raw);
                    x[r] = n == null ? Double.NaN : n;
                }
                cols[k] = x;
            } else {
                String[] x = new String[members.size()];
                for (int r = 0; r < x.length; r++) {
                    Object raw = i == null ? null : members.get(r)[i];
                    x[r] = raw == null ? null : String.valueOf(raw);
                }
                cols[k] = x;
            }
        }
        return new Batch(members.size(), cols);
    }

    @Override
    public Rows drill(Viewer v, AggregationPlan plan, CellKey key, int offset, int limit) {
        List<List<Object>> out = new ArrayList<>();
        long total = 0;
        CellSet set = CellSet.of(List.copyOf(key.coords().keySet()), key.coords());
        for (Object[] row : rows) {
            if (keep(plan, set, row)) {
                if (total >= offset && out.size() < limit) {
                    out.add(java.util.Arrays.asList(row.clone()));
                }
                total++;
            }
        }
        return new Rows(fields.stream().map(Field::name).toList(), out, total, "");
    }
}
