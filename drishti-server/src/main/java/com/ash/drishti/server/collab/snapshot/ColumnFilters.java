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
package com.ash.drishti.server.collab.snapshot;

import com.ash.drishti.engine.control.ViewState;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.engine.view.ViewModel.Cell;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The column filters of a shared view applied on the server, so a picture shows the rows the sender had left after filtering (the console
 * applies the same filters in the browser; {@code static/js/colfilter.js} is the reference for the grammar and the meaning of each
 * condition). A column is found by its heading, a filter by its address form ({@code in:a,b}, {@code gt:1m}, {@code bt:1~9}, {@code today}).
 * A value filter is a checked list; a condition tests the cell by the column's detected type (number, date or text). A masked cell
 * never matches a condition. A filter on a column that does not exist is ignored; nothing here can fail a render.
 */
public final class ColumnFilters {

    private static final String MASK = "•••";
    private static final Pattern NUMBER = Pattern.compile("^([+-]?)(\\d+(?:\\.\\d+)?)(k|m|mm|bn|b|tn)?(%|bp)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}.*");

    private ColumnFilters() {}

    /** The view with every filtered table's rows reduced; the same view when no filter is present. */
    public static ViewModel apply(ViewModel v, ViewState state, LocalDate today) {
        if (state == null || !state.hasFilters()) {
            return v;
        }
        List<PanelView> panels = v.panels().stream().map(p -> panel(p, state, today)).toList();
        return new ViewModel(v.ref(), v.mnemonic(), v.title(), v.strip(), panels, v.keys(), v.provenance(), v.timings(), v.controls(), v.selects());
    }

    private static PanelView panel(PanelView p, ViewState state, LocalDate today) {
        PanelData data = p.data();
        if (data instanceof PanelData.Table t) {
            Map<String, String> filters = state.filtersOf(p.id());
            if (!filters.isEmpty()) {
                data = table(t, filters, today);
            }
        } else if (data instanceof PanelData.Tabs tabs) {
            List<PanelData.Tab> out = new ArrayList<>();
            for (PanelData.Tab tab : tabs.tabs()) {
                out.add(new PanelData.Tab(tab.title(), tab.fields(), tab.panels().stream().map(x -> panel(x, state, today)).toList()));
            }
            data = new PanelData.Tabs(tabs.layout(), out);
        }
        return data == p.data() ? p : new PanelView(p.id(), p.kind(), p.title(), p.code(), p.key(), p.area(), p.inferred(), p.explanation(), data,
                p.error(), p.empty(), p.span(), p.height(), p.denied());
    }

