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
package com.ash.drishti.bicompute.sql;

import com.ash.drishti.kernel.api.pivot.AggregationPlan;
import com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall;
import com.ash.drishti.kernel.api.pivot.CellKey;
import com.ash.drishti.kernel.api.pivot.CellSet;
import com.ash.drishti.kernel.api.pivot.Dim;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles cell sets to SQL (ARCHITECTURE.md section 26.4): every set that shares a restriction shape (the same restricted dimensions, the
 * same exclusions and marks) goes into one statement, its groupings as {@code GROUP BY GROUPING SETS (...)} and its parents' coordinates as
 * one condition, so the cells, every subtotal level and the grand total come from one scan. The statement computes each function's
 * <em>state</em> (sums and counts, Welford's parts, a holistic figure of the set's own rows), never figures merged from other sets.
 *
 * <p>Every value is a bound parameter, never spliced; field names come from the source's catalogue only (quoted); the person's row rules
 * are applied first, inside the base relation, before any filter or grouping.
 */
public final class GroupingSetsCompiler {

    /** One compiled statement: its SQL, parameters, the dimension columns in order, and the sets it answers. */
    public record Compiled(String sql, List<Object> params, List<String> dims, List<CellSet> sets, int stateColumns) {}

    /** The two guard columns a statement ends with when the plan asks whether each cell is in one currency. */
    public static final List<String> GUARD = List.of("min(\"_ccy\")", "max(\"_ccy\")");

    private GroupingSetsCompiler() {}

    /** The statements of a plan: sets grouped by restriction shape, one statement each. */
    public static List<Compiled> compile(AggregationPlan plan, TabularSource.Relation rel, Map<String, String> types) {
        Map<String, List<CellSet>> byShape = new LinkedHashMap<>();
        for (CellSet s : plan.sets()) {
            String shape = "at=" + String.join(",", s.atDims()) + ";ex=" + s.exclude() + ";mk=" + s.marks();
            byShape.computeIfAbsent(shape, k -> new ArrayList<>()).add(s);
        }
        List<Compiled> out = new ArrayList<>();
        for (List<CellSet> group : byShape.values()) {
            out.add(compileGroup(plan, rel, types, group));
        }
        return out;
    }

    /** The SQL expression of a dimension over the base relation's column (text; a missing value is {@link CellKey#BLANK}). */
    public static String dimExpr(Dim d, String type) {
        String col = DuckDbExecutor.quote(d.field());
        String e;
        if (d.width() > 0) {
            double w = d.width();
            boolean whole = w == Math.rint(w);
            String b = "floor(TRY_CAST(" + col + " AS DOUBLE) / " + num(w) + ") * " + num(w);
            e = whole ? "CAST(CAST(" + b + " AS BIGINT) AS VARCHAR)" : "CAST(" + b + " AS VARCHAR)";
        } else if ("list".equals(type) || d.joined()) {
            // a list attribute: a native list<string> column or its JSON text (["fx","rates"]) both cast to a list; a single value is a
            // list of one. Exploded, each value is its own member (a row in several cells; the answer's note says totals may count a row
            // twice); joined (TAGS:join), one label "fx, rates"
            String list = "COALESCE(TRY_CAST(" + col + " AS VARCHAR[]), CASE WHEN " + col + " IS NULL THEN NULL ELSE [CAST(" + col
                    + " AS VARCHAR)] END)";
            e = d.joined() ? "NULLIF(array_to_string(" + list + ", ', '), '')"
                    : "unnest(CASE WHEN len(list_distinct(" + list + ")) > 0 THEN list_distinct(" + list + ") ELSE [NULL] END)";
        } else if (d.group() != null) {
            String dt = "TRY_CAST(" + col + " AS DATE)";
            e = switch (d.group()) {
                case "day" -> "strftime(" + dt + ", '%Y-%m-%d')";
                case "week" -> "strftime(date_trunc('week', " + dt + "), '%Y-%m-%d')";
                case "month" -> "strftime(" + dt + ", '%Y-%m')";
                case "quarter" -> "CAST(year(" + dt + ") AS VARCHAR) || '-Q' || CAST(quarter(" + dt + ") AS VARCHAR)";
                default -> "CAST(year(" + dt + ") AS VARCHAR)";
            };
        } else if ("date".equals(type)) {
            e = "COALESCE(strftime(TRY_CAST(" + col + " AS DATE), '%Y-%m-%d'), CAST(" + col + " AS VARCHAR))";
        } else if ("number".equals(type)) {
            e = "CASE WHEN TRY_CAST(" + col + " AS DOUBLE) = floor(TRY_CAST(" + col + " AS DOUBLE)) AND abs(TRY_CAST(" + col
                    + " AS DOUBLE)) < 1e15 THEN CAST(CAST(TRY_CAST(" + col + " AS DOUBLE) AS BIGINT) AS VARCHAR) ELSE CAST(TRY_CAST(" + col
                    + " AS DOUBLE) AS VARCHAR) END";
        } else {
            e = "CAST(" + col + " AS VARCHAR)";
        }
        return "COALESCE(" + e + ", '" + CellKey.BLANK + "')";
    }

