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
package com.ash.drishti.server.bi.report;

import com.ash.drishti.server.bi.report.ReportParser.Problem;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A paginated report, or statement (docs/architecture/BI_DESIGN.md section 12.2, wave D server item 1; docs/guides/BI_GUIDE.md): a report page with
 * {@code layout: statement} is laid out on paper, page by page, as boards became a report layout in 2.4.0. Its visuals are <b>bands</b> drawn top to bottom in
 * the order of their rows ({@code at.y}, then {@code at.x}): text, cards, tables and matrices; the report's {@code statement:} sets the paper:
 *
 * <pre>
 * statement:
 *   paper: A4                     # A4, A3, A5, Letter, Legal
 *   orientation: portrait         # or landscape
 *   margins: { top: 18, right: 15, bottom: 18, left: 15 }      # millimetres
 *   header: { left: "{title}", right: "{period}" }              # left, center, right; tokens below
 *   footer: { left: "{reader} · run {run-date}", right: "Page {page} of {pages}" }
 *   period: previous-month        # what the statement covers: none, day, month, previous-month, quarter, previous-quarter, year, previous-year
 *   dates: end                    # its queries read the period's last day (end) or every day of it (window)
 *   fontSize: 9                   # points, 7 to 12
 * </pre>
 *
 * A band's own {@code format} adds {@code pageBreak} ({@code before}, {@code after}), {@code keepTogether} (never split across pages), {@code repeatHeader}
 * (a table's column titles on every page it runs onto, the default) and {@code groupBy} (a table's rows in groups of one field's value, each group kept on one
 * page when it fits). {@link #check} is the report schema's part ({@code DRS-9038}); {@link #of} reads the settings with their defaults. Pure.
 */
public record StatementSpec(String paper, boolean landscape, double[] margins, Map<String, String> header, Map<String, String> footer, String period, String dates,
        int fontSize) {

    static final String CODE = "DRS-9038";
    /** Paper sizes in millimetres, portrait (width, height). */
    public static final Map<String, double[]> PAPERS = Map.of("A3", new double[] {297, 420}, "A4", new double[] {210, 297}, "A5", new double[] {148, 210},
            "Letter", new double[] {215.9, 279.4}, "Legal", new double[] {215.9, 355.6});
    public static final List<String> PERIODS = List.of("none", "day", "month", "previous-month", "quarter", "previous-quarter", "year", "previous-year");
    /** The visual types a statement draws as bands. */
    public static final Set<String> BANDS = Set.of("text", "card", "multirow-card", "kpi", "gauge", "table", "matrix");
    /** Bands that run over pages (one to a row). */
    public static final Set<String> FLOWING = Set.of("table", "matrix", "multirow-card");
    static final Set<String> KEYS = Set.of("paper", "orientation", "margins", "header", "footer", "period", "dates", "fontSize");
    static final Set<String> LINE_KEYS = Set.of("left", "center", "right");
    static final Set<String> TOKENS = Set.of("title", "page", "pages", "run-date", "run-time", "period", "from", "to", "as-of", "reader");
    static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");
    static final double[] DEFAULT_MARGINS = {18, 15, 18, 15};
    static final Map<String, String> DEFAULT_HEADER = Map.of("left", "{title}", "right", "{period}");
    static final Map<String, String> DEFAULT_FOOTER = Map.of("left", "{reader} · run {run-date}", "right", "Page {page} of {pages}");

    public StatementSpec {
        margins = margins.clone();
        header = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(header));
        footer = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(footer));
    }

    @Override
    public double[] margins() {
        return margins.clone();
    }

    /** The page's width and height in points (1/72 inch), as oriented. */
    public double[] pagePoints() {
        double[] mm = PAPERS.getOrDefault(paper, PAPERS.get("A4"));
        double w = mm[0] * 72 / 25.4;
        double h = mm[1] * 72 / 25.4;
        return landscape ? new double[] {h, w} : new double[] {w, h};
    }

    /** A margin in points: 0 top, 1 right, 2 bottom, 3 left. */
    public double marginPoints(int side) {
        return margins[side] * 72 / 25.4;
    }

    /** The settings of a report, with the defaults for what it leaves out (bad values are {@link #check}'s; here they fall back to the default). */
    public static StatementSpec of(Map<String, Object> raw, String defaultPaper) {
        Map<String, Object> s = raw == null ? Map.of() : raw;
        String paper = paperName(String.valueOf(s.getOrDefault("paper", defaultPaper == null ? "A4" : defaultPaper)));
        boolean landscape = "landscape".equals(String.valueOf(s.getOrDefault("orientation", "portrait")).toLowerCase(Locale.ROOT));
        double[] m = DEFAULT_MARGINS.clone();
        if (s.get("margins") instanceof Map<?, ?> mm) {
            String[] sides = {"top", "right", "bottom", "left"};
            for (int i = 0; i < 4; i++) {
                if (mm.get(sides[i]) instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 60) {
                    m[i] = n.doubleValue();
                }
            }
        } else if (s.get("margins") instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 60) {
            java.util.Arrays.fill(m, n.doubleValue());
        }
        String period = String.valueOf(s.getOrDefault("period", "none"));
        String dates = String.valueOf(s.getOrDefault("dates", "end"));
        int font = s.get("fontSize") instanceof Number n && n.intValue() >= 7 && n.intValue() <= 12 ? n.intValue() : 9;
        return new StatementSpec(paper == null ? "A4" : paper, landscape, m, line(s.get("header"), DEFAULT_HEADER), line(s.get("footer"), DEFAULT_FOOTER),
                PERIODS.contains(period) ? period : "none", "window".equals(dates) ? "window" : "end", font);
    }

    private static Map<String, String> line(Object v, Map<String, String> dflt) {
        if (v == null) {
            return dflt;
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            for (String k : List.of("left", "center", "right")) {
                if (m.get(k) != null) {
                    out.put(k, String.valueOf(m.get(k)));
                }
            }
        }
        return out;                                                          // header: {} or false: no header line
    }

    /** The paper's name as the table spells it ({@code a4} is A4, {@code letter} Letter), or null. */
    static String paperName(String s) {
        return PAPERS.keySet().stream().filter(k -> k.equalsIgnoreCase(s.strip())).findFirst().orElse(null);
    }

    // ---- the period ------------------------------------------------------------------------------------------------

    /** What a statement covers, worked out from its date (the run's business date): the first and last day, and how a header names it. */
    public record Period(LocalDate from, LocalDate to, String label) {}

    /** The period of {@code period} that a statement dated {@code date} covers; {@code none} and {@code day} are the date itself. */
    public static Period period(String period, LocalDate date) {
        return switch (period == null ? "none" : period) {
            case "month" -> month(YearMonth.from(date));
            case "previous-month" -> month(YearMonth.from(date).minusMonths(1));
            case "quarter" -> quarter(date);
            case "previous-quarter" -> quarter(date.minusMonths(3));
            case "year" -> new Period(LocalDate.of(date.getYear(), 1, 1), LocalDate.of(date.getYear(), 12, 31), String.valueOf(date.getYear()));
            case "previous-year" -> new Period(LocalDate.of(date.getYear() - 1, 1, 1), LocalDate.of(date.getYear() - 1, 12, 31), String.valueOf(date.getYear() - 1));
            default -> new Period(date, date, date.toString());
        };
    }

    private static Period month(YearMonth m) {
        return new Period(m.atDay(1), m.atEndOfMonth(), m.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + m.getYear());
    }

    private static Period quarter(LocalDate d) {
        int q = (d.getMonthValue() - 1) / 3;
        LocalDate from = LocalDate.of(d.getYear(), q * 3 + 1, 1);
        return new Period(from, from.plusMonths(3).minusDays(1), d.getYear() + "-Q" + (q + 1));
    }

    // ---- tokens --------------------------------------------------------------------------------------------------

    /** The text with each {@code {token}} replaced from {@code values}; an unknown token is left as written. */
    public static String fill(String text, Map<String, String> values) {
        if (text == null || text.indexOf('{') < 0) {
            return text == null ? "" : text;
        }
        Matcher m = TOKEN.matcher(text);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(b, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(b);
        return b.toString();
    }

    // ---- the schema's checks -------------------------------------------------------------------------------------

    /** The problems of a report's statement settings and statement pages ({@code DRS-9038}); empty for a report with neither. */
    public static List<Problem> check(Report r) {
        List<Problem> out = new ArrayList<>();
        Map<String, Object> s = r.statement();
        boolean pages = r.hasStatement();
        if (!s.isEmpty() && !pages) {
            out.add(new Problem("warning", CODE, "statement", "`statement:` sets the paper of the statement pages, and no page has layout: statement"));
        }
        settings(s, out);
        for (int pi = 0; pi < r.pages().size(); pi++) {
            Report.Page page = r.pages().get(pi);
            if (page.statement()) {
                page(page, "pages[" + pi + "]", out);
            }
        }
        return out;
    }

    private static void settings(Map<String, Object> s, List<Problem> out) {
        s.keySet().stream().filter(k -> !KEYS.contains(k)).forEach(k -> out.add(new Problem("warning", CODE, "statement." + k, "unknown statement key '" + k + "' is ignored ("
                + String.join(", ", KEYS.stream().sorted().toList()) + ")")));
        if (s.containsKey("paper") && paperName(String.valueOf(s.get("paper"))) == null) {
            out.add(ReportParser.error(CODE, "statement.paper", "paper is one of " + PAPERS.keySet().stream().sorted().toList() + ", not '" + s.get("paper") + "'"));
        }
        if (s.containsKey("orientation") && !Set.of("portrait", "landscape").contains(String.valueOf(s.get("orientation")).toLowerCase(Locale.ROOT))) {
            out.add(ReportParser.error(CODE, "statement.orientation", "orientation is portrait or landscape"));
        }
        Object m = s.get("margins");
        if (m instanceof Map<?, ?> mm) {
            mm.forEach((k, v) -> {
                if (!Set.of("top", "right", "bottom", "left").contains(String.valueOf(k))) {
                    out.add(ReportParser.error(CODE, "statement.margins." + k, "margins are top, right, bottom and left, in millimetres"));
                } else if (!(v instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() > 60) {
                    out.add(ReportParser.error(CODE, "statement.margins." + k, "a margin is 0 to 60 millimetres, not '" + v + "'"));
                }
            });
        } else if (m != null && (!(m instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() > 60)) {
            out.add(ReportParser.error(CODE, "statement.margins", "margins is { top, right, bottom, left } in millimetres, or one number for all four (0 to 60)"));
        }
        for (String key : List.of("header", "footer")) {
            Object v = s.get(key);
            if (v == null || Boolean.FALSE.equals(v)) {
                continue;
            }
            if (!(v instanceof Map<?, ?> line)) {
                out.add(ReportParser.error(CODE, "statement." + key, key + " is { left, center, right }: the text of each part, with tokens such as {page} and {run-date}"));
                continue;
            }
            line.forEach((k, t) -> {
                if (!LINE_KEYS.contains(String.valueOf(k))) {
                    out.add(ReportParser.error(CODE, "statement." + key + "." + k, "a " + key + " has left, center and right"));
                } else {
                    tokens(String.valueOf(t), "statement." + key + "." + k, out);
                }
            });
        }
        if (s.containsKey("period") && !PERIODS.contains(String.valueOf(s.get("period")))) {
            out.add(ReportParser.error(CODE, "statement.period", "period is one of " + PERIODS + ", not '" + s.get("period") + "'"));
        }
        if (s.containsKey("dates") && !Set.of("end", "window").contains(String.valueOf(s.get("dates")))) {
            out.add(ReportParser.error(CODE, "statement.dates", "dates is end (the period's last day) or window (every day of the period)"));
        }
        if (s.containsKey("fontSize") && (!(s.get("fontSize") instanceof Number n) || n.intValue() < 7 || n.intValue() > 12)) {
            out.add(ReportParser.error(CODE, "statement.fontSize", "fontSize is 7 to 12 points"));
        }
    }

    static void tokens(String text, String path, List<Problem> out) {
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            String t = m.group(1);
            if (!TOKENS.contains(t) && !t.startsWith("param.")) {
                out.add(ReportParser.error(CODE, path, "{" + t + "} is not a token: " + TOKENS.stream().sorted().map(x -> "{" + x + "}").toList() + " or {param.<name>}"));
            }
        }
    }

    /**
     * The {@code {param.<name>}} tokens of a report's header, footer, text bands and titles that name no parameter of its model ({@code DRS-9038}): {@code
     * params} are the model's parameter names (none for a report on a dataset). Run once the model is known (QA2-02).
     */
    public static List<Problem> checkParams(Report r, Set<String> params) {
        List<Problem> out = new ArrayList<>();
        if (!r.hasStatement() && r.statement().isEmpty()) {
            return out;
        }
        for (String key : List.of("header", "footer")) {
            if (r.statement().get(key) instanceof Map<?, ?> line) {
                line.forEach((k, t) -> params(String.valueOf(t), "statement." + key + "." + k, params, out));
            }
        }
        for (int pi = 0; pi < r.pages().size(); pi++) {
            Report.Page page = r.pages().get(pi);
            if (!page.statement()) {
                continue;
            }
            for (int vi = 0; vi < page.visuals().size(); vi++) {
                Report.Visual v = page.visuals().get(vi);
                String vp = "pages[" + pi + "].visuals[" + vi + "]";
                params(v.text(), vp + ".text", params, out);
                params(v.title(), vp + ".title", params, out);
            }
        }
        return out;
    }

    private static void params(String text, String path, Set<String> params, List<Problem> out) {
        if (text == null || text.indexOf('{') < 0) {
            return;
        }
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            String t = m.group(1);
            if (t.startsWith("param.") && !params.contains(t.substring(6))) {
                out.add(ReportParser.error(CODE, path, "{" + t + "} names no parameter of the report's model" + (params.isEmpty() ? " (it has none)"
                        : ": " + params.stream().sorted().map(x -> "{param." + x + "}").toList())));
            }
        }
    }

    private static void page(Report.Page page, String pp, List<Problem> out) {
        if (page.tooltip()) {
            out.add(ReportParser.error(CODE, pp + ".tooltip", "a tooltip page is drawn over a mark; a statement page is paper"));
        }
        Map<Integer, List<Report.Visual>> rows = new LinkedHashMap<>();
        for (int vi = 0; vi < page.visuals().size(); vi++) {
            Report.Visual v = page.visuals().get(vi);
            String vp = pp + ".visuals[" + vi + "]";
            if (!BANDS.contains(v.type())) {
                out.add(ReportParser.error(CODE, vp + ".type", "a statement draws " + BANDS.stream().sorted().toList() + "; a " + v.type()
                        + " belongs on a grid page (export that page as PDF for a picture of it)"));
                continue;
            }
            if (v.at() != null) {
                rows.computeIfAbsent(v.at().y(), k -> new ArrayList<>()).add(v);
            }
            Object brk = v.format().get("pageBreak");
            if (brk != null && !Set.of("before", "after", "none").contains(String.valueOf(brk))) {
                out.add(ReportParser.error(CODE, vp + ".format.pageBreak", "pageBreak is before, after or none"));
            }
            Object group = v.format().get("groupBy");
            if (group != null) {
                List<String> fields = "matrix".equals(v.type()) ? v.well("rows") : v.well("columns");
                if (!"table".equals(v.type()) && !"matrix".equals(v.type())) {
                    out.add(ReportParser.error(CODE, vp + ".format.groupBy", "groupBy groups a table's or a matrix's rows"));
                } else if (!fields.contains(String.valueOf(group))) {
                    out.add(ReportParser.error(CODE, vp + ".format.groupBy", "groupBy names a field the " + v.type() + " shows (" + String.join(", ", fields) + "), not '" + group + "'"));
                }
            }
            if (v.text() != null && !v.text().isBlank()) {
                tokens(v.text(), vp + ".text", out);
            }
            if (v.title() != null && v.title().indexOf('{') >= 0) {
                tokens(v.title(), vp + ".title", out);                       // a title takes the same tokens as a text band (QA2-02)
            }
        }
        rows.forEach((y, vs) -> {
            if (vs.size() > 1 && vs.stream().anyMatch(v -> FLOWING.contains(v.type()))) {
                Report.Visual flowing = vs.stream().filter(v -> FLOWING.contains(v.type())).findFirst().orElseThrow();
                out.add(ReportParser.error(CODE, pp + ".visuals[" + page.visuals().indexOf(flowing) + "].at", "a " + flowing.type()
                        + " of a statement runs over pages, so it has its row to itself: move the other visuals of row " + y + " above or below it"));
            }
        });
    }
}
