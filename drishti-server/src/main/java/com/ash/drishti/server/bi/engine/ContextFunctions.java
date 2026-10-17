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

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.dataset.Resolver;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The SQL of the wave B functions (docs/architecture/BI_DESIGN.md sections 3.3.4 and 3.3.5), written by the {@link SqlEmitter} that meets them:
 * <ul>
 * <li>{@code calc(agg, where=)} restricts the aggregates inside to the condition ({@code FILTER (WHERE ...)}); with {@code ignore=}, {@code keep=} or
 * {@code via=} it is a context sub-query ({@link Contexts});</li>
 * <li>{@code share_of(agg, over=)} is {@code agg / calc(agg, ignore=over)}; {@code parent_of(agg, hierarchy=)} ignores the deepest level of the hierarchy
 * the answer groups by;</li>
 * <li>{@code avg_by(agg, by=)} and {@code avg_over(agg, date)} average the aggregate per value of more fields;</li>
 * <li>{@code as_of(agg, d)} reads the aggregate at another business date (each scan bound to that date);</li>
 * <li>{@code last}, {@code first} restrict to the latest (earliest) date of each group; {@code closing} to the last business day of each period (on the
 * model's calendar, up to the as-of date); {@code opening} is the closing of the period before (along the answer's date field, else at the business day
 * before the as-of period starts);</li>
 * <li>{@code asof()} and {@code shift_date(d, n, unit=)}: dates worked out in Java from the as-of date and the calendar, bound as parameters.</li>
 * </ul>
 * While the compiler only plans (finds the tables a measure reads), a context function stands for its aggregate.
 */
final class ContextFunctions {

    /** The period units of {@code opening}, {@code closing} and {@code shift_date}. */
    static final Set<String> PERIODS = Set.of("day", "week", "month", "quarter", "year");
    static final Set<String> UNITS = Set.of("business-day", "day", "week", "month", "quarter", "year");
    /** How far back {@code closing} looks for period ends when the query has no window (its rows can only be in what was read). */
    static final int CLOSING_YEARS = 5;

    private ContextFunctions() {}

    static String emit(SqlEmitter e, Node.Call c, boolean inAgg) {
        String f = c.function();
        List<Node> pos = Node.positional(c);
        switch (f) {
            case "asof" -> {
                e.params().add(asOf(e).toString());
                return "CAST(? AS DATE)";
            }
            case "shift_date" -> {
                return shiftDate(e, c, inAgg);
            }
            case "fx", "convert" -> {
                return Conversion.emit(e, c, inAgg);
            }
            default -> { }
        }
        Node agg = pos.get(0);
        if (e.isPlanning()) {
            Node.Call where = Node.keyword(c, "where");
            return where == null || where.args().isEmpty() ? e.emit(agg, inAgg) : e.restricted(where.args().get(0), agg, inAgg);
        }
        return switch (f) {
            case "calc" -> calc(e, c, agg, inAgg);
            case "share_of" -> {
                Node.Call over = Node.keyword(c, "over");
                Node total = new Node.Call("calc", List.of(agg, new Node.Call(Node.KEYWORD + "ignore", over == null ? List.of() : over.args())));
                yield e.emit(new Node.Bin("/", agg, total), inAgg);
            }
            case "parent_of" -> parentOf(e, c, agg, inAgg);
            case "avg_by" -> {
                Node.Call by = Node.keyword(c, "by");
                yield contexts(e, f).register(Contexts.Kind.AVERAGE, kept(agg, e), null, null, null, by == null ? List.of() : by.args(), null, null, null);
            }
            case "avg_over" -> contexts(e, f).register(Contexts.Kind.AVERAGE, kept(agg, e), null, null, null, List.of(pos.get(1)), null, null, null);
            case "as_of" -> contexts(e, f).register(Contexts.Kind.DATED, kept(agg, e), null, null, null, null, constant(e, pos.get(1)), null, null);
            case "last", "first" -> {
                Node date = pos.get(1);
                String edge = contexts(e, f).register(Contexts.Kind.EDGE, null, null, null, null, null, null, date, "last".equals(f) ? "max" : "min");
                yield e.restricted("(" + dateSql(e, date) + " = " + edge + ")", agg, inAgg);
            }
            case "closing" -> e.restricted(closingDays(e, pos.get(1), period(c)), agg, inAgg);
            case "opening" -> opening(e, c, agg, pos.get(1), inAgg);
            default -> throw new DrishtiException(ErrorCode.DATASET_NOT_BUILT, "'" + f + "' is not built yet");
        };
    }

