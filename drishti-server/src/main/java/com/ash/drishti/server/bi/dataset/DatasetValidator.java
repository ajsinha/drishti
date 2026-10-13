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
package com.ash.drishti.server.bi.dataset;

import com.ash.drishti.server.bi.dataset.DatasetParser.Problem;
import com.ash.drishti.server.bi.dataset.ExprParser.ExprException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a parsed dataset against itself: relationships join columns that exist and agree in type; calculated columns, measures and row rules
 * parse, resolve every name, use aggregates where they belong and nowhere else, and measures do not refer to each other in a circle; hierarchies,
 * masked columns and rollups name real things; features the grammar reserves are refused with DRS-9013. DRS-9010 structure (the parser),
 * DRS-9011 expressions, DRS-9012 references, DRS-9013 not built.
 */
final class DatasetValidator {

    static final String EXPRESSION = "DRS-9011";
    static final String RELATION = "DRS-9012";
    static final String NOT_BUILT = "DRS-9013";
    private static final Set<String> ARITY_1_2 = Set.of("sum", "avg", "min", "max", "distinct", "count");

    private final Dataset ds;
    private final Resolver resolver;
    private final List<Problem> out = new ArrayList<>();

    private DatasetValidator(Dataset ds) {
        this.ds = ds;
        this.resolver = new Resolver(ds);
    }

    static List<Problem> validate(Dataset ds) {
        DatasetValidator v = new DatasetValidator(ds);
        v.relationships();
        v.calculatedColumns();
        v.measures();
        v.security();
        v.hierarchies();
        v.rollups();
        v.tests();
        return v.out;
    }

    private void err(String code, String path, String message) {
        out.add(new Problem("error", code, path, message));
    }

    // ---- relationships -----------------------------------------------------------------------------------------------

    private void relationships() {
        int i = 0;
        for (Dataset.Relationship r : ds.relationships()) {
            String path = "relationships[" + i++ + "]";
            Dataset.Column from = resolver.column(r.fromTable(), r.fromColumn());
            Dataset.Column to = resolver.column(r.toTable(), r.toColumn());
            if (!ds.tables().containsKey(r.fromTable()) || !ds.tables().containsKey(r.toTable())) {
                err(RELATION, path, "relationship " + r.label() + " names a table the dataset does not have (tables: " + String.join(", ", ds.tables().keySet()) + ")");
            } else if (from == null || to == null) {
                err(RELATION, path, "relationship " + r.label() + ": " + (from == null ? r.fromTable() + " has no column '" + r.fromColumn() + "'"
                        : r.toTable() + " has no column '" + r.toColumn() + "'"));
            } else if (!from.type().equals(to.type())) {
                err(RELATION, path, "relationship " + r.label() + " joins " + from.type() + " to " + to.type() + "; make both the same type");
            } else if (r.fromTable().equals(r.toTable())) {
                err(RELATION, path, "relationship " + r.label() + " joins a table to itself, which a query cannot follow");
            }
            if ("many-to-many".equals(r.cardinality())) {
                out.add(new Problem("warning", NOT_BUILT, path, "many-to-many relationships are stored but a query joins them as written, which can repeat rows"));
            }
        }
    }

    // ---- calculated columns ------------------------------------------------------------------------------------------

    private void calculatedColumns() {
        for (Dataset.Table t : ds.tables().values()) {
            Resolver resolver = t.shape() != null && t.shape().grouped() ? new Resolver(ds, true) : this.resolver;
            for (Dataset.Column c : t.calculated()) {
                String path = "tables." + t.name() + ".columns." + c.name();
                Node n = parse(path, c.expr());
                if (n == null) {
                    continue;
                }
                if (Node.hasAggregate(n)) {
                    err(EXPRESSION, path, "a calculated column is evaluated for each row and cannot aggregate; put the aggregate in a measure");
                }
                Node.walk(n, x -> {
                    if (x instanceof Node.Ref r) {
                        try {
                            Resolver.ColRef ref = resolver.resolve(r.parts(), t.name());
                            if (!ref.table().equals(t.name())) {
                                err(EXPRESSION, path, "a calculated column reads its own table; '" + r.text() + "' is in " + ref.table());
                            } else if (ref.column() != null && resolver.column(t.name(), ref.column()).expr() != null) {
                                err(EXPRESSION, path, "a calculated column cannot use another calculated column ('" + r.text() + "')");
                            }
                        } catch (IllegalArgumentException e) {
                            err(EXPRESSION, path, e.getMessage());
                        }
                    } else if (x instanceof Node.MeasureRef || x instanceof Node.UserRef) {
                        err(EXPRESSION, path, "a calculated column cannot use measures or the user");
                    }
                });
                calls(path, n);
            }
        }
    }

