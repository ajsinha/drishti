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
package com.ash.drishti.server.bi.sql;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.expr.Fields;
import com.ash.drishti.server.bi.mq.ModelQuery;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Binds an MQ-shaped {@code SELECT} to a model (docs/architecture/BI_DESIGN.md section 3.4, "How it compiles"): names are resolved against the model as the
 * caller sees it (a hidden column does not exist, {@code DRS-9171}), and the statement becomes exactly one model query: the selected columns (= the
 * {@code GROUP BY}) are its fields, {@code MEASURE('name')} its measures, an inline aggregate a local measure in DEL, {@code WHERE} its row filters,
 * {@code HAVING} its measure filters, {@code ORDER BY}, {@code LIMIT}, the table clause its time; a select without {@code GROUP BY} or aggregates is a row
 * extract ({@code shape: rows}). Every refusal names its code and a fix. One binder binds one statement; not thread-safe.
 */
public final class SqlBinder {

    /** An output column: its name in the answer, the model query's column it comes from, and whether it is a measure. */
    public record Out(String name, String source, boolean measure) {}

    /** One MQ-shaped select: the model query, the answer's columns in the select's order, the rows to skip ({@code OFFSET}), and its table clause. */
    public record Leaf(ModelQuery query, List<Out> columns, long offset, SqlAst.TimeClause time) {}

    private final Dataset flat;
    private final Fields fields;
    private final String model;

    public SqlBinder(Dataset flat, Fields fields, String model) {
        this.flat = flat;
        this.fields = fields;
        this.model = model;
    }

    /** True when a select reads only the model's tables (it can be one model query). */
    boolean readsModel(SqlAst.Select s, Set<String> ctes) {
        return !s.from().isEmpty() && s.from().stream().allMatch(f -> f.subquery() == null && f.table() != null && !ctes.contains(f.table()));
    }

    // ---- one select ------------------------------------------------------------------------------------------------------