    // ---- filter context ----------------------------------------------------------------------------------------------

    private static String calc(SqlEmitter e, Node.Call c, Node agg, boolean inAgg) {
        Node.Call where = Node.keyword(c, "where");
        Node.Call ignore = Node.keyword(c, "ignore");
        Node.Call keep = Node.keyword(c, "keep");
        Node.Call via = Node.keyword(c, "via");
        Node cond = where == null || where.args().isEmpty() || where.args().get(0) instanceof Node.Null ? null : where.args().get(0);
        String viaText = via == null || via.args().isEmpty() || !(via.args().get(0) instanceof Node.Str s) ? null : s.value();
        boolean edits = ignore != null && !ignore.args().isEmpty() || keep != null || viaText != null;
        if (!edits) {
            return cond == null ? e.emit(agg, inAgg) : e.restricted(cond, agg, inAgg);
        }
        Node inner = kept(cond == null ? agg : new Node.Call("calc", List.of(agg, new Node.Call(Node.KEYWORD + "where", List.of(cond)))), e);
        return contexts(e, "calc").register(Contexts.Kind.EDIT, inner, ignore == null ? null : ignore.args(), keep == null ? null : keep.args(), viaText, null, null, null, null);
    }

    /** The aggregate with the conditions the emitter is now under (an outer {@code calc(where=)}), which a context sub-query must keep. */
    private static Node kept(Node agg, SqlEmitter e) {
        List<Node> conds = e.activeConditions();
        if (conds.isEmpty()) {
            return agg;
        }
        Node all = conds.get(0);
        for (int i = 1; i < conds.size(); i++) {
            all = new Node.Bin("&&", all, conds.get(i));
        }
        return new Node.Call("calc", List.of(agg, new Node.Call(Node.KEYWORD + "where", List.of(all))));
    }

    private static String parentOf(SqlEmitter e, Node.Call c, Node agg, boolean inAgg) {
        Node.Call h = Node.keyword(c, "hierarchy");
        String name = h == null || h.args().isEmpty() || !(h.args().get(0) instanceof Node.Str s) ? "" : s.value();
        List<String> levels = e.dataset().hierarchies().get(name);
        if (levels == null) {
            throw new DrishtiException(ErrorCode.MQ_CONTEXT, "parent_of(): hierarchy='" + name + "' names no hierarchy of this model (hierarchies: " + e.dataset().hierarchies().keySet() + ")");
        }
        Contexts ctx = contexts(e, "parent_of");
        int deepest = -1;
        String field = null;
        for (int i = 0; i < levels.size(); i++) {
            for (SqlCompiler.Field f : ctx.fields()) {
                if (!f.masked() && same(e, levels.get(i), f)) {
                    deepest = i;
                    field = f.text();
                }
            }
        }
        if (deepest <= 0) {
            return "CAST(NULL AS " + e.dialect().doubleType() + ")";                // the top level has no parent; no level in the answer, no parent either
        }
        Node parent = new Node.Call("calc", List.of(agg, new Node.Call(Node.KEYWORD + "ignore", List.of(new Node.Ref(List.of(field.split("\\.", -1)))))));
        return e.emit(new Node.Bin("/", agg, parent), inAgg);
    }

    private static boolean same(SqlEmitter e, String level, SqlCompiler.Field f) {
        if (level.equals(f.text())) {
            return true;
        }
        try {
            Resolver.ColRef r = e.resolver().resolve(List.of(level.split("\\.", -1)), null);
            return r.table().equals(f.ref().table()) && java.util.Objects.equals(r.column(), f.ref().column()) && java.util.Objects.equals(r.part(), f.ref().part());
        } catch (IllegalArgumentException x) {
            return false;
        }
    }

    private static Contexts contexts(SqlEmitter e, String f) {
        Contexts ctx = e.contexts();
        if (ctx == null) {
            throw new DrishtiException(ErrorCode.MQ_CONTEXT, f + "() cannot be worked out here (a rollup, a live aggregate or a row expression has no filter context): ask it in a query of the model");
        }
        return ctx;
    }

    // ---- balances ----------------------------------------------------------------------------------------------------

