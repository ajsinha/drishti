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
package com.ash.drishti.banking.var;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Period statistics over the official records (VAR_GUIDE.md section 18.10; REGULATORY_VAR_REPORTING.md section 4.4): a node's period end,
 * average, high and low of a measure over a fiscal year or quarter (or any dates), the 60-business-day average of VaR and the 12-week
 * average of stressed VaR the capital formula takes. Read from the stored official records, never recomputed; a weekday inside the period
 * without an official record is named, never skipped silently.
 */
final class VarPeriodStats {

    /** A period: its name and its first and last day. */
    record Period(String name, LocalDate from, LocalDate to) {}

    private static final Pattern FY = Pattern.compile("(?i)^FY(\\d{4})(?:Q([1-4]))?$");
    private static final Pattern RANGE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\.\\.(\\d{4}-\\d{2}-\\d{2})$");
    /** The measures of an official record a statistic may read. */
    static final List<String> MEASURES = List.of("var", "es", "svar", "ses");

    private final VarOfficialStore store;
    private final RegulatoryProperties props;
    private final int stressWeeks;
    /** The desk P&amp;L, which tells a business day from a holiday (null: every weekday is one). */
    private final VarDeskPnl pnl;

    VarPeriodStats(VarOfficialStore store, RegulatoryProperties props, int stressWeeks, VarDeskPnl pnl) {
        this.store = store;
        this.props = props;
        this.stressWeeks = stressWeeks;
        this.pnl = pnl;
    }

    /**
     * {@code FY2026} (the fiscal year ending in 2026 on the configured year end: 2025-11-01 to 2026-10-31 with {@code 10-31}),
     * {@code FY2026Q1} (its first quarter), or {@code 2026-01-01..2026-03-31}. IllegalArgumentException in words otherwise.
     */
    static Period period(String text, MonthDay yearEnd) {
        String t = text == null ? "" : text.strip();
        Matcher m = FY.matcher(t);
        if (m.matches()) {
            int y = Integer.parseInt(m.group(1));
            LocalDate end = yearEnd.atYear(y);
            LocalDate start = yearEnd.atYear(y - 1).plusDays(1);
            if (m.group(2) != null) {
                int q = Integer.parseInt(m.group(2));
                LocalDate qs = start.plusMonths(3L * (q - 1));
                return new Period("FY" + y + "Q" + q, qs, qs.plusMonths(3).minusDays(1));
            }
            return new Period("FY" + y, start, end);
        }
        Matcher r = RANGE.matcher(t);
        if (r.matches()) {
            LocalDate a = LocalDate.parse(r.group(1));
            LocalDate b = LocalDate.parse(r.group(2));
            if (b.isBefore(a)) {
                throw new IllegalArgumentException("the period " + t + " ends before it starts");
            }
            return new Period(t, a, b);
        }
        throw new IllegalArgumentException("a period is FY2026 (the fiscal year), FY2026Q1 (a fiscal quarter) or 2026-01-01..2026-03-31, not '"
                + t + "'");
    }

    /** The fiscal year a date is in ({@code FY2026}). */
    static String fiscalYear(LocalDate d, MonthDay yearEnd) {
        LocalDate end = yearEnd.atYear(d.getYear());
        return "FY" + (d.isAfter(end) ? d.getYear() + 1 : d.getYear());
    }

    /** The same period one year earlier (the comparative of a report). */
    static Period priorYear(Period p, MonthDay yearEnd) {
        Matcher m = FY.matcher(p.name());
        if (m.matches()) {
            int y = Integer.parseInt(m.group(1)) - 1;
            return period("FY" + y + (m.group(2) == null ? "" : "Q" + m.group(2)), yearEnd);
        }
        return new Period(p.from().minusYears(1) + ".." + p.to().minusYears(1), p.from().minusYears(1), p.to().minusYears(1));
    }

    /** One value of a node on an official date, with the record it came from. */
    record Point(LocalDate date, double value, Object resultVersion, Object captureId, boolean synthetic) {}

    /** The node's values of a measure on the official dates of a period (to the newest record when the period is not over). */
    List<Point> points(String node, String measure, Period p) {
        List<Point> out = new ArrayList<>();
        for (LocalDate d : store.dates().subSet(p.from(), true, p.to(), true)) {
            Map<String, Object> rec = store.index(d).orElse(null);
            if (rec != null && rec.get("nodes") instanceof Map<?, ?> nodes && nodes.get(node) instanceof Map<?, ?> f) {
                double v = VarOfficialStore.num(f.get(measure));
                if (Double.isFinite(v)) {
                    out.add(new Point(d, v, rec.get("resultVersion"), f.get("captureId"), Boolean.TRUE.equals(rec.get("synthetic"))));
                }
            }
        }
        return out;
    }