    private static PanelData.Table table(PanelData.Table t, Map<String, String> filters, LocalDate today) {
        List<String> cols = t.columns() == null ? List.of() : t.columns();
        List<Test> tests = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            String spec = filters.get(norm(cols.get(i)));
            if (spec != null) {
                Test test = Test.of(i, spec, allTexts(t.rows(), i), today);
                if (test != null) {
                    tests.add(test);
                }
            }
        }
        if (tests.isEmpty()) {
            return t;
        }
        List<PanelData.Row> rows = keep(t.rows(), tests);
        return new PanelData.Table(t.columns(), t.numeric(), rows, t.total(), t.more(), t.search(), t.pivot(), t.expand());
    }

    /** Rows that pass every filter, plus a parent that does not but has a child that does (as the console keeps it). */
    private static List<PanelData.Row> keep(List<PanelData.Row> rows, List<Test> tests) {
        List<PanelData.Row> out = new ArrayList<>();
        for (PanelData.Row r : rows == null ? List.<PanelData.Row>of() : rows) {
            List<PanelData.Row> kids = r.children() == null || r.children().isEmpty() ? r.children() : keep(r.children(), tests);
            boolean pass = tests.stream().allMatch(t -> t.passes(r));
            if (pass || (kids != null && !kids.isEmpty())) {
                out.add(kids == r.children() ? r : new PanelData.Row(r.cells(), r.highlight(), r.path(), kids, r.pick()));
            }
        }
        return out;
    }

    private static List<String> allTexts(List<PanelData.Row> rows, int col) {
        List<String> out = new ArrayList<>();
        collect(rows, col, out);
        return out;
    }

    private static void collect(List<PanelData.Row> rows, int col, List<String> out) {
        for (PanelData.Row r : rows == null ? List.<PanelData.Row>of() : rows) {
            out.add(text(r, col));
            collect(r.children(), col, out);
        }
    }

    private static String text(PanelData.Row r, int col) {
        if (r.cells() == null || col >= r.cells().size()) {
            return "";
        }
        Cell c = r.cells().get(col);
        return c == null || c.text() == null ? "" : c.text();
    }

    static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    // ---- values and types ---------------------------------------------------------------------------------------------

    private enum Kind { BLANK, MASK, DATE, NUM, TEXT }

    private static Kind kindOf(String text) {
        String t = norm(text);
        if (t.isEmpty()) {
            return Kind.BLANK;
        }
        if (t.contains(MASK)) {
            return Kind.MASK;
        }
        if (day(t) != null) {
            return Kind.DATE;
        }
        return number(t) != null ? Kind.NUM : Kind.TEXT;
    }

    /** A cell as a number: 1.5m, 250k, -30,205,543, 12%; null when it is not one. */
    static Double number(String text) {
        String t = text.replaceAll("[−–]", "-").replace(",", "").replaceAll("\\s", "");
        Matcher m = NUMBER.matcher(t);
        if (!m.matches()) {
            return null;
        }
        double n = Double.parseDouble(m.group(2)) * mult(m.group(3));
        return "-".equals(m.group(1)) ? -n : n;
    }

    private static double mult(String s) {
        if (s == null) {
            return 1;
        }
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "k" -> 1e3;
            case "m", "mm" -> 1e6;
            case "bn", "b" -> 1e9;
            case "tn" -> 1e12;
            default -> 1;
        };
    }

    /** The day a cell starts with ({@code 2026-10-01...}), or null. */
    private static LocalDate day(String text) {
        if (text == null || !ISO.matcher(text).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static String type(List<String> texts) {
        int num = 0;
        int date = 0;
        int total = 0;
        for (String t : texts) {
            Kind k = kindOf(t);
            if (k == Kind.NUM) {
                num++;
            } else if (k == Kind.DATE) {
                date++;
            }
            if (k == Kind.NUM || k == Kind.DATE || k == Kind.TEXT) {
                total++;
            }
        }
        if (total == 0) {
            return "text";
        }
        return num / (double) total >= 0.8 ? "number" : date / (double) total >= 0.8 ? "date" : "text";
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    // ---- one column's filter --------------------------------------------------------------------------------------------

    /** A column's filter ready to test rows: either a checked list or a condition with whatever it needs from the whole column. */
    private static final class Test {
        private final int col;
        private Set<String> values;
        private boolean blanks;
        private boolean masked;
        private String op;
        private String a;
        private String b;
        private String type;
        private double avg;
        private Double cut;
        private LocalDate today;

        private Test(int col) {
            this.col = col;
        }

        static Test of(int col, String spec, List<String> texts, LocalDate today) {
            Test t = new Test(col);
            int i = spec.indexOf(':');
            String op = i < 0 ? spec : spec.substring(0, i);
            String rest = i < 0 ? "" : spec.substring(i + 1);
            if ("in".equals(op)) {
                t.values = new HashSet<>();
                for (String p : rest.split(",")) {
                    if ("~b".equals(p)) {
                        t.blanks = true;
                    } else if ("~m".equals(p)) {
                        t.masked = true;
                    } else if (!p.isEmpty()) {
                        t.values.add(norm(dec(p)));
                    }
                }
                return t;
            }
            if (!op.matches("[a-z0-9]+")) {
                return null;
            }
            String[] parts = rest.isEmpty() ? new String[0] : rest.split("~", -1);
            t.op = op;
            t.a = parts.length > 0 && !parts[0].isEmpty() ? dec(parts[0]) : null;
            t.b = parts.length > 1 && !parts[1].isEmpty() ? dec(parts[1]) : null;
            t.type = type(texts);
            t.today = today;
            if (op.equals("avga") || op.equals("avgb") || op.equals("top") || op.equals("bot")) {
                List<Double> ns = new ArrayList<>();
                texts.forEach(x -> {
                    if (kindOf(x) == Kind.NUM) {
                        ns.add(number(norm(x)));
                    }
                });
                if (op.startsWith("avg")) {
                    t.avg = ns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                } else {
                    int n = 10;
                    try {
                        n = Math.max(1, Integer.parseInt(t.a == null ? "10" : t.a.strip()));
                    } catch (NumberFormatException e) {
                        // the default of ten, as the console reads a bad count
                    }
                    ns.sort(op.equals("top") ? java.util.Comparator.reverseOrder() : java.util.Comparator.naturalOrder());
                    t.cut = ns.isEmpty() ? null : ns.get(Math.min(n, ns.size()) - 1);
                }
            }
            return t;
        }

        boolean passes(PanelData.Row r) {
            String text = text(r, col);
            if (values != null) {
                Kind k = kindOf(text);
                return k == Kind.BLANK ? blanks : k == Kind.MASK ? masked : values.contains(norm(text));
            }
            return condition(text);
        }

        private boolean condition(String text) {
            Kind k = kindOf(text);
            if (k == Kind.MASK) {
                return false;
            }
            String s = norm(text).toLowerCase(Locale.ROOT);
            String av = a == null ? "" : norm(a).toLowerCase(Locale.ROOT);
            if (type.equals("text") || (type.equals("number") && k != Kind.NUM)) {
                if (type.equals("number")) {
                    return op.equals("ne");
                }
                return switch (op) {
                    case "eq" -> s.equals(av);
                    case "ne" -> !s.equals(av);
                    case "ct" -> s.contains(av);
                    case "nc" -> !s.contains(av);
                    case "bw" -> s.startsWith(av);
                    case "ew" -> s.endsWith(av);
                    default -> false;
                };
            }
            return type.equals("number") ? numeric(number(s)) : k == Kind.DATE && date(day(s));
        }

        private boolean numeric(Double v) {
            Double w = a == null ? null : number(norm(a));
            Double w2 = b == null ? null : number(norm(b));
            double x = v == null ? Double.NaN : v;
            double y = w == null ? Double.NaN : w;
            double z = w2 == null ? Double.NaN : w2;
            return switch (op) {
                case "eq" -> x == y;
                case "ne" -> x != y;
                case "gt" -> x > y;
                case "ge" -> x >= y;
                case "lt" -> x < y;
                case "le" -> x <= y;
                case "bt" -> x >= Math.min(y, z) && x <= Math.max(y, z);
                case "top" -> cut != null && x >= cut;
                case "bot" -> cut != null && x <= cut;
                case "avga" -> x > avg;
                case "avgb" -> x < avg;
                default -> false;
            };
        }

        private boolean date(LocalDate d) {
            if (d == null) {
                return false;
            }
            LocalDate monday = today.with(DayOfWeek.MONDAY);
            LocalDate da = a == null ? null : day(a);
            LocalDate db = b == null ? null : day(b);
            return switch (op) {
                case "today" -> d.equals(today);
                case "yest" -> d.equals(today.minusDays(1));
                case "thisweek" -> !d.isBefore(monday) && !d.isAfter(monday.plusDays(6));
                case "lastweek" -> !d.isBefore(monday.minusDays(7)) && d.isBefore(monday);
                case "thismonth" -> d.getYear() == today.getYear() && d.getMonth() == today.getMonth();
                case "lastmonth" -> ChronoUnit.MONTHS.between(d.withDayOfMonth(1), today.withDayOfMonth(1)) == 1;
                case "last7" -> !d.isBefore(today.minusDays(6)) && !d.isAfter(today);
                case "last30" -> !d.isBefore(today.minusDays(29)) && !d.isAfter(today);
                case "bt" -> da != null && db != null && !d.isBefore(da.isBefore(db) ? da : db) && !d.isAfter(da.isBefore(db) ? db : da);
                case "bf" -> da != null && d.isBefore(da);
                case "af" -> da != null && d.isAfter(da);
                default -> false;
            };
        }
    }
}