    private static String num(double w) {
        return w == Math.rint(w) ? Long.toString((long) w) : Double.toString(w);
    }

    /** A function's input expression over the base relation. */
    static String inputExpr(FunctionCall f, String field) {
        String col = DuckDbExecutor.quote(field);
        return f.fn().numeric() ? "TRY_CAST(" + col + " AS DOUBLE)" : col;
    }

    /**
     * A money input converted into the report currency at the view's rates (codes and rates are checked: three capital letters and positive
     * numbers, written as literals); a row in a currency without a rate gives no value.
     */
    static String converted(AggregationPlan plan, FunctionCall f, String field) {
        String raw = inputExpr(f, field);
        AggregationPlan.Money m = plan.money();
        if (m.conversion() == null || !m.fields().contains(field) || !plan.money().money(f)) {
            return raw;
        }
        String ccyField = m.conversion().field() != null ? m.conversion().field() : m.currencyField();
        if (ccyField == null) {
            return raw;
        }
        StringBuilder c = new StringBuilder("CASE upper(CAST(" + DuckDbExecutor.quote(ccyField) + " AS VARCHAR))");
        c.append(" WHEN '").append(m.conversion().report()).append("' THEN 1.0");
        m.conversion().rates().forEach((k, v) -> {
            if (!k.equals(m.conversion().report())) {
                c.append(" WHEN '").append(k).append("' THEN ").append(Double.toString(v));
            }
        });
        c.append(" ELSE NULL END");
        return "(" + raw + " * " + c + ")";
    }

    /** The base relation: the source with the row rules applied, its dimensions and inputs as columns {@code d0..}, {@code i0..}. */
    static String base(AggregationPlan plan, TabularSource.Relation rel, Map<String, String> types, List<String> dims, List<Object> params,
            List<String> extraWhere) {
        List<String> cols = new ArrayList<>();
        for (int i = 0; i < dims.size(); i++) {
            Dim d = plan.dims().get(dims.get(i));
            cols.add(dimExpr(d, types.getOrDefault(d.field(), "string")) + " AS \"d" + i + "\"");
        }
        int k = 0;
        for (FunctionCall f : plan.functions()) {
            for (String in : f.inputs()) {
                cols.add(converted(plan, f, in) + " AS \"i" + k++ + "\"");
            }
        }
        if (plan.money().guard()) {
            cols.add("CAST(" + DuckDbExecutor.quote(plan.money().currencyField()) + " AS VARCHAR) AS \"_ccy\"");
        }
        cols.add("1 AS \"_one\"");
        params.addAll(rel.params());
        StringBuilder b = new StringBuilder("SELECT ").append(String.join(", ", cols)).append(" FROM ").append(rel.from()).append(" AS \"t\"");
        List<String> where = new ArrayList<>();
        if (rel.rowRules() != null && !rel.rowRules().isBlank()) {
            where.add("(" + rel.rowRules() + ")");
            params.addAll(rel.ruleParams());
        }
        if (plan.shard() != null && plan.shard().count() > 1) {
            List<String> all = new ArrayList<>();
            types.keySet().stream().sorted().forEach(n -> all.add("CAST(" + DuckDbExecutor.quote(n) + " AS VARCHAR)"));
            where.add("hash(" + String.join(", ", all) + ") % " + plan.shard().count() + " = " + plan.shard().index());
        }
        // the filters again on the raw columns, so a Parquet reader skips row groups and files by their statistics (the grouped
        // expressions below are the filter of record; these only let the scan read less)
        for (Map.Entry<String, AggregationPlan.Filter> f : plan.filters().entrySet()) {
            Dim d = plan.dims().get(f.getKey());
            if (d == null || d.group() != null || !"string".equals(types.getOrDefault(d.field(), "string")) || f.getValue().in().isEmpty()
                    || f.getValue().in().contains(CellKey.BLANK)) {
                continue;
            }
            where.add(DuckDbExecutor.quote(d.field()) + " IN (" + marks(f.getValue().in().size()) + ")");
            params.addAll(f.getValue().in());
        }
        where.addAll(extraWhere);
        if (!where.isEmpty()) {
            b.append(" WHERE ").append(String.join(" AND ", where));
        }
        return b.toString();
    }