    /** The statistics of a node's measure over a period. */
    Map<String, Object> stats(String node, String measure, Period p) {
        if (!MEASURES.contains(measure)) {
            throw new IllegalArgumentException("a statistic reads " + MEASURES + " of the official records, not " + measure);
        }
        List<Point> pts = points(node, measure, p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node);
        out.put("measure", measure);
        out.put("period", p.name());
        out.put("from", p.from().toString());
        out.put("to", p.to().toString());
        LocalDate newest = store.dates().isEmpty() ? null : store.dates().last();
        boolean preview = newest == null || newest.isBefore(p.to());
        out.put("preview", preview);
        if (preview) {
            out.put("previewNote", p.name() + " closes " + p.to() + (newest == null ? "" : ": the figures run to the newest official record, " + newest)
                    + "; the run of record is made on the period end");
        }
        out.put("records", pts.size());
        out.put("synthetic", pts.stream().filter(Point::synthetic).count());
        if (pts.isEmpty()) {
            out.put("note", "no official " + measure + " of " + node + " in " + p.name());
            return out;
        }
        Point hi = pts.get(0);
        Point lo = pts.get(0);
        double sum = 0;
        for (Point x : pts) {
            sum += x.value();
            hi = x.value() > hi.value() ? x : hi;
            lo = x.value() < lo.value() ? x : lo;
        }
        Point end = pts.get(pts.size() - 1);
        out.put("periodEnd", point(end));
        out.put("average", sum / pts.size());
        out.put("high", point(hi));
        out.put("low", point(lo));
        out.put("missing", missing(pts, p, newest));
        // the capital formula's averages, to the period end (VAR_GUIDE.md section 18.10)
        if ("var".equals(measure)) {
            out.put("average" + props.averageDays(), lastAverage(node, "var", end.date(), props.averageDays()));
            out.put("stressedAverage", store.stressAverage(node, end.date(), stressWeeks));
        }
        return out;
    }

    private static Map<String, Object> point(Point x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", x.date().toString());
        m.put("value", x.value());
        m.put("resultVersion", x.resultVersion());
        m.put("captureId", x.captureId());
        return m;
    }

    /**
     * The business days from the period's start to its last official date (or end) without an official record: a weekday is a business day
     * unless the desk P&amp;L is read and has no row of it (a holiday: no P&amp;L, no record).
     */
    private List<String> missing(List<Point> pts, Period p, LocalDate newest) {
        java.util.Set<LocalDate> have = new java.util.HashSet<>();
        pts.forEach(x -> have.add(x.date()));
        LocalDate last = newest == null || newest.isAfter(p.to()) ? p.to() : newest;
        LocalDate first = store.dates().isEmpty() ? p.from() : (store.dates().first().isAfter(p.from()) ? store.dates().first() : p.from());
        List<String> out = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !have.contains(d)) {
                VarDeskPnl.Day day = pnl == null ? null : pnl.day(d);
                if (day == null || !day.read() || !day.rows().isEmpty()) {
                    out.add(d.toString());
                }
            }
        }
        if (first.isAfter(p.from())) {
            out.add(0, "before " + first + ": no official record at all (the records start " + first + ")");
        }
        return out;
    }

    /** The mean of a node's measure over its last {@code n} official records to a date. */
    Map<String, Object> lastAverage(String node, String measure, LocalDate upTo, int n) {
        List<Double> vals = new ArrayList<>();
        LocalDate from = null;
        for (LocalDate d : store.dates().headSet(upTo, true).descendingSet()) {
            if (vals.size() >= n) {
                break;
            }
            Map<String, Object> rec = store.index(d).orElse(null);
            if (rec != null && rec.get("nodes") instanceof Map<?, ?> nodes && nodes.get(node) instanceof Map<?, ?> f
                    && Double.isFinite(VarOfficialStore.num(f.get(measure)))) {
                vals.add(VarOfficialStore.num(f.get(measure)));
                from = d;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", n);
        m.put("records", vals.size());
        m.put("from", from == null ? null : from.toString());
        m.put("to", upTo.toString());
        m.put("average", vals.isEmpty() ? null : vals.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
        if (vals.size() < n) {
            m.put("note", "only " + vals.size() + " official records to " + upTo + ", of the " + n + " the average takes");
        }
        return m;
    }
}