    // ---- measures ----------------------------------------------------------------------------------------------------

    /**
     * A Python measure names a Python item and the columns it reads ({@code inputs}) and is grouped by ({@code by}), all of one table; the query engine
     * refuses it (the console's runner computes it, for a python visual), so the model says so as a warning.
     */
    private void pythonMeasure(String path, Dataset.Measure m) {
        if (m.python().isBlank()) {
            err(RELATION, path + ".python", "python names the Python item that computes the measure (its catalogue id or file name)");
            return;
        }
        if (m.inputs().isEmpty()) {
            err(RELATION, path + ".inputs", "a Python measure lists the columns it reads: inputs: [trade.pnl1d]");
        }
        Set<String> tables = new LinkedHashSet<>();
        for (String which : List.of("inputs", "by")) {
            for (String c : "inputs".equals(which) ? m.inputs() : m.by()) {
                try {
                    Resolver.ColRef ref = resolver.resolve(List.of(c.split("\\.")), null);
                    if (ref.column() == null || ref.part() != null) {
                        err(RELATION, path + "." + which, "'" + c + "' is not a column (table.column)");
                    } else {
                        tables.add(ref.table());
                    }
                } catch (IllegalArgumentException e) {
                    err(RELATION, path + "." + which, e.getMessage());
                }
            }
        }
        if (tables.size() > 1) {
            err(RELATION, path, "a Python measure reads the columns of one table, not " + tables);
        }
        out.add(new Problem("warning", NOT_BUILT, path, "a Python measure (" + m.python() + ") is computed by the console's Python runner and shown by a python visual; a query that names it is refused"));
    }

    private void measures() {
        for (Dataset.Measure m : ds.measures().values()) {
            String path = "measures." + m.name();
            if (m.expr() == null) {
                pythonMeasure(path, m);
                continue;
            }
            Node n = parse(path, m.expr());
            if (n == null) {
                continue;
            }
            calls(path, n);
            if (!Node.hasAggregate(n) && !Node.anyMatch(n, x -> x instanceof Node.MeasureRef)) {
                err(EXPRESSION, path, "a measure aggregates: use sum(), avg(), min(), max(), count(), distinct(), ratio() or percentile()");
            }
            refs(path, n, false, null);
            cycle(m.name(), new LinkedHashSet<>(), path);
        }
    }

    /** Checks function names and arities. */
    private void calls(String path, Node root) {
        Node.walk(root, x -> {
            if (!(x instanceof Node.Call c)) {
                return;
            }
            String f = c.function();
            int a = c.args().size();
            if (Node.ANALYTIC.contains(f)) {
                analyticCall(path, c);
            } else if (Node.TIME.contains(f) || Node.WINDOW.contains(f)) {
                timeCall(path, c);
            } else if (!Node.AGGREGATES.contains(f) && !Node.SCALARS.contains(f)) {
                err(EXPRESSION, path, "unknown function '" + f + "'; aggregates: " + new java.util.TreeSet<>(Node.AGGREGATES) + ", row functions: " + new java.util.TreeSet<>(Node.SCALARS));
            } else if (ARITY_1_2.contains(f) && (a < 1 || a > 2)) {
                err(EXPRESSION, path, f + "(column) or " + f + "(column, condition) takes one or two arguments, not " + a);
            } else if ("ratio".equals(f) && a != 2) {
                err(EXPRESSION, path, "ratio(numerator, denominator) takes two arguments, not " + a);
            } else if ("percentile".equals(f) && (a < 2 || a > 3 || !(c.args().get(1) instanceof Node.Num p) || p.value() < 0 || p.value() > 1)) {
                err(EXPRESSION, path, "percentile(column, p) takes a number p from 0 to 1, for example percentile(trades.mtm, 0.95)");
            } else if (Set.of("abs", "upper", "lower", "year", "quarter", "month", "day", "length").contains(f) && a != 1) {
                err(EXPRESSION, path, f + " takes one argument");
            } else if (Set.of("contains", "startsWith").contains(f) && a != 2) {
                err(EXPRESSION, path, f + " takes two arguments");
            } else if ("if".equals(f) && a != 3) {
                err(EXPRESSION, path, "if(condition, then, else) takes three arguments");
            }
        });
    }