    private static Compiled compileGroup(AggregationPlan plan, TabularSource.Relation rel, Map<String, String> types, List<CellSet> group) {
        Set<String> used = new LinkedHashSet<>();
        for (CellSet s : group) {
            used.addAll(s.grouping());
            used.addAll(s.at().keySet());
            used.addAll(s.exclude().keySet());
        }
        used.addAll(plan.filters().keySet());
        List<String> dims = new ArrayList<>(used);
        dims.removeIf(d -> !plan.dims().containsKey(d));
        // the grouped dimensions first: only they are selected and named in GROUPING(); a dimension only filtered or excluded on (a
        // filter, the Others of a level) stays in the base relation for the WHERE alone
        Set<String> groupedSet = new LinkedHashSet<>();
        group.forEach(s -> groupedSet.addAll(s.grouping()));
        List<String> ordered = new ArrayList<>(dims.stream().filter(groupedSet::contains).toList());
        dims.stream().filter(d -> !groupedSet.contains(d)).forEach(ordered::add);
        dims = ordered;
        int kg = (int) dims.stream().filter(groupedSet::contains).count();
        List<Object> params = new ArrayList<>();
        String base = base(plan, rel, types, dims, params, List.of());
        List<String> where = new ArrayList<>();
        for (Map.Entry<String, AggregationPlan.Filter> f : plan.filters().entrySet()) {
            String col = "\"d" + dims.indexOf(f.getKey()) + "\"";
            if (!f.getValue().in().isEmpty()) {
                where.add(col + " IN (" + marks(f.getValue().in().size()) + ")");
                params.addAll(f.getValue().in());
            }
            if (!f.getValue().notIn().isEmpty()) {
                where.add(col + " NOT IN (" + marks(f.getValue().notIn().size()) + ")");
                params.addAll(f.getValue().notIn());
            }
        }
        CellSet first = group.get(0);
        List<String> atDims = first.atDims();
        if (!atDims.isEmpty()) {
            Set<Map<String, String>> prefixes = new LinkedHashSet<>();
            group.forEach(s -> prefixes.add(s.at()));
            List<String> ors = new ArrayList<>();
            for (Map<String, String> p : prefixes) {
                List<String> ands = new ArrayList<>();
                for (String d : atDims) {
                    ands.add("\"d" + dims.indexOf(d) + "\" = ?");
                    params.add(p.get(d));
                }
                ors.add("(" + String.join(" AND ", ands) + ")");
            }
            where.add("(" + String.join(" OR ", ors) + ")");
        }
        for (Map.Entry<String, List<String>> ex : first.exclude().entrySet()) {
            where.add("\"d" + dims.indexOf(ex.getKey()) + "\" NOT IN (" + marks(ex.getValue().size()) + ")");
            params.addAll(ex.getValue());
        }
        List<String> select = new ArrayList<>();
        boolean grouped = kg > 0;
        for (int i = 0; i < kg; i++) {
            select.add("\"d" + i + "\"");
        }
        select.add(grouped ? "GROUPING(" + String.join(", ", select) + ") AS \"_g\"" : "0 AS \"_g\"");
        select.add("count(*) AS \"_n\"");
        int k = 0;
        int states = 0;
        for (FunctionCall f : plan.functions()) {
            List<String> ins = new ArrayList<>();
            for (int j = 0; j < f.inputs().size(); j++) {
                ins.add("\"i" + k++ + "\"");
            }
            List<String> parts = f.fn().sqlState(ins);
            if (parts.isEmpty()) {
                throw new IllegalStateException(f.id() + " has no SQL form: the tabular provider cannot compute it");
            }
            for (String p : parts) {
                select.add(p + " AS \"s" + states++ + "\"");
            }
        }
        if (plan.money().guard()) {
            for (String g : GUARD) {
                select.add(g + " AS \"g" + select.size() + "\"");
            }
        }
        Set<String> sets = new LinkedHashSet<>();
        for (CellSet s : group) {
            List<String> g = new ArrayList<>();
            for (String d : dims) {
                if (s.grouping().contains(d)) {
                    g.add("\"d" + dims.indexOf(d) + "\"");
                }
            }
            sets.add("(" + String.join(", ", g) + ")");
        }
        StringBuilder sql = new StringBuilder("WITH \"base\" AS (").append(base).append(")\nSELECT ").append(String.join(", ", select))
                .append("\nFROM \"base\"");
        if (!where.isEmpty()) {
            sql.append("\nWHERE ").append(String.join(" AND ", where));
        }
        if (grouped) {
            sql.append("\nGROUP BY GROUPING SETS (").append(String.join(", ", sets)).append(")");
        }
        if (plan.maxGroups() > 0) {
            sql.append("\nLIMIT ").append(plan.maxGroups() + 1);
        }
        return new Compiled(sql.toString(), params, List.copyOf(dims.subList(0, kg)), group, states);
    }

