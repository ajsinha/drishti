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
package com.ash.drishti.server.dist;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.bi.report.FieldParams;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.StatementSpec;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Paginated reports and statements (docs/architecture/BI_DESIGN.md section 12.2, wave D server item 1; docs/guides/STATEMENTS.md): the statement pages of
 * a report ({@code layout: statement}) made for <b>one reader</b>. Every band that holds data is asked of the report service as that reader, so their row
 * rules and masks apply exactly as on the screen; the period, the parameters and the business date of the run are the state every band is asked under.
 * The bands are then laid out page by page ({@link StatementLayout}) and written as PDF ({@link StatementPdf}) or Excel ({@link XlsxWriter}, the paper's
 * print setup), or returned as pages for the BI Studio's preview. A subscription to a statement bursts it: one run, and one file, per recipient.
 *
 * <p>Stateless and thread-safe: each run has its own layout and its own small pool of band queries ({@code bandQueries} at a time).
 */
public final class StatementService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    /** Excel's paper size codes. */
    private static final Map<String, Integer> EXCEL_PAPER = Map.of("Letter", 1, "Legal", 5, "A3", 8, "A4", 9, "A5", 11);

    private final BiReportService reports;
    private final BiExporter exporter;
    private final DistProperties.Statements limits;
    private final Clock clock;
    private final ZoneId zone;
    private final Supplier<LocalDate> businessDate;

    public StatementService(BiReportService reports, BiExporter exporter, DistProperties.Statements limits, Clock clock, ZoneId zone, Supplier<LocalDate> businessDate) {
        this.reports = reports;
        this.exporter = exporter;
        this.limits = limits;
        this.clock = clock;
        this.zone = zone;
        this.businessDate = businessDate;
    }

    /** One statement made for one reader: its pages, the same figures as a table document (Excel and the email), and the values its header and footer used. */
    public record Run(String title, StatementSpec spec, StatementPages pages, ExportDoc doc, Map<String, String> tokens, String period, List<String> cut,
            String hint) {

        public Run {
            cut = cut == null ? List.of() : List.copyOf(cut);
        }

        /** A run with no table cut. */
        public Run(String title, StatementSpec spec, StatementPages pages, ExportDoc doc, Map<String, String> tokens, String period) {
            this(title, spec, pages, doc, tokens, period, List.of(), null);
        }
    }

    /**
     * Whether an export of {@code page} of the report is a statement: the page is a statement page, or (no page named) every page of the report a reader
     * sees (tooltip pages aside) is one.
     */
    public static boolean isStatement(Report report, String page) {
        if (report == null || !report.hasStatement()) {
            return false;
        }
        if (page != null && !page.isBlank()) {
            Report.Page p = report.page(page);
            return p != null && p.statement();
        }
        return report.pages().stream().filter(p -> !p.tooltip()).allMatch(Report.Page::statement);
    }

    /** Whether the target of an export or a subscription is a statement, for {@code who}. */
    public boolean isStatement(Principal who, ContentExporter.Target t) {
        return t.isReport() && isStatement(reports.open(who, t.id()).report(), t.page());
    }

    /**
     * Makes the statement for {@code who}.
     *
     * @param reportId a stored report the reader may read, or null with {@code yaml}
     * @param yaml draft report YAML (an author's preview), or null
     * @param page a statement page, or blank for every statement page of the report
     * @param state the view state: slicers, parameters ({@code params}), dates
     * @param date the run's business date (yyyy-mm-dd), or blank for the state's or today's
     */
    public Run run(Principal who, String reportId, String yaml, String page, JsonNode state, String date) {
        BiReportService.Statement st = reports.statement(who, reportId, yaml);
        Report report = st.report();
        List<Report.Page> pages = pagesOf(report, page);
        StatementSpec spec = StatementSpec.of(report.statement(), limits.paper());
        ObjectNode s = state != null && state.isObject() ? ((ObjectNode) state).deepCopy() : JSON.createObjectNode();
        LocalDate day = dayOf(date, s);
        StatementSpec.Period period = StatementSpec.period(spec.period(), day);
        if (!"none".equals(spec.period())) {
            LocalDate end = period.to().isAfter(day) ? day : period.to();
            LocalDate from = "window".equals(spec.dates()) ? period.from() : end;
            s.set("dates", JSON.createObjectNode().put("from", from.toString()).put("to", end.toString()));
        } else if (date != null && !date.isBlank()) {
            s.set("dates", JSON.createObjectNode().put("from", day.toString()).put("to", day.toString()));
        }
        s.put("limit", limits.maxRows() + 1);
        Map<String, String> tokens = tokens(st, who, s, period, spec, day);
        java.util.Set<String> counts = counts(st);
        List<List<List<StatementLayout.Band>>> laid = new ArrayList<>();
        List<ExportDoc.Section> sections = new ArrayList<>();
        List<String> cut = new ArrayList<>();
        String asOf = null;
        List<List<Report.Visual>> visualsOf = new ArrayList<>();
        List<List<BiExporter.Built>> answersOf = new ArrayList<>();
        for (Report.Page p : pages) {                                     // every band asked first: {as-of} is known before any text is filled (QA2-02)
            List<Report.Visual> visuals = new ArrayList<>(p.visuals());
            visuals.sort(Comparator.comparingInt((Report.Visual v) -> v.at() == null ? 0 : v.at().y()).thenComparingInt(v -> v.at() == null ? 0 : v.at().x()));
            List<BiExporter.Built> answers = ask(who, st.id(), yaml, p, visuals, s);
            for (int i = 0; i < visuals.size(); i++) {
                answers.set(i, carded(visuals.get(i), trimmed(visuals.get(i), answers.get(i), cut), counts));
                if (asOf == null && answers.get(i) != null) {
                    asOf = answers.get(i).asOf();
                }
            }
            visualsOf.add(visuals);
            answersOf.add(answers);
        }
        tokens.put("as-of", asOf == null ? day.toString() : asOf);
        for (int pi = 0; pi < pages.size(); pi++) {
            List<Report.Visual> visuals = visualsOf.get(pi);
            List<BiExporter.Built> answers = answersOf.get(pi);
            List<List<StatementLayout.Band>> rows = new ArrayList<>();
            Integer rowY = null;
            for (int i = 0; i < visuals.size(); i++) {
                Report.Visual v = visuals.get(i);
                BiExporter.Built b = answers.get(i);
                if (b != null) {
                    sections.add(b.section());
                }
                int y = v.at() == null ? i : v.at().y();
                if (rowY == null || y != rowY || StatementSpec.FLOWING.contains(v.type()) || rows.get(rows.size() - 1).stream().anyMatch(x -> "table".equals(x.kind()))) {
                    rows.add(new ArrayList<>());
                    rowY = y;
                }
                rows.get(rows.size() - 1).add(band(drawn(v, st.params(), values(s)), b, tokens));
            }
            laid.add(rows);
        }
        StatementLayout layout = new StatementLayout(spec, limits.maxPages());
        StatementPages out = layout.lay(laid, tokens);
        cut.addAll(layout.cut());                                         // a figure that could not be drawn whole (QA3B-01)
        List<String> notes = new ArrayList<>();
        notes.add("Statement for " + who.user() + ", run " + tokens.get("run-date") + " " + tokens.get("run-time"));
        if (!"none".equals(spec.period())) {
            notes.add("Period: " + period.label() + " (" + period.from() + " to " + period.to() + ")");
        }
        notes.addAll(BiExporter.filters(report, s));
        notes.addAll(cut);
        String hint = asOf == null && empty(answersOf) ? hint(st, spec, period, s, day) : null;
        if (hint != null) {
            notes.add(0, hint);
        }
        String sub = (!"none".equals(spec.period()) ? period.label() + " · " : "") + "Data as of " + (asOf == null ? day.toString() : asOf) + " · " + pages(out.pages().size());
        ExportDoc doc = new ExportDoc(st.title(), sub, notes, sections);
        return new Run(st.title(), spec, out, doc, tokens, "none".equals(spec.period()) ? "" : period.label(), cut, hint);
    }

    /**
     * What a run that found no data says (QA2-12, QA3B-02): the dates its bands actually read (the period's last day, every day of it, or the date asked), never
     * the whole period when only its last day was read, and the nearest dates before and after them that hold data, when the sources can say.
     */
    private String hint(BiReportService.Statement st, StatementSpec spec, StatementSpec.Period period, JsonNode state, LocalDate day) {
        LocalDate from = dateOr(state.path("dates").path("from"), day);
        LocalDate to = dateOr(state.path("dates").path("to"), from);
        StringBuilder b = new StringBuilder("No data is held for ");
        b.append(from.equals(to) ? to.toString() : from + " to " + to);
        if (!"none".equals(spec.period())) {
            b.append(from.equals(to) ? ", the date" : ", the dates").append(" this ").append(period.label()).append(" statement reads");
        }
        b.append(": every band is empty.");
        DatasetService.HeldDates held;
        try {
            held = reports.heldDates(st);
        } catch (RuntimeException e) {
            held = new DatasetService.HeldDates(new java.util.TreeSet<>(), false);
        }
        java.util.NavigableSet<LocalDate> dates = held.dates();
        if (!dates.isEmpty() && held.every()) {
            LocalDate before = dates.lower(from);
            LocalDate after = dates.higher(to);
            List<String> near = new ArrayList<>();
            if (before != null) {
                near.add(before + " (before)");
            }
            if (after != null) {
                near.add(after + " (after)");
            }
            if (!near.isEmpty()) {
                b.append(near.size() == 1 ? " The nearest date with data is " : " The nearest dates with data are ").append(String.join(" and ", near)).append('.');
            }
        } else if (!dates.isEmpty()) {
            b.append(dates.size() == 1 ? " Data is held for " + dates.first() + "." : " The data held runs from " + dates.first() + " to " + dates.last() + ".");
        }
        return b.append(" Choose a date the data covers.").toString();
    }

    private static LocalDate dateOr(JsonNode n, LocalDate dflt) {
        try {
            return n.isTextual() ? LocalDate.parse(n.asText()) : dflt;
        } catch (java.time.format.DateTimeParseException e) {
            return dflt;
        }
    }

    /** True when no band that reads data has a value: no row, or only rows with nothing in them but a zero count (QA2-12). */
    private static boolean empty(List<List<BiExporter.Built>> answers) {
        for (List<BiExporter.Built> page : answers) {
            for (BiExporter.Built b : page) {
                if (b == null) {
                    continue;
                }
                for (List<Object> row : b.section().rows()) {
                    if (row.stream().anyMatch(v -> v != null && !"–".equals(v) && !(v instanceof Number n && n.doubleValue() == 0))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** {@code 1 page}, {@code 2 pages}: the count with its noun, singular for one (the subject of a mail, the audit, QA3B-09). */
    public static String pages(int n) {
        return n + (n == 1 ? " page" : " pages");
    }

    /** The statement as a PDF file. */
    public byte[] pdf(Run r) {
        return StatementPdf.write(r.pages(), r.title());
    }

    /** The statement's tables as an Excel workbook that prints on the statement's paper, with its header and footer and the column titles on every page. */
    public byte[] xlsx(Run r) {
        StatementSpec s = r.spec();
        double[] mm = s.margins();
        double[] inches = {mm[0] / 25.4, mm[1] / 25.4, mm[2] / 25.4, mm[3] / 25.4};
        return XlsxWriter.write(r.doc(), new XlsxWriter.PrintSetup(s.landscape(), EXCEL_PAPER.getOrDefault(s.paper(), 9), inches, excel(s.header(), r.tokens()),
                excel(s.footer(), r.tokens())));
    }

    /** The pages for the BI Studio's preview (what the PDF draws), with the run's period and tokens. */
    public Map<String, Object> preview(Principal who, String reportId, String yaml, String page, JsonNode state, String date) {
        Run r = run(who, reportId, yaml, page, state, date);
        Map<String, Object> m = new LinkedHashMap<>(r.pages().toMap());
        m.put("title", r.title());
        m.put("period", r.period());
        m.put("paper", Map.of("size", r.spec().paper(), "orientation", r.spec().landscape() ? "landscape" : "portrait", "margins", r.spec().margins()));
        m.put("tokens", r.tokens());
        m.put("rows", r.doc().rowCount());
        m.put("cut", r.cut());                                         // the tables that stop at max-rows, in words (QA2-01)
        m.put("hint", r.hint());                                       // no data is held for the run's dates, in words (QA2-12); null when there is
        return m;
    }

    /** The file name of a reader's statement: its title, period and the reader ({@code Monthly statement September 2026 rita.pdf}). */
    public static String filename(Run r, Principal who, ContentExporter.Format f) {
        return ContentExporter.filename(r.title() + (r.period().isBlank() ? "" : " " + r.period()) + " " + who.user(), null, f);
    }

    // ---- the run -------------------------------------------------------------------------------------------------

    private static List<Report.Page> pagesOf(Report report, String page) {
        if (page != null && !page.isBlank()) {
            Report.Page p = report.page(page);
            if (p == null) {
                throw new DrishtiException(ErrorCode.STATEMENT_INVALID, "the report has no page '" + page + "'");
            }
            if (!p.statement()) {
                throw new DrishtiException(ErrorCode.STATEMENT_INVALID, "page '" + page + "' is not a statement page (layout: statement): export it as a picture instead");
            }
            return List.of(p);
        }
        List<Report.Page> all = report.pages().stream().filter(Report.Page::statement).toList();
        if (all.isEmpty()) {
            throw new DrishtiException(ErrorCode.STATEMENT_INVALID, "the report has no statement page: give a page layout: statement");
        }
        return all;
    }

    private LocalDate dayOf(String date, JsonNode state) {
        try {
            if (date != null && !date.isBlank()) {
                return LocalDate.parse(date);
            }
            if (state.path("dates").path("to").isTextual()) {
                return LocalDate.parse(state.path("dates").path("to").asText());
            }
        } catch (java.time.format.DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.STATEMENT_INVALID, "a statement's date is yyyy-mm-dd, not '" + date + "'");
        }
        return businessDate.get();
    }

    private Map<String, String> tokens(BiReportService.Statement st, Principal who, JsonNode state, StatementSpec.Period period, StatementSpec spec, LocalDate day) {
        ZonedDateTime now = clock.instant().atZone(zone);
        Map<String, String> t = new LinkedHashMap<>();
        t.put("title", st.title());
        t.put("run-date", now.toLocalDate().toString());
        t.put("run-time", TIME.format(now));
        t.put("reader", who.user());
        t.put("period", "none".equals(spec.period()) ? day.toString() : period.label());
        t.put("from", period.from().toString());
        t.put("to", period.to().toString());
        for (Map.Entry<String, ModelDef.Parameter> e : st.params().entrySet()) {
            JsonNode v = state.path("params").path(e.getKey());
            ModelDef.Parameter p = e.getValue();
            Object dflt = p.defaultValue() != null || p.fields().isEmpty() ? p.defaultValue() : p.fields().get(0);
            Object value = v.isMissingNode() || v.isNull() ? dflt : v.isNumber() ? v.numberValue() : v.asText();
            t.put("param." + e.getKey(), value == null ? "" : label(String.valueOf(value)));
        }
        state.path("params").fields().forEachRemaining(e -> t.putIfAbsent("param." + e.getKey(), label(e.getValue().asText())));
        return t;
    }

    /** {@code trade.DESK} reads as {@code DESK} in a header. */
    private static String label(String v) {
        int dot = v.indexOf('.');
        return dot > 0 && dot < v.length() - 1 && v.indexOf(' ') < 0 ? v.substring(dot + 1) : v;
    }

    /** Each band that holds data asked as the reader, {@code bandQueries} at a time, in order; null for a text band. */
    private List<BiExporter.Built> ask(Principal who, String reportId, String yaml, Report.Page page, List<Report.Visual> visuals, JsonNode state) {
        Semaphore room = new Semaphore(limits.bandQueries());
        List<Future<BiExporter.Built>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Report.Visual v : visuals) {
                if ("text".equals(v.type())) {
                    futures.add(null);
                    continue;
                }
                futures.add(pool.submit(() -> {
                    room.acquire();
                    try {
                        return exporter.section(who, reportId, yaml, page, v, state, v.title() == null || v.title().isBlank() ? v.id() : v.title(),
                                StatementSpec.FLOWING.contains(v.type()) ? limits.maxRows() : null);     // a table is read whole, never the screen's first rows (QA2-01)
                    } finally {
                        room.release();
                    }
                }));
            }
            List<BiExporter.Built> out = new ArrayList<>();
            for (Future<BiExporter.Built> f : futures) {
                out.add(f == null ? null : f.get());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DrishtiException(ErrorCode.EXPORT_FAILED, "the statement was interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new DrishtiException(ErrorCode.EXPORT_FAILED, "a band of the statement failed: " + e.getCause(), e.getCause());
        }
    }

    // ---- bands ---------------------------------------------------------------------------------------------------

    /** The run's parameter values ({@code state.params}). */
    private static Map<String, Object> values(JsonNode state) {
        Map<String, Object> out = new LinkedHashMap<>();
        state.path("params").fields().forEachRemaining(e -> out.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class)));
        return out;
    }

    /** A band as it is drawn for the run: a field parameter in its wells, its sort or its {@code groupBy} is the field (or measure) the run chose. */
    static Report.Visual drawn(Report.Visual v, Map<String, ModelDef.Parameter> params, Map<String, Object> values) {
        if (params.isEmpty()) {
            return v;
        }
        Report.Visual r = FieldParams.resolve(v, params, values);
        Object g = r.format().get("groupBy");
        if (g == null || !FieldParams.isParam(String.valueOf(g))) {
            return r;
        }
        Map<String, Object> format = new LinkedHashMap<>(r.format());
        format.put("groupBy", FieldParams.choice(String.valueOf(g), params, values));
        return new Report.Visual(r.id(), r.type(), r.title(), r.at(), r.wells(), r.filters(), r.sort(), r.limit(), r.hierarchy(), r.drillThrough(), format, r.text(), r.src(),
                r.alt(), r.interacts(), r.action(), r.dataset(), r.tooltip(), r.from(), r.alert());
    }

    /**
     * A table band's answer cut at {@code max-rows} (it was asked for one row more, so a longer table is known): the rows kept, a note above the table that
     * says so, and the same words in {@code cut} for the preview and the Excel file. Other bands are as they came.
     */
    private BiExporter.Built trimmed(Report.Visual v, BiExporter.Built b, List<String> cut) {
        if (b == null || !StatementSpec.FLOWING.contains(v.type()) || b.section().rows().size() <= limits.maxRows()) {
            return b;
        }
        ExportDoc.Section s = b.section();
        String says = "The first " + String.format(Locale.ROOT, "%,d", limits.maxRows()) + " rows only: the table goes on (drishti.distribution.statements.max-rows).";
        cut.add((s.title() == null || s.title().isBlank() ? v.id() : s.title()) + ": " + says);
        ExportDoc.Section kept = new ExportDoc.Section(s.id(), s.title(), s.type(), s.columns(), s.rows().subList(0, limits.maxRows()),
                s.note() == null ? says : says + " " + s.note(), s.masked(), s.kind());
        return new BiExporter.Built(kept, b.asOf());
    }

    /** The measures of the statement's datasets that count ({@code count(...)}, {@code distinct(...)}): a count of nothing reads 0, never a dash (QA3B-08). */
    static java.util.Set<String> counts(BiReportService.Statement st) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (com.ash.drishti.server.bi.dataset.Dataset d : st.datasets()) {
            d.measures().forEach((name, m) -> {
                if (isCount(m.expr())) {
                    out.add(BiExporter.shortName(name));
                }
            });
        }
        return out;
    }

    /** True for an expression that is one count: {@code count(trade)}, {@code distinct(trade.BOOK)}, the call spanning the whole expression. */
    static boolean isCount(String expr) {
        if (expr == null) {
            return false;
        }
        String e = expr.strip();
        String lower = e.toLowerCase(Locale.ROOT);
        String fn = lower.startsWith("count") ? "count" : lower.startsWith("distinct") ? "distinct" : null;
        if (fn == null || !e.endsWith(")")) {
            return false;
        }
        String rest = e.substring(fn.length()).stripLeading();
        if (!rest.startsWith("(")) {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i == rest.length() - 1;                                 // the first call closes at the very end: nothing is added to it
            }
        }
        return false;
    }

    /**
     * A card's answer with its empty value made one rule for the preview, the PDF and Excel (QA3B-08): a count of nothing is 0, any other value of nothing (a
     * sum of no rows) is a dash; whether no row came back, one empty row, or the date was refused as later than the business date (DRS-9020: no data is held
     * for it, so nothing is counted). A masked value stays the mask; a card that failed for another reason keeps its "Not available" note and its dash.
     */
    static BiExporter.Built carded(Report.Visual v, BiExporter.Built b, java.util.Set<String> counts) {
        if (b != null && "card".equals(v.type()) && b.section().columns().isEmpty() && b.section().note() != null && b.section().note().contains("DRS-9020")
                && !v.well("values").isEmpty()) {
            String measure = BiExporter.shortName(v.well("values").get(0));
            List<Object> row = new ArrayList<>(List.of(counts.contains(measure) ? (Object) 0L : "–"));
            ExportDoc.Section s = b.section();
            return new BiExporter.Built(new ExportDoc.Section(s.id(), s.title(), ExportDoc.CARD, List.of(measure), List.of(row), s.note(), List.of(), v.type()), b.asOf());
        }
        if (b == null || !ExportDoc.CARD.equals(b.section().type()) || b.section().columns().isEmpty()) {
            return b;
        }
        ExportDoc.Section s = b.section();
        String measure = v.well("values").isEmpty() ? s.columns().get(0) : BiExporter.shortName(v.well("values").get(0));
        int mi = Math.max(0, s.columns().indexOf(measure));
        List<Object> first = s.rows().isEmpty() ? null : s.rows().get(0);
        Object value = first == null || mi >= first.size() ? null : first.get(mi);
        if ((value != null && !"".equals(value)) || s.masked().contains(s.columns().get(mi))) {
            return b;
        }
        List<Object> row = new ArrayList<>(java.util.Collections.nCopies(s.columns().size(), null));
        if (first != null) {
            for (int c = 0; c < Math.min(first.size(), row.size()); c++) {
                row.set(c, first.get(c));
            }
        }
        row.set(mi, counts.contains(s.columns().get(mi)) || counts.contains(measure) ? (Object) 0L : "–");
        return new BiExporter.Built(new ExportDoc.Section(s.id(), s.title(), s.type(), s.columns(), List.of(row), s.note(), s.masked(), s.kind()), b.asOf());
    }

    private StatementLayout.Band band(Report.Visual v, BiExporter.Built b, Map<String, String> tokens) {
        double x = v.at() == null ? 0 : v.at().x() / (double) Report.COLUMNS;
        double w = v.at() == null ? 1 : Math.min(1 - x, v.at().w() / (double) Report.COLUMNS);
        String brk = String.valueOf(v.format().getOrDefault("pageBreak", "none"));
        boolean keep = Boolean.TRUE.equals(v.format().get("keepTogether"));
        boolean repeat = !Boolean.FALSE.equals(v.format().get("repeatHeader"));
        String title = StatementSpec.fill(v.title(), tokens);
        if ("text".equals(v.type())) {
            return new StatementLayout.Band("text", v.id(), title, StatementSpec.fill(v.text(), tokens), "heading".equals(v.format().get("size")), null, null, null, x, w, brk, keep,
                    repeat);
        }
        ExportDoc.Section s = b.section();
        String note = s.note();
        if (StatementSpec.FLOWING.contains(v.type())) {
            StatementLayout.Table t = "matrix".equals(v.type()) ? pivot(v, s) : table(s, v);
            return new StatementLayout.Band("table", v.id(), title, null, false, null, note, t, 0, 1, brk, keep, repeat);
        }
        String measure = v.well("values").isEmpty() ? (s.columns().isEmpty() ? "" : s.columns().get(0)) : BiExporter.shortName(v.well("values").get(0));
        int mi = Math.max(0, s.columns().indexOf(measure));
        String value = s.rows().isEmpty() ? "–" : text(s.shown(s.rows().get(0), mi));
        if (value.isBlank()) {
            value = "–";                                                  // no value (a sum of no rows): a dash, as a masked card and a card with no rows (QA2-14)
        }
        return new StatementLayout.Band("card", v.id(), title.isBlank() ? measure : title, null, false, value, note, null, x, w, brk, keep, repeat);
    }

    /** A table section as the layout's table: cells as text (masked cells as the mask), number columns to the right, the {@code groupBy} column. */
    private static StatementLayout.Table table(ExportDoc.Section s, Report.Visual v) {
        List<List<String>> rows = new ArrayList<>(s.rows().size());
        for (List<Object> r : s.rows()) {
            List<String> cells = new ArrayList<>(s.columns().size());
            for (int c = 0; c < s.columns().size(); c++) {
                cells.add(text(s.shown(r, c)));
            }
            rows.add(cells);
        }
        boolean[] numeric = new boolean[s.columns().size()];
        for (int c = 0; c < numeric.length; c++) {
            numeric[c] = s.isNumeric(c);
        }
        int group = groupColumn(s.columns(), v);
        return new StatementLayout.Table(s.columns(), grouped(rows, group), numeric, group);
    }

    /** The rows with each group's rows together, the groups in the order they first appear and the rows in their own order inside each (a stable grouping). */
    static List<List<String>> grouped(List<List<String>> rows, int column) {
        if (column < 0) {
            return rows;
        }
        Map<String, List<List<String>>> groups = new LinkedHashMap<>();
        for (List<String> r : rows) {
            groups.computeIfAbsent(column < r.size() ? r.get(column) : "", k -> new ArrayList<>()).add(r);
        }
        List<List<String>> out = new ArrayList<>(rows.size());
        groups.values().forEach(out::addAll);
        return out;
    }

    private static int groupColumn(List<String> columns, Report.Visual v) {
        Object g = v.format().get("groupBy");
        if (g == null) {
            return -1;
        }
        String name = BiExporter.shortName(String.valueOf(g));
        return columns.indexOf(name);
    }

    /**
     * A matrix's answer (its rows fields, its column field, its measures, one row per combination) turned wide: a row per combination of the rows fields and a
     * column per value of the column field (and measure), in the order the answer gives them.
     */
    static StatementLayout.Table pivot(Report.Visual v, ExportDoc.Section s) {
        int nr = v.well("rows").size();
        int nc = v.well("columns").isEmpty() ? 0 : 1;
        int nm = Math.max(0, s.columns().size() - nr - nc);
        if (nc == 0 || nm == 0 || s.columns().size() < nr + nc + nm) {
            return table(s, v);
        }
        List<String> colValues = new ArrayList<>();
        Map<List<String>, Map<String, List<String>>> byRow = new LinkedHashMap<>();
        for (List<Object> r : s.rows()) {
            List<String> key = new ArrayList<>();
            for (int i = 0; i < nr; i++) {
                key.add(text(s.shown(r, i)));
            }
            String col = text(s.shown(r, nr));
            if (!colValues.contains(col)) {
                colValues.add(col);
            }
            List<String> vals = new ArrayList<>();
            for (int m = 0; m < nm; m++) {
                vals.add(text(s.shown(r, nr + nc + m)));
            }
            byRow.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(col, vals);
        }
        List<String> headers = new ArrayList<>(s.columns().subList(0, nr));
        for (String c : colValues) {
            for (int m = 0; m < nm; m++) {
                headers.add(nm == 1 ? c : c + " " + s.columns().get(nr + nc + m));
            }
        }
        List<List<String>> rows = new ArrayList<>();
        byRow.forEach((key, cols) -> {
            List<String> row = new ArrayList<>(key);
            for (String c : colValues) {
                List<String> vals = cols.get(c);
                for (int m = 0; m < nm; m++) {
                    row.add(vals == null ? "" : vals.get(m));
                }
            }
            rows.add(row);
        });
        boolean[] numeric = new boolean[headers.size()];
        for (int c = nr; c < numeric.length; c++) {
            numeric[c] = true;
        }
        int group = groupColumn(headers, v);
        return new StatementLayout.Table(headers, grouped(rows, group), numeric, group);
    }

    /** A cell as the paper shows it: whole numbers with thousands separators, other numbers with two decimals, dates as yyyy-mm-dd. */
    static String text(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Long || v instanceof Integer) {
            return String.format(Locale.ROOT, "%,d", ((Number) v).longValue());
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? "" : String.format(Locale.ROOT, "%,.2f", d);
        }
        return String.valueOf(v);
    }

    /** A header or footer line in Excel's codes ({@code &L}, {@code &C}, {@code &R}; {@code &P}, {@code &N}, {@code &D} for the page, pages and date). */
    static String excel(Map<String, String> line, Map<String, String> tokens) {
        if (line.isEmpty()) {
            return "";
        }
        Map<String, String> t = new LinkedHashMap<>();
        tokens.forEach((k, v) -> t.put(k, v.replace("&", "&&")));
        t.put("page", "&P");
        t.put("pages", "&N");
        t.put("run-date", "&D");
        StringBuilder b = new StringBuilder();
        for (String[] part : new String[][] {{"left", "&L"}, {"center", "&C"}, {"right", "&R"}}) {
            String text = line.get(part[0]);
            if (text != null && !text.isBlank()) {
                b.append(part[1]).append(StatementSpec.fill(text.replace("&", "&&"), t));
            }
        }
        return b.toString();
    }
}