    /**
     * {@code lag(agg, n, order [, partition...])}, {@code moving_avg(agg, n, order [, partition...])} and {@code rank(agg ['desc' [, partition...]])} and kin: a window
     * over the rows the query returns. {@code n} is a whole number from 1 to 1000; the order and partition arguments are columns of the dataset.
     */
    private void analyticCall(String path, Node.Call c) {
        String f = c.function();
        List<Node> a = c.args();
        boolean ordered = Node.ORDERED.contains(f);
        int fixed = ordered ? 3 : 1;
        if (a.size() < fixed) {
            err(EXPRESSION, path, ordered
                    ? f + "(aggregate, n, order column [, partition columns]) takes at least three arguments, for example " + f + "(sum(trades.mtm), 1, trades.tradeDate)"
                    : f + "(aggregate ['asc'|'desc' [, partition columns]]) takes an aggregate, for example " + f + "(sum(trades.mtm), 'desc')");
            return;
        }
        Node agg = a.get(0);
        if (!Node.hasAggregate(agg)) {
            err(EXPRESSION, path, f + "() works over an aggregate: its first argument must aggregate, for example sum(trades.mtm)");
        }
        if (Node.anyMatch(agg, x -> x instanceof Node.MeasureRef || x instanceof Node.Call k && (Node.TIME.contains(k.function()) || Node.isWindow(k.function())))) {
            err(EXPRESSION, path, f + "() cannot hold another window or time function or a measure reference; write the aggregate itself");
        }
        int first = 1;
        if (ordered) {
            first = 3;
            if (!(a.get(1) instanceof Node.Num n) || !n.integral() || n.value() < 1 || n.value() > 1000) {
                err(EXPRESSION, path, f + "()'s second argument is a whole number of rows from 1 to 1000");
            }
            columnArg(path, f, a.get(2), "order");
        } else if (a.size() > 1) {
            first = 2;
            if (!(a.get(1) instanceof Node.Str s) || !Set.of("asc", "desc").contains(s.value().toLowerCase(java.util.Locale.ROOT))) {
                err(EXPRESSION, path, f + "()'s second argument is 'asc' or 'desc'");
            }
        }
        for (int i = first; i < a.size(); i++) {
            columnArg(path, f, a.get(i), "partition");
        }
    }

    private void columnArg(String path, String f, Node arg, String what) {
        if (!(arg instanceof Node.Ref ref)) {
            err(EXPRESSION, path, f + "()'s " + what + " argument is a column, for example trades.tradeDate");
            return;
        }
        try {
            Resolver.ColRef col = resolver.resolve(ref.parts(), null);
            if (col.column() == null) {
                err(EXPRESSION, path, f + "()'s " + what + " argument must be a column; '" + ref.text() + "' is a table");
            }
        } catch (IllegalArgumentException e) {
            err(EXPRESSION, path, e.getMessage());
        }
    }