    /** The SELECT of a cell's member rows (drill-through): the visible fields, the row rules, the filters and the cell's coordinates. */
    public static Compiled drill(AggregationPlan plan, TabularSource.Relation rel, Map<String, String> types, List<PivotProvider.Field> fields,
            Map<String, String> coords, int offset, int limit, boolean count) {
        List<String> dims = new ArrayList<>(new LinkedHashSet<>(plan.filters().keySet()));
        coords.keySet().forEach(d -> {
            if (!dims.contains(d)) {
                dims.add(d);
            }
        });
        dims.removeIf(d -> !plan.dims().containsKey(d));
        List<Object> params = new ArrayList<>(rel.params());
        List<String> cols = new ArrayList<>();
        for (PivotProvider.Field f : fields) {
            cols.add("\"t\"." + DuckDbExecutor.quote(f.name()));
        }
        for (int i = 0; i < dims.size(); i++) {
            Dim d = plan.dims().get(dims.get(i));
            cols.add(dimExpr(d, types.getOrDefault(d.field(), "string")) + " AS \"d" + i + "\"");
        }
        StringBuilder b = new StringBuilder("WITH \"base\" AS (SELECT ").append(String.join(", ", cols)).append(" FROM ").append(rel.from())
                .append(" AS \"t\"");
        if (rel.rowRules() != null && !rel.rowRules().isBlank()) {
            b.append(" WHERE (").append(rel.rowRules()).append(")");
            params.addAll(rel.ruleParams());
        }
        b.append(")\nSELECT ");
        b.append(count ? "count(*)" : String.join(", ", fields.stream().map(f -> DuckDbExecutor.quote(f.name())).toList()));
        b.append(" FROM \"base\"");
        List<String> where = new ArrayList<>();
        for (Map.Entry<String, AggregationPlan.Filter> f : plan.filters().entrySet()) {
            String col = "\"d" + dims.indexOf(f.getKey()) + "\"";
            if (!f.getValue().in().isEmpty()) {
                where.add(col + " IN (" + marks(f.getValue().in().size()) + ")");
                params.addAll(f.getValue().in());
            }
            if (!f.getValue().notIn().isEmpty()) {
                where.add(col + " NOT IN (" + marks(f.getValue().notIn().size()) + ")");
                params.addAll(f.getValue().notIn());
            }
        }
        for (Map.Entry<String, String> c : coords.entrySet()) {
            where.add("\"d" + dims.indexOf(c.getKey()) + "\" = ?");
            params.add(c.getValue());
        }
        if (!where.isEmpty()) {
            b.append(" WHERE ").append(String.join(" AND ", where));
        }
        if (!count) {
            b.append(" LIMIT ").append(limit).append(" OFFSET ").append(offset);
        }
        return new Compiled(b.toString(), params, dims, List.of(), 0);
    }

    private static String marks(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    /** The words of a cell's coordinates as a condition ({@code DESK = 'RATES' AND month = '2026-10'}), for the provenance. */
    public static String words(Map<String, String> coords) {
        if (coords.isEmpty()) {
            return "every row of the view";
        }
        List<String> w = new ArrayList<>();
        coords.forEach((k, v) -> w.add(k + " = " + (CellKey.OTHERS.equals(v) ? "(the members not in the top N)" : "'" + v + "'")));
        return String.join(" AND ", w).toLowerCase(Locale.ROOT).equals("") ? "" : String.join(" AND ", w);
    }
}