    public Leaf leaf(SqlAst.Select s) {
        Map<String, String> names = from(s);
        String fact = flat.tables().containsKey(s.from().get(0).table()) ? s.from().get(0).table() : names.values().iterator().next();
        SqlToDel del = new SqlToDel(c -> field(c, names), fact);
        List<String> fieldList = new ArrayList<>();
        List<String> measureList = new ArrayList<>();
        Map<String, String> define = new LinkedHashMap<>();
        List<Out> outs = new ArrayList<>();
        Map<String, String> byAlias = new LinkedHashMap<>();
        Map<String, String> byText = new LinkedHashMap<>();
        boolean aggregates = false;
        for (SqlAst.Item it : s.items()) {
            if (it.star()) {
                throw new DsqlException(DsqlException.SHAPE, "SELECT * is not Drishti SQL", "name the columns: SELECT trades.desk, MEASURE('MTM') ...", it.at());
            }
            SqlAst.Expr e = it.expr();
            if (SqlAst.any(e, x -> x instanceof SqlAst.Call c && c.over() != null)) {
                throw new DsqlException(DsqlException.WINDOW, "a window function runs over the answer's rows", "the window is computed over the answer (a composite statement)", it.at());
            }
            SqlAst.walk(e, x -> {
                if (x instanceof SqlAst.Call call && !SqlFunctions.AGGREGATES.contains(call.name()) && !SqlFunctions.SCALARS.contains(call.name())
                        && !SqlFunctions.WINDOWS.contains(call.name())) {
                    throw SqlFunctions.refused(call.name(), call.at());
                }
            });
            String source;
            boolean measure;
            if (e instanceof SqlAst.Column c) {
                source = field(c, names);
                measure = false;
                if (fieldList.contains(source)) {
                    throw new DsqlException(DsqlException.SHAPE, "'" + source + "' is selected twice", null, it.at());
                }
                fieldList.add(source);
            } else if (e instanceof SqlAst.Measure m) {
                source = measureName(m);
                measure = true;
                aggregates = true;
                measureList.add(source);
            } else if (isAggregate(e)) {
                aggregates = true;
                measure = true;
                source = it.alias() != null ? it.alias() : text(e);
                if (flat.measures().containsKey(source)) {
                    source = source + " (sql)";
                }
                define.put(source, del.del(e));
                measureList.add(source);
            } else if (e instanceof SqlAst.Literal) {
                throw new DsqlException(DsqlException.SHAPE, "a constant column is not Drishti SQL", "select fields and measures", it.at());
            } else {
                throw new DsqlException(DsqlException.SHAPE, "a computed row column (" + text(e) + ") is not Drishti SQL",
                        "make it a derived column of the dataset (derive: { expr: ... }), or compute it over the answer in a WITH query", it.at());
            }
            String name = unique(it.alias() != null ? it.alias() : e instanceof SqlAst.Column c ? c.text() : source, outs);
            outs.add(new Out(name, source, measure));
            byAlias.put(name.toLowerCase(Locale.ROOT), source);
            byText.put(text(e), source);
        }
        boolean grouped = !s.groupBy().isEmpty() || aggregates;
        if (!s.groupBy().isEmpty()) {
            Set<String> group = new LinkedHashSet<>();
            for (SqlAst.Expr g : s.groupBy()) {
                group.add(groupKey(g, names, outs, byAlias));
            }
            for (String f : fieldList) {
                if (!group.contains(f)) {
                    throw new DsqlException(DsqlException.SHAPE, "'" + f + "' is selected but not in GROUP BY", "add it to GROUP BY", s.at());
                }
            }
            for (String g : group) {
                if (!fieldList.contains(g)) {
                    throw new DsqlException(DsqlException.SHAPE, "'" + g + "' is in GROUP BY but not selected: a model query answers the fields it groups by", "select it",
                            s.groupBy().get(0).at());
                }
            }
        } else if (aggregates && !fieldList.isEmpty()) {
            throw new DsqlException(DsqlException.SHAPE, "the columns " + fieldList + " are selected next to aggregates without GROUP BY", "add GROUP BY " + String.join(", ", fieldList),
                    s.at());
        }
        if (s.distinct() && !grouped) {
            throw new DsqlException(DsqlException.DISTINCT, "SELECT DISTINCT on a fact without grouping would silently change its grain",
                    "GROUP BY " + String.join(", ", fieldList), s.at());
        }
        List<ModelQuery.Where> where = new ArrayList<>();
        for (SqlAst.Expr p : conjuncts(s.where())) {
            where.addAll(rowFilter(p, names));
        }
        List<ModelQuery.Having> having = new ArrayList<>();
        for (SqlAst.Expr p : conjuncts(s.having())) {
            having.add(measureFilter(p, del, define, measureList, outs, byAlias, byText));
        }
        List<ModelQuery.Sort> sort = new ArrayList<>();
        for (SqlAst.Order o : s.orderBy()) {
            sort.add(new ModelQuery.Sort(orderKey(o.expr(), names, outs, byAlias, byText), o.desc()));
        }
        long offset = s.offset() == null ? 0 : s.offset();
        Integer limit = s.limit() == null ? null : (int) Math.min(Integer.MAX_VALUE, s.limit() + offset);
        ModelQuery q = new ModelQuery(1, model, define, fieldList, measureList, where, having, time(s.time()), sort, limit, null, null, grouped ? ModelQuery.GROUPED : ModelQuery.ROWS,
                Map.of(), new ModelQuery.Options("auto", false, "json"));
        return new Leaf(q, outs, offset, s.time());
    }

    /** {@code FROM}: each table a model alias (renamed by its own alias), each join along a relationship of the model. */
    private Map<String, String> from(SqlAst.Select s) {
        Map<String, String> names = new LinkedHashMap<>();
        for (SqlAst.From f : s.from()) {
            if (f.subquery() != null) {
                throw new DsqlException(DsqlException.SHAPE, "a subquery here makes the statement composite", null, f.at());
            }
            if (!flat.tables().containsKey(f.table())) {
                throw new DsqlException(f.table().contains(".") || f.table().startsWith("pack") ? DsqlException.SCOPE : DsqlException.NAME, "no table '" + f.table()
                        + "' in this model: its tables are " + flat.tables().keySet(), "FROM " + flat.tables().keySet().iterator().next(), f.at(), f.at() + f.table().length());
            }
            names.put(f.name(), f.table());
            if (!f.name().equals(f.table())) {
                names.putIfAbsent(f.table(), f.table());
            }
        }
        for (SqlAst.From f : s.from()) {
            if (f.on() != null) {
                checkJoin(f, names);
            }
        }
        return names;
    }