    /** {@code ytd(sum(x), table.date)}: an aggregate expression and a date column; {@code running} takes one sum, count, min or max. */
    private void timeCall(String path, Node.Call c) {
        String f = c.function();
        if (c.args().size() != 2) {
            err(EXPRESSION, path, f + "(aggregate, table.dateColumn) takes two arguments, for example " + f + "(sum(trades.mtm), trades.tradeDate)");
            return;
        }
        Node agg = c.args().get(0);
        Node date = c.args().get(1);
        if (!Node.hasAggregate(agg)) {
            err(EXPRESSION, path, f + "() restricts an aggregate: its first argument must aggregate, for example sum(trades.mtm)");
        }
        boolean nested = Node.anyMatch(agg, x -> x instanceof Node.Call k && (Node.TIME.contains(k.function()) || Node.isWindow(k.function())));
        if (nested || Node.anyMatch(agg, x -> x instanceof Node.MeasureRef)) {
            err(EXPRESSION, path, f + "() cannot hold another time function or a measure reference; write the aggregate itself");
        }
        if (Node.WINDOW.contains(f) && !(agg instanceof Node.Call k && Set.of("sum", "count", "min", "max").contains(k.function()))) {
            err(EXPRESSION, path, "running() adds up along the date, so it takes one sum, count, min or max (an average, ratio, distinct count or percentile has no running total)");
        }
        if (!(date instanceof Node.Ref ref)) {
            err(EXPRESSION, path, f + "()'s second argument is a date column, for example trades.tradeDate");
            return;
        }
        try {
            Resolver.ColRef col = resolver.resolve(ref.parts(), null);
            Dataset.Column column = col.column() == null || col.part() != null ? null : resolver.column(col.table(), col.column());
            boolean temporal = column != null && ("date".equals(column.type()) || "timestamp".equals(column.type())) || Dataset.DATE.equals(col.column()) && col.part() == null;
            if (!temporal) {
                err(EXPRESSION, path, f + "()'s second argument must be a date or timestamp column; '" + ref.text() + "' is not");
            }
        } catch (IllegalArgumentException e) {
            err(EXPRESSION, path, e.getMessage());
        }
    }

    /** Every name resolves; a column outside an aggregate is an error in a measure. */
    private void refs(String path, Node n, boolean inAggregate, String current) {
        switch (n) {
            case Node.Ref r -> {
                try {
                    Resolver.ColRef ref = resolver.resolve(r.parts(), current);
                    if (!inAggregate) {
                        err(EXPRESSION, path, "'" + r.text() + "' is used outside an aggregate; write sum(" + r.text() + ") or similar");
                    } else if (ref.column() == null && !r.parts().equals(List.of(ref.table()))) {
                        err(EXPRESSION, path, "'" + r.text() + "' is not a column");
                    }
                } catch (IllegalArgumentException e) {
                    err(EXPRESSION, path, e.getMessage());
                }
            }
            case Node.MeasureRef m -> {
                if (!ds.measures().containsKey(m.name())) {
                    err(EXPRESSION, path, "measure('" + m.name() + "') names no measure of this dataset (measures: " + String.join(", ", ds.measures().keySet()) + ")");
                }
            }
            case Node.UserRef u -> err(EXPRESSION, path, "user." + u.attribute() + " is for row rules; a measure does not depend on the reader");
            case Node.Call c when Node.ANALYTIC.contains(c.function()) && !c.args().isEmpty() -> {
                if (inAggregate) {
                    err(EXPRESSION, path, c.function() + "() cannot be inside an aggregate");
                }
                refs(path, c.args().get(0), false, current);               // the other arguments are columns and numbers, checked by analyticCall
            }
            case Node.Call c when (Node.TIME.contains(c.function()) || Node.WINDOW.contains(c.function())) && c.args().size() == 2 -> {
                if (inAggregate) {
                    err(EXPRESSION, path, c.function() + "() cannot be inside an aggregate");
                }
                refs(path, c.args().get(0), false, current);               // the date column (second argument) is checked by timeCall
            }
            case Node.Call c -> {
                boolean agg = Node.AGGREGATES.contains(c.function());
                if (agg && inAggregate && !"ratio".equals(c.function())) {
                    err(EXPRESSION, path, "aggregates do not nest: " + c.function() + "() is inside another aggregate");
                }
                boolean ratio = "ratio".equals(c.function());
                for (int i = 0; i < c.args().size(); i++) {
                    Node a = c.args().get(i);
                    if (ratio) {
                        if (!Node.hasAggregate(a) && !Node.anyMatch(a, x -> x instanceof Node.MeasureRef)) {
                            err(EXPRESSION, path, "ratio() takes two aggregates, for example ratio(sum(trades.mtm), sum(trades.notional))");
                        }
                        refs(path, a, false, current);
                    } else {
                        refs(path, a, inAggregate || agg, current);
                    }
                }
            }
            case Node.Bin b -> {
                refs(path, b.left(), inAggregate, current);
                refs(path, b.right(), inAggregate, current);
            }
            case Node.Un u -> refs(path, u.operand(), inAggregate, current);
            case Node.In i -> {
                refs(path, i.value(), inAggregate, current);
                i.items().forEach(x -> refs(path, x, inAggregate, current));
            }
            case Node.Cond c -> {
                refs(path, c.test(), inAggregate, current);
                refs(path, c.yes(), inAggregate, current);
                refs(path, c.no(), inAggregate, current);
            }
            default -> { }
        }
    }

