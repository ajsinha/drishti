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
package com.ash.drishti.bicompute.lang;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.rachana.el.DatePartFunctions;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Functions;
import com.ash.drishti.rachana.el.check.Checker;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A row expression of Rachana in SQL (DuckDB's), as the one compiler writes it for a scan (HOW_IT_FITS.md §A28 2.6). Rachana's
 * evaluator is the definition: every operator and function here computes what Rachana computes, nulls included, so a live table's rows
 * (evaluated in Java) and a scan (in SQL) agree value for value; the differential test proves it for every function. The null rule
 * (Q1): {@code ==} and {@code !=} are {@code IS NOT DISTINCT FROM} and {@code IS DISTINCT FROM} ({@code null != 'X'} is true); an
 * ordering with null is false; a condition that is null is false; arithmetic and the text functions are null in, null out; a division
 * by zero is null. Literals are written as SQL literals (they come from a reviewed definition, never from a reader's input).
 *
 * <p>A function with no SQL form is refused ({@link IllegalArgumentException}): {@code shift_date} of a column by business days (the
 * calendar is Java's), {@code fx} (the FX source of Q15 comes with the table layer), anything outside checked mode.
 */
public final class SqlLowering {

    /** How names reach SQL. */
    public interface Columns {
        /** The SQL of a field ({@code [trades, MTM]}, {@code [$, NOTIONAL]}, {@code [DESK]}); an unknown one is an IllegalArgumentException. */
        String sql(List<String> parts);

        /** True when the field is masked for the reader (its SQL reads null, its Java value is masked). */
        default boolean masked(List<String> parts) {
            return false;
        }

        /** True when the field is a date or a timestamp. */
        default boolean date(List<String> parts) {
            return false;
        }

        /** True when the name is a table (an alias of a model: {@code count(trades)} counts its rows). */
        default boolean table(String name) {
            return false;
        }
    }

    /**
     * What a compilation is bound to.
     *
     * @param asOf the query's as-of date ({@code asof()}), or null when it has none
     * @param calendar the model's calendar ({@code USNY} by default)
     */
    public record Options(LocalDate asOf, BusinessCalendar calendar) {
        public Options {
            calendar = calendar == null ? BusinessCalendar.of("USNY") : calendar;
        }

        public static Options of(LocalDate asOf) {
            return new Options(asOf, null);
        }
    }

    /** How many years either side of the as-of date the holidays of {@code business_days_between} are taken from. */
    static final int HOLIDAY_YEARS = 10;

    private final Columns columns;
    private final Options options;
    private boolean masked;

    public SqlLowering(Columns columns, Options options) {
        this.columns = columns;
        this.options = options == null ? Options.of(null) : options;
    }

    /** The row expression in SQL, with whether it reads a masked column. */
    public Lowered.Row row(Expr e) {
        masked = false;
        String sql = sql(e);
        return new Lowered.Row(e, sql, masked);
    }

    /** The SQL of {@code e}. */
    public String sql(Expr e) {
        return switch (e) {
            case Expr.Lit l -> literal(l.value());
            case Expr.Absent a -> "NULL";
            case Expr.Field f -> field(f);
            case Expr.At a -> field(a);
            case Expr.Local l when l.name().startsWith("__h") -> Lowered.Shape.placeholder(Integer.parseInt(l.name().substring(3)));
            case Expr.Not n -> "(NOT " + cond(n.e()) + ")";
            case Expr.Neg n -> "(-" + sql(n.e()) + ")";
            case Expr.And a -> "(" + cond(a.l()) + " AND " + cond(a.r()) + ")";
            case Expr.Or o -> "(" + cond(o.l()) + " OR " + cond(o.r()) + ")";
            case Expr.Ternary t -> "(CASE WHEN " + cond(t.cond()) + " THEN " + sql(t.then()) + " ELSE " + sql(t.otherwise()) + " END)";
            case Expr.Binary b -> binary(b);
            case Expr.In i -> in(i);
            case Expr.Call c -> call(c.name(), c.args(), c);
            case Expr.Form f -> call(f.name(), f.args(), f);
            default -> throw new IllegalArgumentException("this has no SQL form in a table or a model: " + e.getClass().getSimpleName());
        };
    }

    /** A condition: null is false (the null rule). */
    String cond(Expr e) {
        return "coalesce(" + sql(e) + ", FALSE)";
    }

    static String literal(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (v instanceof Long || v instanceof Integer) {
            return "CAST(" + v + " AS BIGINT)";
        }
        if (v instanceof Number n) {
            return "CAST(" + new java.math.BigDecimal(n.toString()).toPlainString() + " AS DOUBLE)";
        }
        return "'" + v.toString().replace("'", "''") + "'";
    }

    private String field(Expr e) {
        List<String> parts = Checker.parts(e);
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("a list of the source has no SQL form in a row expression");
        }
        if (columns.masked(parts)) {
            masked = true;
            return "NULL";                                                     // masks are applied before: a masked column reads null
        }
        return columns.sql(parts);
    }

    private String binary(Expr.Binary b) {
        String l = sql(b.l());
        String r = sql(b.r());
        return switch (b.op()) {
            case "==" -> "(" + l + " IS NOT DISTINCT FROM " + r + ")";
            case "!=" -> "(" + l + " IS DISTINCT FROM " + r + ")";
            case "<", "<=", ">", ">=" -> "coalesce(" + l + " " + b.op() + " " + r + ", FALSE)";
            case "-" -> isDate(b.l()) && isDate(b.r()) ? "CAST(" + date(l) + " - " + date(r) + " AS BIGINT)"   // date - date: the days between
                    : "(CAST(" + l + " AS DOUBLE) - CAST(" + r + " AS DOUBLE))";
            case "+", "*" -> "(CAST(" + l + " AS DOUBLE) " + b.op() + " CAST(" + r + " AS DOUBLE))";
            case "/" -> "(CAST(" + l + " AS DOUBLE) / NULLIF(CAST(" + r + " AS DOUBLE), 0))";
            case "%" -> "(CAST(" + l + " AS DOUBLE) - CAST(" + r + " AS DOUBLE) * trunc(CAST(" + l + " AS DOUBLE) / NULLIF(CAST(" + r
                    + " AS DOUBLE), 0)))";                                     // the remainder with the dividend's sign, as Rachana's (Java's) %
            default -> throw new IllegalArgumentException("operator " + b.op() + " has no SQL form");
        };
    }

    /** True when {@code e} is a date (a date column, or a function that gives one): {@code date - date} is days. */
    private boolean isDate(Expr e) {
        return switch (e) {
            case Expr.Field f -> Checker.parts(f) != null && columns.date(Checker.parts(f));
            case Expr.At a -> Checker.parts(a) != null && columns.date(Checker.parts(a));
            case Expr.Call c -> java.util.Set.of("date", "timestamp", "asof", "shift_date").contains(Functions.oneName(c.name()));
            case Expr.Ternary t -> isDate(t.then()) || isDate(t.otherwise());
            default -> false;
        };
    }

    private String in(Expr.In i) {
        String v = sql(i.value());
        if (!(i.list() instanceof Expr.ListLit l)) {
            throw new IllegalArgumentException("'in' over " + (i.list() instanceof Expr.Field ? "an attribute" : "a value")
                    + " is bound when the query runs (user.x, param.x), not written here");
        }
        List<String> any = new ArrayList<>();
        for (Expr item : l.items()) {
            any.add(v + " IS NOT DISTINCT FROM " + sql(item));
        }
        String holds = any.isEmpty() ? "FALSE" : "(" + String.join(" OR ", any) + ")";
        return i.negated() ? "(NOT " + holds + ")" : holds;
    }

    /** A value as a date: a date, a timestamp's date, or ISO text's first ten characters. */
    private static String date(String sql) {
        return "TRY_CAST(substr(CAST(" + sql + " AS VARCHAR), 1, 10) AS DATE)";
    }

    private static String text(String sql) {
        return "CAST(" + sql + " AS VARCHAR)";
    }

    private String call(String name, List<Expr> args, Expr at) {
        String fn = Functions.oneName(name);
        List<String> a = new ArrayList<>();
        for (Expr x : args) {
            a.add(x instanceof Expr.Absent ? null : sql(x));
        }
        return switch (fn) {
            case "abs" -> "abs(" + a.get(0) + ")";
            case "round" -> "CAST(round(CAST(" + a.get(0) + " AS DECIMAL(38, 12)), " + whole(args, 1, 0) + ") AS DOUBLE)";
            case "coalesce" -> a.size() == 1 ? a.get(0) : "coalesce(" + String.join(", ", a) + ")";
            case "upper" -> "upper(" + text(a.get(0)) + ")";
            case "lower" -> "lower(" + text(a.get(0)) + ")";
            case "len" -> "CAST(length(" + text(a.get(0)) + ") AS BIGINT)";
            case "contains" -> "coalesce(contains(lower(" + text(a.get(0)) + "), lower(coalesce(" + text(a.get(1)) + ", ''))), FALSE)";
            case "starts_with" -> "starts_with(lower(coalesce(" + text(a.get(0)) + ", '')), lower(coalesce(" + text(a.get(1)) + ", '')))";
            case "ends_with" -> "suffix(lower(coalesce(" + text(a.get(0)) + ", '')), lower(coalesce(" + text(a.get(1)) + ", '')))";
            case "date" -> date(a.get(0));
            case "timestamp" -> "CAST(" + a.get(0) + " AS TIMESTAMP)";
            case "year", "quarter", "month", "day" -> "CAST(" + fn + "(" + date(a.get(0)) + ") AS BIGINT)";
            case "week" -> "CAST(weekofyear(" + date(a.get(0)) + ") AS BIGINT)";
            case "days_between" -> "CAST(" + date(a.get(1)) + " - " + date(a.get(0)) + " AS BIGINT)";
            case "business_days_between" -> businessDays(a.get(0), a.get(1), calendar(args, 2));
            case "shift_date" -> shift(args, a);
            case "asof" -> {
                if (options.asOf() == null) {
                    throw new IllegalArgumentException("asof() needs the query's as-of date, which this compilation was not given");
                }
                yield "DATE '" + options.asOf() + "'";
            }
            case "power" -> "(CASE WHEN CAST(" + a.get(0) + " AS DOUBLE) = 0 AND CAST(" + a.get(1) + " AS DOUBLE) < 0 THEN NULL ELSE power(CAST("
                    + a.get(0) + " AS DOUBLE), CAST(" + a.get(1) + " AS DOUBLE)) END)";
            case "floor" -> a.size() < 2 || a.get(1) == null ? "floor(CAST(" + a.get(0) + " AS DOUBLE))"
                    : "(CASE WHEN " + a.get(0) + " IS NULL THEN NULL WHEN CAST(" + a.get(1) + " AS DOUBLE) = 0 THEN CAST(0 AS DOUBLE) ELSE floor(CAST("
                            + a.get(0) + " AS DOUBLE) / abs(CAST("
                            + a.get(1) + " AS DOUBLE))) * abs(CAST(" + a.get(1) + " AS DOUBLE)) END)";
            case "least", "greatest" -> fn + "(" + String.join(", ", a.stream().map(x -> "CAST(" + x + " AS DOUBLE)").toList()) + ")";
            case "switch" -> switchOf(a);
            case "fx" -> throw new IllegalArgumentException("fx() reads the installation's FX source, which comes with the table layer (Q15): "
                    + "it has no SQL form yet");
            default -> throw new IllegalArgumentException(fn + "() has no SQL form in a row expression"
                    + (Functions.arity().containsKey(fn) ? " (an aggregate or a function of a measure belongs in a measure)" : ""));
        };
    }

    private static String switchOf(List<String> a) {
        StringBuilder b = new StringBuilder("(CASE");
        int pairs = (a.size() - 1) / 2;
        for (int i = 0; i < pairs; i++) {
            b.append(" WHEN ").append(a.get(0)).append(" IS NOT DISTINCT FROM ").append(a.get(1 + 2 * i)).append(" THEN ").append(a.get(2 + 2 * i));
        }
        b.append(" ELSE ").append((a.size() - 1) % 2 == 1 ? a.get(a.size() - 1) : "NULL").append(" END)");
        return b.toString();
    }

    /** A whole number written in the expression (a place count), or the default when it is left out. */
    private static int whole(List<Expr> args, int i, int dflt) {
        if (args.size() <= i || args.get(i) instanceof Expr.Absent) {
            return dflt;
        }
        Expr x = args.get(i);
        boolean neg = x instanceof Expr.Neg;
        Expr v = neg ? ((Expr.Neg) x).e() : x;
        if (v instanceof Expr.Lit l && l.value() instanceof Number n) {
            return neg ? -n.intValue() : n.intValue();
        }
        throw new IllegalArgumentException("a whole number written in the expression is needed here");
    }

    private BusinessCalendar calendar(List<Expr> args, int i) {
        if (args.size() > i && args.get(i) instanceof Expr.Lit l && l.value() instanceof String spec) {
            return BusinessCalendar.of(spec.trim().toUpperCase(Locale.ROOT));
        }
        return options.calendar();
    }

    /**
     * Weekdays after {@code a} up to {@code b} less the calendar's weekday holidays in between (DEL's form): the weekdays from Monday
     * 1970-01-05, {@code 5 * weeks + min(day + 1, 5)}, differenced; a holiday {@code h} with {@code a < h <= b} counts one less (one more
     * when {@code b} is before {@code a}).
     */
    private String businessDays(String from, String to, BusinessCalendar cal) {
        String days = "(" + weekdays(to) + " - " + weekdays(from) + ")";
        List<String> hs = new ArrayList<>();
        int year = (options.asOf() == null ? LocalDate.now(java.time.ZoneOffset.UTC) : options.asOf()).getYear();
        // the weekdays the calendar closes (observed holidays included), exactly the days Rachana's isBusinessDay refuses
        LocalDate end = LocalDate.of(year + HOLIDAY_YEARS, 12, 31);
        for (LocalDate h = LocalDate.of(year - HOLIDAY_YEARS, 1, 1); !h.isAfter(end); h = h.plusDays(1)) {
            if (h.getDayOfWeek() != DayOfWeek.SATURDAY && h.getDayOfWeek() != DayOfWeek.SUNDAY && !cal.isBusinessDay(h)) {
                hs.add("DATE '" + h + "'");
            }
        }
        if (hs.isEmpty()) {
            return "CAST(" + days + " AS BIGINT)";
        }
        String list = "[" + String.join(", ", hs) + "]";
        String a = date(from);
        String b = date(to);
        return "CAST(" + days + " - len(list_filter(" + list + ", h -> h > " + a + " AND h <= " + b + ")) + len(list_filter(" + list + ", h -> h > "
                + b + " AND h <= " + a + ")) AS BIGINT)";
    }

    private static String weekdays(String d) {
        String n = "CAST(" + date(d) + " - DATE '1970-01-05' AS DOUBLE)";
        return "(5 * floor(" + n + " / 7) + least(" + n + " - 7 * floor(" + n + " / 7) + 1, 5))";
    }

    /** {@code shift_date}: a fixed date worked out now (business days on the calendar); a column moved by a period in SQL. */
    private String shift(List<Expr> args, List<String> a) {
        int n = whole(args, 1, 0);
        String unit = args.size() > 2 && args.get(2) instanceof Expr.Lit l && l.value() instanceof String u ? DatePartFunctions.unit(u) : "business-day";
        LocalDate fixed = constant(args.get(0));
        if (fixed != null) {
            return "DATE '" + DatePartFunctions.shift(fixed, n, unit, calendar(args, 3)) + "'";
        }
        if ("business-day".equals(unit)) {
            throw new IllegalArgumentException("shift_date() of a column moves it by day, week, month, quarter or year; business days need a fixed "
                    + "date (asof() or date('...')) because they follow the calendar");
        }
        String interval = switch (unit) {
            case "day" -> "INTERVAL (" + n + ") DAY";
            case "week" -> "INTERVAL (" + (7 * n) + ") DAY";
            case "month" -> "INTERVAL (" + n + ") MONTH";
            case "quarter" -> "INTERVAL (" + (3 * n) + ") MONTH";
            default -> "INTERVAL (" + n + ") YEAR";
        };
        return "CAST(" + date(a.get(0)) + " + " + interval + " AS DATE)";
    }

    /** A date worked out before the query runs: {@code date('...')}, {@code '2026-10-16'}, {@code asof()}, {@code shift_date} of one. */
    LocalDate constant(Expr e) {
        if (e instanceof Expr.Lit l && l.value() instanceof String s && Checker.isoDate(s)) {
            return LocalDate.parse(s.substring(0, 10));
        }
        if (e instanceof Expr.Call c) {
            String fn = Functions.oneName(c.name());
            if ("date".equals(fn) && c.args().size() == 1) {
                return constant(c.args().get(0));
            }
            if ("asof".equals(fn)) {
                return options.asOf();
            }
            if ("shift_date".equals(fn)) {
                LocalDate base = constant(c.args().get(0));
                if (base == null) {
                    return null;
                }
                String unit = c.args().size() > 2 && c.args().get(2) instanceof Expr.Lit u && u.value() instanceof String s ? DatePartFunctions.unit(s) : "business-day";
                return DatePartFunctions.shift(base, whole(c.args(), 1, 0), unit, calendar(c.args(), 3));
            }
        }
        return null;
    }
}
