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
package com.ash.drishti.bicompute.formula;

import com.ash.drishti.bicompute.spi.Batch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A row expression of a formula (the argument or the {@code where=} of an aggregate), two ways from one tree: evaluated over a columnar
 * batch in Java ({@link #eval}: the harness, the playground, the providers that feed batches) and written as SQL over the plan's input
 * columns ({@link #sql}: the tabular provider's {@code GROUPING SETS} statement). The two agree: a missing value, a text that is not a number,
 * a division by zero and a comparison with a missing value are all absent (a bad value is null).
 *
 * <p>Literals are written into the SQL as SQL literals (numbers as Java writes a double; texts quoted, quotes doubled): they come from a
 * function's reviewed definition, never from a reader's input; the reader's values (filters, coordinates) stay bound parameters.
 */
final class RowExpr {

    private final FormulaAst node;
    private final List<String> fields;

    /** {@code fields}: the formula's input fields, in the plan's input order (a field's index is its batch column and SQL input). */
    RowExpr(FormulaAst node, List<String> fields) {
        this.node = node;
        this.fields = fields;
    }

    // ---- Java ------------------------------------------------------------------------------------------------------------------------

    /** The expression's value per row: {@code Double}, {@code String}, {@code Boolean} or null (absent). */
    Object[] eval(Batch b) {
        Object[] out = new Object[b.rows()];
        Object[][] cols = new Object[fields.size()][];
        for (int r = 0; r < b.rows(); r++) {
            out[r] = value(node, b, r, cols);
        }
        return out;
    }

    /** The numbers of the expression per row ({@code NaN}: absent), {@code mask} applied (a row whose condition is not true is absent). */
    double[] numbers(Batch b, boolean[] mask) {
        Object[] v = eval(b);
        double[] out = new double[b.rows()];
        for (int r = 0; r < out.length; r++) {
            out[r] = mask != null && !mask[r] ? Double.NaN : toNum(v[r]);
        }
        return out;
    }

    /** The texts of the expression per row (null: absent), {@code mask} applied. */
    String[] texts(Batch b, boolean[] mask) {
        Object[] v = eval(b);
        String[] out = new String[b.rows()];
        for (int r = 0; r < out.length; r++) {
            out[r] = mask != null && !mask[r] || v[r] == null ? null : text(v[r]);
        }
        return out;
    }

    /** True per row where the condition is true (absent is not true). */
    boolean[] mask(Batch b) {
        Object[] v = eval(b);
        boolean[] out = new boolean[b.rows()];
        for (int r = 0; r < out.length; r++) {
            out[r] = Boolean.TRUE.equals(truth(v[r]));
        }
        return out;
    }

    private Object column(Batch b, int r, Object[][] cols, int i) {
        Object c = b.columns()[i];
        if (c instanceof double[] d) {
            return Double.isNaN(d[r]) ? null : d[r];
        }
        String s = ((String[]) c)[r];
        return s;
    }

    private Object value(FormulaAst n, Batch b, int r, Object[][] cols) {
        return switch (n) {
            case FormulaAst.Num x -> x.value();
            case FormulaAst.Str x -> x.value();
            case FormulaAst.Bool x -> x.value();
            case FormulaAst.NoneLit x -> null;
            case FormulaAst.Field f -> column(b, r, cols, fields.indexOf(f.name()));
            case FormulaAst.ListLit l -> {
                List<Object> items = new ArrayList<>();
                l.items().forEach(i -> items.add(value(i, b, r, cols)));
                yield items;
            }
            case FormulaAst.Unary u -> {
                Object v = value(u.operand(), b, r, cols);
                yield switch (u.op()) {
                    case "not" -> {
                        Boolean t = truth(v);
                        yield t == null ? null : !t;
                    }
                    case "-" -> num(-toNum(v));
                    default -> num(toNum(v));
                };
            }
            case FormulaAst.Binary x -> num(arith(x.op(), toNum(value(x.left(), b, r, cols)), toNum(value(x.right(), b, r, cols))));
            case FormulaAst.BoolOp x -> {
                boolean and = x.op().equals("and");
                boolean unknown = false;
                Boolean decided = null;
                for (FormulaAst v : x.values()) {
                    Boolean t = truth(value(v, b, r, cols));
                    if (t == null) {
                        unknown = true;
                    } else if (t != and) {
                        decided = t;            // and: a false decides; or: a true decides
                        break;
                    }
                }
                yield decided != null ? decided : unknown ? null : (Boolean) and;
            }
            case FormulaAst.Compare c -> {
                Object left = value(c.first(), b, r, cols);
                Boolean all = true;
                for (int i = 0; i < c.ops().size(); i++) {
                    Object right = value(c.rest().get(i), b, r, cols);
                    String op = c.ops().get(i);
                    boolean nullTest = (op.equals("==") || op.equals("!=")) && (c.rest().get(i) instanceof FormulaAst.NoneLit
                            || (i == 0 ? c.first() : c.rest().get(i - 1)) instanceof FormulaAst.NoneLit);
                    // x == null tests for nothing (Rachana's one null rule, docs/architecture/ARCHITECTURE.md §28 Q1); other comparisons with nothing are unknown
                    Boolean t = nullTest ? Boolean.valueOf((left == null && right == null) == op.equals("==")) : compare(op, left, right);
                    if (t == null) {
                        all = null;
                        break;
                    }
                    if (!t) {
                        all = false;
                        break;
                    }
                    left = right;
                }
                yield all;
            }
            case FormulaAst.IfExp i -> Boolean.TRUE.equals(truth(value(i.test(), b, r, cols))) ? value(i.body(), b, r, cols)
                    : value(i.orElse(), b, r, cols);
            case FormulaAst.Scalar s -> scalar(s, b, r, cols);
            case FormulaAst.Agg a -> throw new IllegalStateException("an aggregate is not a row value");
        };
    }

    private Object scalar(FormulaAst.Scalar s, Batch b, int r, Object[][] cols) {
        List<Object> a = new ArrayList<>();
        s.args().forEach(x -> a.add(value(x, b, r, cols)));
        switch (s.name()) {
            case "coalesce" -> {
                return a.stream().filter(Objects::nonNull).findFirst().orElse(null);
            }
            case "greatest", "least" -> {
                Double best = null;
                for (Object o : a) {
                    double d = toNum(o);
                    if (!Double.isNaN(d) && (best == null || (s.name().equals("greatest") ? d > best : d < best))) {
                        best = d;
                    }
                }
                return best;
            }
            case "round" -> {
                double x = toNum(a.get(0));
                int digits = a.size() > 1 ? (int) toNum(a.get(1)) : 0;
                if (Double.isNaN(x)) {
                    return null;
                }
                return num(new java.math.BigDecimal(Double.toString(x)).setScale(digits, java.math.RoundingMode.HALF_UP).doubleValue());
            }
            case "ratio" -> {
                return num(arith("/", toNum(a.get(0)), toNum(a.get(1))));
            }
            case "power" -> {
                return num(arith("**", toNum(a.get(0)), toNum(a.get(1))));
            }
            default -> { }
        }
        double x = toNum(a.get(0));
        double v = switch (s.name()) {
            case "abs" -> Math.abs(x);
            case "sqrt" -> x < 0 ? Double.NaN : Math.sqrt(x);
            case "ln" -> x <= 0 ? Double.NaN : Math.log(x);
            case "log10" -> x <= 0 ? Double.NaN : Math.log10(x);
            case "exp" -> Math.exp(x);
            case "floor" -> Math.floor(x);
            case "ceiling" -> Math.ceil(x);
            case "sign" -> Math.signum(x);
            default -> Double.NaN;
        };
        return num(v);
    }

    static double arith(String op, double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return Double.NaN;
        }
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> b == 0 ? Double.NaN : a / b;
            case "//" -> b == 0 ? Double.NaN : Math.floor(a / b);
            case "%" -> b == 0 ? Double.NaN : a - b * Math.floor(a / b);
            case "**" -> Math.pow(a, b);
            default -> Double.NaN;
        };
    }

    private static Boolean compare(String op, Object l, Object r) {
        if (op.equals("in") || op.equals("not in")) {
            if (l == null || !(r instanceof List<?> items)) {
                return null;
            }
            boolean found = false;
            for (Object i : items) {
                Boolean eq = compare("==", l, i);
                if (Boolean.TRUE.equals(eq)) {
                    found = true;
                    break;
                }
            }
            return op.equals("in") == found;
        }
        if (l == null || r == null) {
            return null;
        }
        int c;
        if (l instanceof String && r instanceof String) {
            c = text(l).compareTo(text(r));
        } else {
            // a number beside a number or a text value compares as numbers (as the SQL path's cast does); text that is no number: nothing
            double a = toNum(l);
            double b = toNum(r);
            if (Double.isNaN(a) || Double.isNaN(b)) {
                return null;
            }
            c = Double.compare(a, b);
        }
        return switch (op) {
            case "==" -> c == 0;
            case "!=" -> c != 0;
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            default -> c >= 0;
        };
    }

    private static Double num(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    static double toNum(Object o) {
        if (o instanceof Double d) {
            return d;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static Boolean truth(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s) {
            return !s.isEmpty();
        }
        double d = toNum(o);
        return Double.isNaN(d) ? null : d != 0;
    }

    static String text(Object o) {
        if (o instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString(d.longValue());
        }
        return String.valueOf(o);
    }

    // ---- SQL -------------------------------------------------------------------------------------------------------------------------

    /** The expression as SQL over the input columns {@code inputs} (in {@link #fields}' order), as a number. */
    String sqlNumber(List<String> inputs) {
        return sql(node, inputs, "num");
    }

    /** The expression as SQL text. */
    String sqlText(List<String> inputs) {
        return sql(node, inputs, "text");
    }

    /** The expression as an SQL condition. */
    String sqlCondition(List<String> inputs) {
        return sql(node, inputs, "bool");
    }

    private String sql(FormulaAst n, List<String> in, String want) {
        return switch (n) {
            case FormulaAst.Num x -> want.equals("text") ? literal(text(x.value())) : number(x.value());
            case FormulaAst.Str x -> want.equals("num") ? "TRY_CAST(" + literal(x.value()) + " AS DOUBLE)" : literal(x.value());
            case FormulaAst.Bool x -> want.equals("bool") ? (x.value() ? "TRUE" : "FALSE") : x.value() ? "1" : "0";
            case FormulaAst.NoneLit x -> "NULL";
            case FormulaAst.Field f -> {
                String col = in.get(fields.indexOf(f.name()));
                yield switch (want) {
                    case "num" -> "TRY_CAST(" + col + " AS DOUBLE)";
                    case "text" -> "CAST(" + col + " AS VARCHAR)";
                    default -> "(TRY_CAST(" + col + " AS DOUBLE) <> 0)";
                };
            }
            case FormulaAst.ListLit l -> throw new FormulaLowering.FormulaException("a list is only the right side of in", -1);
            case FormulaAst.Unary u -> u.op().equals("not") ? "(NOT " + sql(u.operand(), in, "bool") + ")"
                    : "(" + u.op() + sql(u.operand(), in, "num") + ")";
            case FormulaAst.Binary x -> {
                String a = sql(x.left(), in, "num");
                String b = sql(x.right(), in, "num");
                yield switch (x.op()) {
                    case "/" -> "(" + a + " / NULLIF(" + b + ", 0))";
                    case "//" -> "floor(" + a + " / NULLIF(" + b + ", 0))";
                    case "%" -> "(" + a + " - NULLIF(" + b + ", 0) * floor(" + a + " / NULLIF(" + b + ", 0)))";
                    case "**" -> "power(" + a + ", " + b + ")";
                    default -> "(" + a + " " + x.op() + " " + b + ")";
                };
            }
            case FormulaAst.BoolOp x -> "(" + String.join(" " + x.op().toUpperCase(Locale.ROOT) + " ",
                    x.values().stream().map(v -> sql(v, in, "bool")).toList()) + ")";
            case FormulaAst.Compare c -> compareSql(c, in);
            case FormulaAst.IfExp i -> "(CASE WHEN " + sql(i.test(), in, "bool") + " THEN " + sql(i.body(), in, want) + " ELSE "
                    + sql(i.orElse(), in, want) + " END)";
            case FormulaAst.Scalar s -> scalarSql(s, in);
            case FormulaAst.Agg a -> throw new IllegalStateException("an aggregate is not a row value");
        };
    }

    private String compareSql(FormulaAst.Compare c, List<String> in) {
        List<String> parts = new ArrayList<>();
        FormulaAst left = c.first();
        for (int i = 0; i < c.ops().size(); i++) {
            String op = c.ops().get(i);
            FormulaAst right = c.rest().get(i);
            if (op.endsWith("in")) {
                if (!(right instanceof FormulaAst.ListLit l)) {
                    throw new FormulaLowering.FormulaException("in takes a list: x in ['A', 'B']", -1);
                }
                boolean text = l.items().stream().anyMatch(x -> x instanceof FormulaAst.Str);
                String kind = text ? "text" : "num";
                if (l.items().isEmpty()) {                                   // an empty list matches nothing, as the Java path answers (SQL has no IN ())
                    parts.add("(CASE WHEN " + sql(left, in, kind) + " IS NULL THEN NULL ELSE " + (op.equals("in") ? "FALSE" : "TRUE") + " END)");
                } else {
                    String items = String.join(", ", l.items().stream().map(x -> sql(x, in, kind)).toList());
                    parts.add("(" + sql(left, in, kind) + (op.equals("in") ? " IN (" : " NOT IN (") + items + "))");
                }
            } else if ((op.equals("==") || op.equals("!=")) && (left instanceof FormulaAst.NoneLit || right instanceof FormulaAst.NoneLit)) {
                FormulaAst other = right instanceof FormulaAst.NoneLit ? left : right;
                parts.add("(" + sql(other, in, "text") + (op.equals("==") ? " IS NULL)" : " IS NOT NULL)"));
            } else {
                boolean text = left instanceof FormulaAst.Str || right instanceof FormulaAst.Str;
                String kind = text ? "text" : "num";
                parts.add("(" + sql(left, in, kind) + " " + (op.equals("==") ? "=" : op.equals("!=") ? "<>" : op) + " " + sql(right, in, kind) + ")");
            }
            left = right;
        }
        return parts.size() == 1 ? parts.get(0) : "(" + String.join(" AND ", parts) + ")";
    }

    private String scalarSql(FormulaAst.Scalar s, List<String> in) {
        List<String> a = s.args().stream().map(x -> sql(x, in, "num")).toList();
        return switch (s.name()) {
            case "coalesce" -> "coalesce(" + String.join(", ", a) + ")";
            case "greatest", "least" -> s.name() + "(" + String.join(", ", a) + ")";
            case "round" -> "round(" + a.get(0) + ", " + (a.size() > 1 ? "CAST(" + a.get(1) + " AS INTEGER)" : "0") + ")";
            case "ratio" -> "(" + a.get(0) + " / NULLIF(" + a.get(1) + ", 0))";
            case "power" -> "power(" + a.get(0) + ", " + a.get(1) + ")";
            case "ceiling" -> "ceil(" + a.get(0) + ")";
            case "sqrt" -> "(CASE WHEN " + a.get(0) + " >= 0 THEN sqrt(" + a.get(0) + ") END)";
            case "ln" -> "(CASE WHEN " + a.get(0) + " > 0 THEN ln(" + a.get(0) + ") END)";
            case "log10" -> "(CASE WHEN " + a.get(0) + " > 0 THEN log10(" + a.get(0) + ") END)";
            case "sign" -> "CAST(sign(" + a.get(0) + ") AS DOUBLE)";
            default -> s.name() + "(" + a.get(0) + ")";
        };
    }

    static String number(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d) + ".0";
        }
        return "CAST('" + Double.toString(d) + "' AS DOUBLE)";
    }

    static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