    private void checkJoin(SqlAst.From f, Map<String, String> names) {
        if (f.on() instanceof SqlAst.Binary b && "=".equals(b.op()) && b.left() instanceof SqlAst.Column l && b.right() instanceof SqlAst.Column r && l.parts().size() == 2
                && r.parts().size() == 2) {
            String lt = names.get(l.parts().get(0));
            String rt = names.get(r.parts().get(0));
            for (Dataset.Relationship rel : flat.relationships()) {
                boolean forward = rel.fromTable().equals(lt) && rel.fromColumn().equals(l.parts().get(1)) && rel.toTable().equals(rt) && rel.toColumn().equals(r.parts().get(1));
                boolean backward = rel.fromTable().equals(rt) && rel.fromColumn().equals(r.parts().get(1)) && rel.toTable().equals(lt) && rel.toColumn().equals(l.parts().get(1));
                if ((forward || backward) && rel.active()) {
                    return;
                }
            }
        }
        throw new DsqlException(DsqlException.JOIN, "the join's ON is not a relationship of the model", "JOIN " + f.name() + " without ON uses the model's relationship; or ask the model owner for one",
                f.on().at());
    }

    /** The model field a column names, as the caller sees the model. */
    String field(SqlAst.Column c, Map<String, String> names) {
        List<String> parts = new ArrayList<>(c.parts());
        if (parts.size() >= 2 && names.containsKey(parts.get(0))) {
            parts.set(0, names.get(parts.get(0)));
        } else if (parts.size() >= 2 && flat.tables().containsKey(parts.get(0))) {
            throw new DsqlException(DsqlException.NAME, "'" + c.text() + "' names the table " + parts.get(0) + ", which the statement does not read", "JOIN " + parts.get(0), c.at(),
                    c.at() + c.text().length());
        } else if (parts.size() == 1 || !flat.tables().containsKey(parts.get(0))) {
            List<String> found = new ArrayList<>();
            for (String t : new LinkedHashSet<>(names.values())) {
                List<String> withTable = new ArrayList<>(List.of(t));
                withTable.addAll(parts);
                if (fields.field(withTable).isPresent()) {
                    found.add(t);
                }
            }
            if (found.size() > 1) {
                throw new DsqlException(DsqlException.NAME, "'" + c.text() + "' is a column of " + found + ": say which", found.get(0) + "." + c.text(), c.at(), c.at() + c.text().length());
            }
            if (found.size() == 1) {
                parts.add(0, found.get(0));
            }
        }
        return fields.field(parts).map(Fields.Field::path).orElseThrow(() -> new DsqlException(DsqlException.NAME, "no column '" + c.text() + "' in "
                + new LinkedHashSet<>(names.values()), null, c.at(), c.at() + c.text().length()));
    }

    private String measureName(SqlAst.Measure m) {
        return fields.measure(m.name()).orElseThrow(() -> new DsqlException(DsqlException.NAME, "no measure '" + m.name() + "' in this model", null, m.at()));
    }

    static boolean isAggregate(SqlAst.Expr e) {
        return SqlAst.any(e, x -> x instanceof SqlAst.Measure || x instanceof SqlAst.Call c && SqlFunctions.AGGREGATES.contains(c.name()) && c.over() == null);
    }

    private static String unique(String name, List<Out> outs) {
        String n = name;
        int i = 2;
        while (true) {
            String candidate = n;
            if (outs.stream().noneMatch(o -> o.name().equalsIgnoreCase(candidate))) {
                return n;
            }
            n = name + "_" + i++;
        }
    }

