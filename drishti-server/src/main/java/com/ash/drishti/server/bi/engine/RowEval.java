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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.server.bi.dataset.Node;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A row expression worked out in Java over one changed row of a live table (wave B, docs/architecture/BI_DESIGN.md section 4.6: "Δ filtered by the reader's
 * row rules, rules re-evaluated per changed row"), with the semantics of the SQL the compiler writes: {@code None} propagates through arithmetic, a comparison
 * with {@code None} is false, {@code /} by zero is {@code None}, {@code in user.desks} reads the reader's attribute. What it cannot work out (a function it
 * does not know, a column of another table) throws {@link Unsupported}, and the caller recomputes from the engine instead. Pure.
 */
public final class RowEval {

    /** The expression is outside what this evaluator does: recompute through the engine. */
    static final class Unsupported extends RuntimeException {
        Unsupported(String what) {
            super(what, null, false, false);
        }
    }

    private final String table;
    private final Map<String, Integer> index;
    private final Map<String, List<Object>> user;

    /**
     * @param table the alias the row belongs to (a column of another alias is unsupported)
     * @param columns the row's columns by position ({@code businessDate} first)
     */
    RowEval(String table, List<String> columns, Map<String, List<Object>> user) {
        this.table = table;
        this.index = new java.util.HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            index.put(columns.get(i), i);
        }
        this.user = user == null ? Map.of() : user;
    }

    /** True when the condition holds for the row (a None condition does not). */
    boolean test(Node n, Object[] row) {
        return Boolean.TRUE.equals(eval(n, row));
    }

    Object eval(Node n, Object[] row) {
        return switch (n) {
            case Node.Num x -> x.integral() && Math.abs(x.value()) < 9e15 ? (Object) (long) x.value() : (Object) x.value();
            case Node.Str s -> s.value();
            case Node.Bool b -> b.value();
            case Node.Null x -> null;
            case Node.Ref r -> ref(r, row);
            case Node.UserRef u -> {
                List<Object> v = user.getOrDefault(u.attribute(), List.of());
                yield v.isEmpty() ? "" : v.get(0);
            }
            case Node.Un u -> {
                Object v = eval(u.operand(), row);
                if ("!".equals(u.op())) {
                    yield v == null ? null : !(Boolean) v;
                }
                yield v == null ? null : v instanceof Long l ? (Object) (-l) : (Object) (-((Number) v).doubleValue());
            }
            case Node.Bin b -> bin(b, row);
            case Node.In i -> in(i, row);
            case Node.Cond c -> Boolean.TRUE.equals(eval(c.test(), row)) ? eval(c.yes(), row) : eval(c.no(), row);
            case Node.Call c -> call(c, row);
            case Node.MeasureRef m -> throw new Unsupported("a measure in a row expression");
        };
    }

    private Object ref(Node.Ref r, Object[] row) {
        List<String> p = r.parts();
        String col;
        String part = null;
        if (p.size() == 1) {
            col = p.get(0);
        } else if (p.get(0).equals(table)) {
            col = p.get(1);
            part = p.size() > 2 ? p.get(2) : null;
        } else {
            throw new Unsupported("a column of another table: " + r.text());
        }
        Integer i = index.get(col);
        if (i == null) {
            throw new Unsupported("not a column of the live table: " + r.text());
        }
        Object v = row[i];
        return part == null ? v : part(part, v);
    }

    /** A date part as the engine gives it (an integer). */
    /** The first day of a date's period ({@code week_start}: its Monday, {@code month_start}, {@code quarter_start}, {@code year_start}). */
    public static LocalDate bucket(String part, LocalDate d) {
        return switch (part) {
            case "week_start" -> d.with(java.time.DayOfWeek.MONDAY);
            case "month_start" -> d.withDayOfMonth(1);
            case "quarter_start" -> LocalDate.of(d.getYear(), (d.getMonthValue() - 1) / 3 * 3 + 1, 1);
            case "year_start" -> d.withDayOfYear(1);
            default -> throw new IllegalArgumentException("not a period: " + part);
        };
    }

    static Object part(String part, Object v) {
        if (v == null) {
            return null;
        }
        LocalDate d = v instanceof LocalDate x ? x : LocalDate.parse(String.valueOf(v).substring(0, 10));
        return switch (part) {
            case "year" -> (long) d.getYear();
            case "quarter" -> (long) ((d.getMonthValue() - 1) / 3 + 1);
            case "month" -> (long) d.getMonthValue();
            case "day" -> (long) d.getDayOfMonth();
            case "week" -> (long) d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "week_start", "month_start", "quarter_start", "year_start" -> bucket(part, d);
            default -> throw new Unsupported("date part " + part);
        };
    }

    private Object bin(Node.Bin b, Object[] row) {
        switch (b.op()) {
            case "&&" -> {
                Object l = eval(b.left(), row);
                if (Boolean.FALSE.equals(l)) {
                    return false;
                }
                Object r = eval(b.right(), row);
                return Boolean.FALSE.equals(r) ? Boolean.FALSE : l == null || r == null ? null : Boolean.TRUE;
            }
            case "||" -> {
                Object l = eval(b.left(), row);
                if (Boolean.TRUE.equals(l)) {
                    return true;
                }
                Object r = eval(b.right(), row);
                return Boolean.TRUE.equals(r) ? Boolean.TRUE : l == null || r == null ? null : Boolean.FALSE;
            }
            default -> { }
        }
        if (("==".equals(b.op()) || "!=".equals(b.op())) && (b.left() instanceof Node.Null || b.right() instanceof Node.Null)) {
            Object other = eval(b.left() instanceof Node.Null ? b.right() : b.left(), row);
            return "==".equals(b.op()) ? other == null : other != null;
        }
        Object l = eval(b.left(), row);
        Object r = eval(b.right(), row);
        if (l == null || r == null) {
            return null;
        }
        return switch (b.op()) {
            case "==" -> compare(l, r) == 0;
            case "!=" -> compare(l, r) != 0;
            case "<" -> compare(l, r) < 0;
            case "<=" -> compare(l, r) <= 0;
            case ">" -> compare(l, r) > 0;
            case ">=" -> compare(l, r) >= 0;
            case "+", "-", "*" -> arithmetic(b.op(), l, r);
            case "/" -> num(r) == 0 ? null : num(l) / num(r);
            case "//" -> num(r) == 0 ? null : Math.floor(num(l) / num(r));
            case "%" -> num(r) == 0 ? null : l instanceof Long a && r instanceof Long c ? (Object) (a % c) : (Object) (num(l) % num(r));
            default -> throw new Unsupported("operator " + b.op());
        };
    }

    private static Object arithmetic(String op, Object l, Object r) {
        if (l instanceof Long a && r instanceof Long c) {
            return switch (op) {
                case "+" -> a + c;
                case "-" -> a - c;
                default -> a * c;
            };
        }
        double a = num(l);
        double c = num(r);
        return switch (op) {
            case "+" -> a + c;
            case "-" -> a - c;
            default -> a * c;
        };
    }

    static double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new Unsupported("a number was expected, not " + v);
    }

    /** Compares two values as SQL does: numbers by value, dates with their ISO text, texts by code point. */
    static int compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return Boolean.compare(x, y);
        }
        return text(a).compareTo(text(b));
    }

    private static String text(Object v) {
        return v instanceof LocalDate d ? d.toString() : String.valueOf(v);
    }

    private Object in(Node.In i, Object[] row) {
        Object v = eval(i.value(), row);
        if (v == null) {
            return null;
        }
        List<Object> items = new ArrayList<>();
        for (Node item : i.items()) {
            if (item instanceof Node.UserRef u) {
                items.addAll(user.getOrDefault(u.attribute(), List.of()));
            } else {
                items.add(eval(item, row));
            }
        }
        boolean found = items.stream().anyMatch(x -> x != null && compare(v, x) == 0);
        return i.negated() != found;
    }

    private Object call(Node.Call c, Object[] row) {
        List<Node> a = c.args();
        return switch (c.function()) {
            case "upper" -> mapText(eval(a.get(0), row), s -> s.toUpperCase(Locale.ROOT));
            case "lower" -> mapText(eval(a.get(0), row), s -> s.toLowerCase(Locale.ROOT));
            case "length" -> {
                Object v = eval(a.get(0), row);
                yield v == null ? null : (long) String.valueOf(v).length();
            }
            case "abs" -> {
                Object v = eval(a.get(0), row);
                yield v == null ? null : v instanceof Long l ? (Object) Math.abs(l) : (Object) Math.abs(num(v));
            }
            case "coalesce" -> {
                for (Node x : a) {
                    Object v = eval(x, row);
                    if (v != null) {
                        yield v;
                    }
                }
                yield null;
            }
            case "contains" -> {
                Object s = eval(a.get(0), row);
                Object part = eval(a.get(1), row);
                yield s == null || part == null ? null : String.valueOf(s).toLowerCase(Locale.ROOT).contains(String.valueOf(part).toLowerCase(Locale.ROOT));
            }
            case "startsWith" -> {
                Object s = eval(a.get(0), row);
                Object p = eval(a.get(1), row);
                yield s == null || p == null ? null : String.valueOf(s).toLowerCase(Locale.ROOT).startsWith(String.valueOf(p).toLowerCase(Locale.ROOT));
            }
            case "endswith" -> {
                Object s = eval(a.get(0), row);
                Object p = eval(a.get(1), row);
                yield s == null || p == null ? null : String.valueOf(s).toLowerCase(Locale.ROOT).endsWith(String.valueOf(p).toLowerCase(Locale.ROOT));
            }
            case "year", "quarter", "month", "day", "week" -> part(c.function(), eval(a.get(0), row));
            case "date" -> {
                Object v = eval(a.get(0), row);
                yield v == null ? null : LocalDate.parse(String.valueOf(v).substring(0, 10));
            }
            default -> throw new Unsupported("function " + c.function());
        };
    }

    private static Object mapText(Object v, java.util.function.UnaryOperator<String> f) {
        return v == null ? null : f.apply(String.valueOf(v));
    }

    /** A value as the engine's answer holds it: integers as longs, decimals as doubles, dates and other values as text. */
    static Object answerValue(Object v) {
        return switch (v) {
            case null -> null;
            case Long l -> l;
            case Integer i -> (long) i;
            case Double d -> d;
            case Float f -> (double) f;
            case LocalDate d -> d.toString();
            default -> String.valueOf(v);
        };
    }

    static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0 || Math.abs(x.doubleValue() - y.doubleValue()) <= 1e-9 * Math.max(1, Math.abs(x.doubleValue()));
        }
        return Objects.equals(a, b);
    }
}