    private static String period(Node.Call c) {
        Node.Call p = Node.keyword(c, "period");
        String period = p == null || p.args().isEmpty() || !(p.args().get(0) instanceof Node.Str s) ? "month" : s.value();
        if (!PERIODS.contains(period)) {
            throw new DrishtiException(ErrorCode.MQ_CONTEXT, c.function() + "(): period is one of " + new java.util.TreeSet<>(PERIODS) + ", not '" + period + "'");
        }
        return period;
    }

    /** The date column's SQL, as a date. */
    private static String dateSql(SqlEmitter e, Node date) {
        String sql = e.emit(date, true);
        Resolver.ColRef col = column(e, date);
        Dataset.Column dc = e.resolver().column(col.table(), col.column());
        return dc != null && "timestamp".equals(dc.type()) ? "CAST(" + sql + " AS DATE)" : sql;
    }

    private static Resolver.ColRef column(SqlEmitter e, Node date) {
        if (!(date instanceof Node.Ref r)) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "a balance function's second argument is a date field");
        }
        try {
            return e.resolver().resolve(r.parts(), null);
        } catch (IllegalArgumentException x) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, x.getMessage());
        }
    }

    /** {@code date in [the closing business day of each period from the window's start to the as-of date]}. */
    private static Node closingDays(SqlEmitter e, Node date, String period) {
        TimeWindows.Context t = time(e);
        LocalDate asOf = t.asOf();
        LocalDate lo = t.from() != null && t.from().isBefore(asOf) ? t.from() : asOf.minusYears(CLOSING_YEARS);
        List<Node> days = new ArrayList<>();
        for (LocalDate start = start(lo, period); !start.isAfter(asOf); start = next(start, period)) {
            LocalDate end = next(start, period).minusDays(1);
            LocalDate close = onOrBefore(t.calendar(), end.isAfter(asOf) ? asOf : end);
            if (!close.isBefore(lo) && !close.isBefore(start)) {
                days.add(new Node.Call("date", List.of(new Node.Str(close.toString()))));
            }
        }
        if (days.isEmpty()) {
            return new Node.Bool(false);
        }
        Node d = date;
        Resolver.ColRef col = column(e, date);
        Dataset.Column dc = e.resolver().column(col.table(), col.column());
        if (dc != null && "timestamp".equals(dc.type())) {
            d = new Node.Call("date", List.of(date));
        }
        return new Node.In(d, days, false);
    }

    private static String opening(SqlEmitter e, Node.Call c, Node agg, Node date, boolean inAgg) {
        String period = period(c);
        Resolver.ColRef col = column(e, date);
        if (e.hasField(new Resolver.ColRef(col.table(), col.column(), null))) {
            // along the answer's date field: each period's opening is the closing of the row before it
            Node closing = new Node.Call("closing", List.of(agg, date, new Node.Call(Node.KEYWORD + "period", List.of(new Node.Str(period)))));
            return e.emit(new Node.Call("lag", List.of(closing, new Node.Num(1, true), date)), inAgg);
        }
        TimeWindows.Context t = time(e);
        LocalDate before = t.calendar().previous(start(t.asOf(), period));
        if (Dataset.DATE.equals(col.column())) {
            // the business date of a snapshot: that day's rows are another partition
            return contexts(e, "opening").register(Contexts.Kind.DATED, kept(agg, e), null, null, null, null, before, null, null);
        }
        return e.restricted(new Node.Bin("==", date, new Node.Call("date", List.of(new Node.Str(before.toString())))), agg, inAgg);
    }

    // ---- dates -------------------------------------------------------------------------------------------------------

    private static TimeWindows.Context time(SqlEmitter e) {
        TimeWindows.Context t = e.timeContext();
        if (t == null) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "this function needs an as-of date and a calendar, which this query was not given");
        }
        return t;
    }

    static LocalDate asOf(SqlEmitter e) {
        return time(e).asOf();
    }

    /** A date worked out before the query runs: {@code date('...')}, {@code asof()}, {@code shift_date(<one of these>, n, unit=)}. */
    static LocalDate constant(SqlEmitter e, Node n) {
        LocalDate d = constantOrNull(e, n);
        if (d == null) {
            throw new DrishtiException(ErrorCode.MQ_CONTEXT, "as_of() needs a date it can work out before the query: date('2026-09-30'), asof(), or shift_date(asof(), -1)");
        }
        return d;
    }

    private static LocalDate constantOrNull(SqlEmitter e, Node n) {
        try {
            return switch (n) {
                case Node.Str s -> LocalDate.parse(s.value());
                case Node.Call c when "date".equals(c.function()) && c.args().size() == 1 && c.args().get(0) instanceof Node.Str s -> LocalDate.parse(s.value());
                case Node.Call c when "asof".equals(c.function()) -> asOf(e);
                case Node.Call c when "shift_date".equals(c.function()) -> {
                    List<Node> pos = Node.positional(c);
                    LocalDate base = constantOrNull(e, pos.get(0));
                    yield base == null ? null : shift(base, count(pos.get(1)), unit(c), time(e).calendar());
                }
                default -> null;
            };
        } catch (java.time.format.DateTimeParseException x) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "not a date: " + x.getParsedString());
        }
    }

    private static int count(Node n) {
        if (n instanceof Node.Num x) {
            return (int) x.value();
        }
        if (n instanceof Node.Un u && "-".equals(u.op()) && u.operand() instanceof Node.Num x) {
            return -(int) x.value();
        }
        throw new DrishtiException(ErrorCode.QUERY_INVALID, "shift_date(): n is a whole number written in the expression");
    }

    private static String unit(Node.Call c) {
        Node.Call u = Node.keyword(c, "unit");
        String unit = u == null || u.args().isEmpty() || !(u.args().get(0) instanceof Node.Str s) ? "business-day" : s.value();
        if (!UNITS.contains(unit)) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "shift_date(): unit is one of " + new java.util.TreeSet<>(UNITS) + ", not '" + unit + "'");
        }
        return unit;
    }

    /** A date moved by {@code n} units (business days on the calendar: holidays and weekends skipped). */
    static LocalDate shift(LocalDate d, int n, String unit, BusinessCalendar cal) {
        return switch (unit) {
            case "day" -> d.plusDays(n);
            case "week" -> d.plusWeeks(n);
            case "month" -> d.plusMonths(n);
            case "quarter" -> d.plusMonths(3L * n);
            case "year" -> d.plusYears(n);
            default -> {
                LocalDate x = d;
                for (int i = 0; i < Math.abs(n); i++) {
                    x = n < 0 ? cal.previous(x) : cal.next(x);
                }
                yield x;
            }
        };
    }

    private static String shiftDate(SqlEmitter e, Node.Call c, boolean inAgg) {
        if (e.isPlanning()) {
            return e.emit(Node.positional(c).get(0), inAgg);
        }
        LocalDate fixed = constantOrNull(e, c);
        if (fixed != null) {
            e.params().add(fixed.toString());
            return "CAST(? AS DATE)";
        }
        List<Node> pos = Node.positional(c);
        String unit = unit(c);
        int n = count(pos.get(1));
        if ("business-day".equals(unit)) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "shift_date() of a column moves it by day, week, month, quarter or year; business days need a fixed date "
                    + "(asof() or date('...')) because they follow the calendar");
        }
        String d = e.emit(pos.get(0), inAgg);
        String interval = switch (unit) {
            case "day" -> "to_days(CAST(? AS INTEGER))";
            case "week" -> "to_days(CAST(? AS INTEGER) * 7)";
            case "month" -> "to_months(CAST(? AS INTEGER))";
            case "quarter" -> "to_months(CAST(? AS INTEGER) * 3)";
            default -> "to_years(CAST(? AS INTEGER))";
        };
        e.params().add(n);
        return "CAST(CAST(" + d + " AS DATE) + " + interval + " AS DATE)";
    }

    /** The first day of the period that holds {@code d}. */
    static LocalDate start(LocalDate d, String period) {
        return switch (period) {
            case "day" -> d;
            case "week" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> d.withDayOfMonth(1);
            case "quarter" -> LocalDate.of(d.getYear(), ((d.getMonthValue() - 1) / 3) * 3 + 1, 1);
            default -> LocalDate.of(d.getYear(), 1, 1);
        };
    }

    /** The first day of the period after the one starting at {@code start}. */
    static LocalDate next(LocalDate start, String period) {
        return switch (period) {
            case "day" -> start.plusDays(1);
            case "week" -> start.plusWeeks(1);
            case "month" -> start.plusMonths(1);
            case "quarter" -> start.plusMonths(3);
            default -> start.plusYears(1);
        };
    }

    /** The business day on or before {@code d}. */
    static LocalDate onOrBefore(BusinessCalendar cal, LocalDate d) {
        return cal.isBusinessDay(d) ? d : cal.previous(d);
    }
}