    private String groupKey(SqlAst.Expr g, Map<String, String> names, List<Out> outs, Map<String, String> byAlias) {
        if (g instanceof SqlAst.Literal l && l.value() instanceof Long n && n >= 1 && n <= outs.size()) {
            return outs.get((int) (n - 1)).source();
        }
        if (g instanceof SqlAst.Column c && c.parts().size() == 1 && byAlias.containsKey(c.text().toLowerCase(Locale.ROOT))) {
            return byAlias.get(c.text().toLowerCase(Locale.ROOT));
        }
        if (g instanceof SqlAst.Column c) {
            return field(c, names);
        }
        throw new DsqlException(DsqlException.SHAPE, "GROUP BY takes columns (or their positions or names in the SELECT list)", null, g.at());
    }

    private String orderKey(SqlAst.Expr e, Map<String, String> names, List<Out> outs, Map<String, String> byAlias, Map<String, String> byText) {
        if (e instanceof SqlAst.Literal l && l.value() instanceof Long n && n >= 1 && n <= outs.size()) {
            return outs.get((int) (n - 1)).source();
        }
        if (e instanceof SqlAst.Column c && c.parts().size() == 1 && byAlias.containsKey(c.text().toLowerCase(Locale.ROOT))) {
            return byAlias.get(c.text().toLowerCase(Locale.ROOT));
        }
        if (byText.containsKey(text(e))) {
            return byText.get(text(e));
        }
        if (e instanceof SqlAst.Measure m) {
            return measureName(m);
        }
        if (e instanceof SqlAst.Column c) {
            return field(c, names);
        }
        throw new DsqlException(DsqlException.SHAPE, "ORDER BY takes a selected column or measure (its name, position or expression)", null, e.at());
    }

    /** The ANDed parts of a condition. */
    static List<SqlAst.Expr> conjuncts(SqlAst.Expr e) {
        List<SqlAst.Expr> out = new ArrayList<>();
        if (e == null) {
            return out;
        }
        if (e instanceof SqlAst.Binary b && "AND".equals(b.op())) {
            out.addAll(conjuncts(b.left()));
            out.addAll(conjuncts(b.right()));
        } else {
            out.add(e);
        }
        return out;
    }

    // ---- WHERE -------------------------------------------------------------------------------------------------------------

    private List<ModelQuery.Where> rowFilter(SqlAst.Expr p, Map<String, String> names) {
        if (isAggregate(p)) {
            throw new DsqlException(DsqlException.MEASURE, "a measure or aggregate in WHERE: WHERE filters rows, before anything is aggregated", "use HAVING", p.at());
        }
        return switch (p) {
            case SqlAst.Binary b when isComparison(b.op()) -> {
                boolean flipped = !(b.left() instanceof SqlAst.Column) && b.right() instanceof SqlAst.Column;
                SqlAst.Expr col = flipped ? b.right() : b.left();
                SqlAst.Expr val = flipped ? b.left() : b.right();
                if (!(col instanceof SqlAst.Column c) || !(val instanceof SqlAst.Literal l)) {
                    throw notSimple(p);
                }
                if (l.value() == null) {
                    throw new DsqlException(DsqlException.SHAPE, "a comparison with NULL is never true", "IS NULL / IS NOT NULL", p.at());
                }
                yield List.of(ModelQuery.Where.of(field(c, names), op(flipped ? flip(b.op()) : b.op()), List.of(value(l))));
            }
            case SqlAst.In i when i.value() instanceof SqlAst.Column c -> {
                List<Object> values = new ArrayList<>();
                for (SqlAst.Expr x : i.items()) {
                    if (x instanceof SqlAst.SubqueryExpr s) {
                        throw new DsqlException(DsqlException.SHAPE, "IN (SELECT ...) is not Drishti SQL: a semi-join is a relationship of the model", "JOIN the table, or filter its field",
                                s.at());
                    }
                    if (!(x instanceof SqlAst.Literal l)) {
                        throw notSimple(p);
                    }
                    values.add(value(l));
                }
                yield List.of(ModelQuery.Where.of(field(c, names), i.negated() ? "not-in" : "in", values));
            }
            case SqlAst.Between b when b.value() instanceof SqlAst.Column c && b.low() instanceof SqlAst.Literal lo && b.high() instanceof SqlAst.Literal hi -> {
                if (b.negated()) {
                    throw notSimple(p);
                }
                yield List.of(ModelQuery.Where.of(field(c, names), "between", List.of(value(lo), value(hi))));
            }
            case SqlAst.IsNull n when n.value() instanceof SqlAst.Column c -> List.of(ModelQuery.Where.of(field(c, names), n.negated() ? "not-null" : "is-null", List.of()));
            case SqlAst.Like l when l.value() instanceof SqlAst.Column c && l.pattern() instanceof SqlAst.Literal pat && pat.value() instanceof String s && !l.negated() -> {
                if (s.startsWith("%") && s.endsWith("%") && s.length() > 1 && !s.substring(1, s.length() - 1).contains("%")) {
                    yield List.of(ModelQuery.Where.of(field(c, names), "contains", List.of(s.substring(1, s.length() - 1))));
                }
                if (s.endsWith("%") && !s.substring(0, s.length() - 1).contains("%")) {
                    yield List.of(ModelQuery.Where.of(field(c, names), "starts-with", List.of(s.substring(0, s.length() - 1))));
                }
                if (!s.contains("%")) {
                    yield List.of(ModelQuery.Where.of(field(c, names), "eq", List.of(s)));
                }
                throw notSimple(p);
            }
            case SqlAst.Binary b when "OR".equals(b.op()) -> List.of(sameFieldOr(b, names));
            default -> throw notSimple(p);
        };
    }