    private void cycle(String name, Set<String> trail, String path) {
        if (!trail.add(name)) {
            err(EXPRESSION, path, "measures refer to each other in a circle: " + String.join(" -> ", trail) + " -> " + name);
            return;
        }
        Dataset.Measure m = ds.measures().get(name);
        if (m != null && m.expr() != null) {
            Node n = tryParse(m.expr());
            if (n != null) {
                Node.walk(n, x -> {
                    if (x instanceof Node.MeasureRef r && ds.measures().containsKey(r.name())) {
                        cycle(r.name(), new LinkedHashSet<>(trail), path);
                    }
                });
            }
        }
    }

    // ---- security, hierarchies, rollups ------------------------------------------------------------------------------

    private void security() {
        int i = 0;
        for (Dataset.RowRule r : ds.security().rows()) {
            String path = "security.rows[" + i++ + "]";
            Node n = parse(path, r.where());
            if (n == null) {
                continue;
            }
            calls(path, n);
            if (Node.hasAggregate(n) || Node.anyMatch(n, x -> x instanceof Node.MeasureRef)) {
                err(EXPRESSION, path, "a row rule is a condition on a row; it cannot aggregate or use measures");
            }
            Node.walk(n, x -> {
                if (x instanceof Node.Ref ref) {
                    try {
                        Resolver.ColRef c = resolver.resolve(ref.parts(), null);
                        if (c.column() == null) {
                            err(EXPRESSION, path, "'" + ref.text() + "' is a table, not a column");
                        }
                    } catch (IllegalArgumentException e) {
                        err(RELATION, path, e.getMessage());
                    }
                } else if (x instanceof Node.UserRef u && !UserAttributes.KNOWN.contains(u.attribute())) {
                    err(EXPRESSION, path, "user." + u.attribute() + " is not an attribute; use one of " + UserAttributes.KNOWN);
                }
            });
        }
        for (String m : ds.security().masked()) {
            if (!maskTargets(m)) {
                err(RELATION, "security.masked", "'" + m + "' is not a column of the dataset (write column or table.column)");
            }
        }
    }

    private boolean maskTargets(String m) {
        String[] p = m.split("\\.", -1);
        if (p.length == 2) {
            return resolver.column(p[0], p[1]) != null;
        }
        return p.length == 1 && ds.tables().keySet().stream().anyMatch(t -> resolver.column(t, m) != null);
    }

    private void hierarchies() {
        for (Map.Entry<String, List<String>> h : ds.hierarchies().entrySet()) {
            for (String level : h.getValue()) {
                try {
                    Resolver.ColRef c = resolver.resolve(List.of(level.split("\\.")), null);
                    if (c.column() == null) {
                        err(RELATION, "hierarchies." + h.getKey(), "level '" + level + "' is a table, not a column");
                    }
                } catch (IllegalArgumentException e) {
                    err(RELATION, "hierarchies." + h.getKey(), "level '" + level + "': " + e.getMessage());
                }
            }
        }
    }

    /** A test names a measure the dataset has (and can run), and the fields it groups, picks and filters by exist. */
    private void tests() {
        int i = 0;
        for (ExpectedValue t : ds.tests()) {
            String path = "tests[" + i++ + "]";
            Dataset.Measure m = t.measure() == null ? null : ds.measures().get(t.measure());
            if (m == null) {
                err(RELATION, path + ".measure", "the dataset has no measure '" + t.measure() + "' (measures: " + String.join(", ", ds.measures().keySet()) + ")");
            } else if (m.expr() == null) {
                err(RELATION, path + ".measure", "'" + t.measure() + "' is a Python measure: a query does not run it, so a test cannot check it");
            }
            java.util.List<String> fields = new java.util.ArrayList<>(t.by());
            fields.addAll(t.row().keySet());
            t.filters().forEach(f -> fields.add(f.field()));
            for (String f : fields) {
                if (ds.measures().containsKey(f)) {
                    continue;
                }
                try {
                    if (resolver.resolve(java.util.List.of(f.split("\\.", -1)), null).column() == null) {
                        err(RELATION, path, "'" + f + "' is a table; a test names table.column");
                    }
                } catch (IllegalArgumentException e) {
                    err(RELATION, path, e.getMessage());
                }
            }
            for (String r : t.row().keySet()) {
                if (!t.by().contains(r)) {
                    err(RELATION, path + ".row", "row picks by '" + r + "', which the test does not group by: add it to by");
                }
            }
        }
    }

    private void rollups() {
        int i = 0;
        for (Dataset.Rollup r : ds.rollups()) {
            String path = "rollups[" + i++ + "]";
            if (!ds.tables().containsKey(r.table())) {
                err(RELATION, path, "rollup '" + r.name() + "' names table '" + r.table() + "', which the dataset does not have");
                continue;
            }
            for (String b : r.by()) {
                Dataset.Column c = resolver.column(r.table(), b);
                if (c == null) {
                    err(RELATION, path, "rollup '" + r.name() + "' groups by '" + b + "', which " + r.table() + " does not have");
                } else if (c.expr() != null) {
                    err(RELATION, path, "rollup '" + r.name() + "' groups by calculated column '" + b + "'; use stored columns");
                }
            }
            Set<String> seen = new HashSet<>();
            for (String m : r.measures()) {
                Dataset.Measure meas = ds.measures().get(m);
                if (meas == null) {
                    err(RELATION, path, "rollup '" + r.name() + "' lists measure '" + m + "', which the dataset does not have");
                } else if (seen.add(m) && !Rollups.eligible(ds, meas, r.table())) {
                    err(RELATION, path, "rollup '" + r.name() + "' cannot hold measure '" + m + "': a rollup holds sums, counts, minimums, maximums and averages over stored columns of "
                            + r.table() + " (not distinct, percentile, a condition inside an aggregate, or another table)");
                }
            }
        }
    }

    // ---- parsing -----------------------------------------------------------------------------------------------------

    private Node parse(String path, String text) {
        try {
            Node n = com.ash.drishti.server.bi.expr.Expressions.parse(text);
            String note = com.ash.drishti.server.bi.expr.Expressions.oldSyntaxNote(text);
            if (note != null) {
                out.add(new Problem("warning", com.ash.drishti.server.bi.expr.Expressions.OLD_SYNTAX, path, note));
            }
            return n;
        } catch (ExprException e) {
            err(EXPRESSION, path, e.getMessage() + " (at position " + e.position() + " of: " + text + ")");
            return null;
        }
    }

    private static Node tryParse(String text) {
        try {
            return com.ash.drishti.server.bi.expr.Expressions.parse(text);
        } catch (ExprException e) {
            return null;
        }
    }
}