    /** {@code a = 'x' OR a = 'y'} on one field: {@code a IN ('x', 'y')}. */
    private ModelQuery.Where sameFieldOr(SqlAst.Binary b, Map<String, String> names) {
        List<SqlAst.Expr> parts = new ArrayList<>();
        disjuncts(b, parts);
        String f = null;
        List<Object> values = new ArrayList<>();
        for (SqlAst.Expr x : parts) {
            if (!(x instanceof SqlAst.Binary eq) || !"=".equals(eq.op()) || !(eq.left() instanceof SqlAst.Column c) || !(eq.right() instanceof SqlAst.Literal l)) {
                throw notSimple(b);
            }
            String field = field(c, names);
            if (f != null && !f.equals(field)) {
                throw notSimple(b);
            }
            f = field;
            values.add(value(l));
        }
        return ModelQuery.Where.of(f, "in", values);
    }

    private static void disjuncts(SqlAst.Expr e, List<SqlAst.Expr> out) {
        if (e instanceof SqlAst.Binary b && "OR".equals(b.op())) {
            disjuncts(b.left(), out);
            disjuncts(b.right(), out);
        } else {
            out.add(e);
        }
    }

    private static DsqlException notSimple(SqlAst.Expr p) {
        return new DsqlException(DsqlException.SHAPE, "WHERE compares a field with values (=, <>, <, <=, >, >=, IN, BETWEEN, LIKE 'x%', IS NULL), ANDed; an OR across fields or an "
                + "expression on a field is not a row filter of wave A", "make the expression a derived column of the dataset (derive), then filter on it", p.at());
    }

    static boolean isComparison(String op) {
        return List.of("=", "<>", "<", "<=", ">", ">=").contains(op);
    }

    static String op(String sql) {
        return switch (sql) {
            case "=" -> "eq";
            case "<>" -> "ne";
            case "<" -> "lt";
            case "<=" -> "le";
            case ">" -> "gt";
            default -> "ge";
        };
    }

    static String flip(String sql) {
        return switch (sql) {
            case "<" -> ">";
            case "<=" -> ">=";
            case ">" -> "<";
            case ">=" -> "<=";
            default -> sql;
        };
    }

    static Object value(SqlAst.Literal l) {
        return l.value() instanceof Boolean b ? b : l.value();
    }

    // ---- HAVING ------------------------------------------------------------------------------------------------------------

    private ModelQuery.Having measureFilter(SqlAst.Expr p, SqlToDel del, Map<String, String> define, List<String> measures, List<Out> outs, Map<String, String> byAlias,
            Map<String, String> byText) {
        if (!(p instanceof SqlAst.Binary b) || !isComparison(b.op())) {
            throw new DsqlException(DsqlException.SHAPE, "HAVING compares a measure with a number (=, <>, <, <=, >, >=), ANDed", null, p.at());
        }
        boolean flipped = b.left() instanceof SqlAst.Literal;
        SqlAst.Expr m = flipped ? b.right() : b.left();
        SqlAst.Expr v = flipped ? b.left() : b.right();
        if (!(v instanceof SqlAst.Literal l) || !(l.value() instanceof Number)) {
            throw new DsqlException(DsqlException.SHAPE, "HAVING compares a measure with a number", null, p.at());
        }
        String name;
        if (m instanceof SqlAst.Measure ms) {
            name = measureName(ms);
        } else if (m instanceof SqlAst.Column c && c.parts().size() == 1 && byAlias.containsKey(c.text().toLowerCase(Locale.ROOT))) {
            name = byAlias.get(c.text().toLowerCase(Locale.ROOT));
            if (outs.stream().noneMatch(o -> o.source().equals(name) && o.measure())) {
                throw new DsqlException(DsqlException.SHAPE, "HAVING filters measures; '" + c.text() + "' is a field", "move it to WHERE", p.at());
            }
        } else if (byText.containsKey(text(m))) {
            name = byText.get(text(m));
        } else if (isAggregate(m)) {
            name = "having: " + text(m);
            define.put(name, del.del(m));
        } else {
            throw new DsqlException(DsqlException.SHAPE, "HAVING filters measures (MEASURE('x'), an aggregate, or a selected measure's name)", "move a field's filter to WHERE", p.at());
        }
        return new ModelQuery.Having(name, op(flipped ? flip(b.op()) : b.op()), List.of(l.value()));
    }

    // ---- time --------------------------------------------------------------------------------------------------------------

    static ModelQuery.Time time(SqlAst.TimeClause t) {
        if (t == null) {
            return ModelQuery.Time.DEFAULT;
        }
        try {
            return switch (t.kind()) {
                case "as-of" -> new ModelQuery.Time(LocalDate.parse(t.asOf()), null, null, null, false);
                case "live" -> new ModelQuery.Time(null, null, null, null, true);
                case "window" -> new ModelQuery.Time(null, LocalDate.parse(t.from()), LocalDate.parse(t.to()), null, false);
                default -> new ModelQuery.Time(null, null, null, last(t), false);
            };
        } catch (java.time.format.DateTimeParseException e) {
            throw new DsqlException(DsqlException.TIME, "the time clause's date is not YYYY-MM-DD", "AS OF DATE '2026-10-09'", t.at());
        }
    }

    private static Duration last(SqlAst.TimeClause t) {
        String[] p = t.last().split(" ");
        long n = Long.parseLong(p[0]);
        String unit = p[1].endsWith("S") ? p[1].substring(0, p[1].length() - 1) : p[1];
        return switch (unit) {
            case "SECOND" -> Duration.ofSeconds(n);
            case "MINUTE" -> Duration.ofMinutes(n);
            case "HOUR" -> Duration.ofHours(n);
            case "DAY" -> Duration.ofDays(n);
            default -> throw new DsqlException(DsqlException.TIME, "FOR WINDOW LAST takes SECOND, MINUTE, HOUR or DAY", null, t.at());
        };
    }

    // ---- text ------------------------------------------------------------------------------------------------------------

    /** A short text of an expression (an output column's default name, and how ORDER BY finds a selected expression). */
    static String text(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.Column c -> c.text();
            case SqlAst.Literal l -> String.valueOf(l.value());
            case SqlAst.Measure m -> "MEASURE('" + m.name() + "')";
            case SqlAst.Call c -> c.name() + "(" + (c.distinct() ? "DISTINCT " : "") + (c.star() ? "*" : String.join(", ", c.args().stream().map(SqlBinder::text).toList())) + ")"
                    + (c.within() == null ? "" : " WITHIN GROUP (ORDER BY " + text(c.within()) + ")");
            case SqlAst.Binary b -> text(b.left()) + " " + b.op() + " " + text(b.right());
            case SqlAst.Unary u -> u.op() + " " + text(u.operand());
            default -> e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        };
    }
}
