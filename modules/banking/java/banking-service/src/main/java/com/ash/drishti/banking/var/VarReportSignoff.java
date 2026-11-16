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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.dist.CsvWriter;
import com.ash.drishti.server.dist.DocLayout;
import com.ash.drishti.server.dist.ExportDoc;
import com.ash.drishti.server.dist.PdfWriter;
import com.ash.drishti.server.dist.XlsxWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The maker-checker sign-off of a frozen report run, and its files (VAR_GUIDE.md section 18.14; REGULATORY_VAR_REPORTING.md section
 * 6.4). The record ({@code var/reports/<report>/<period>/signoff-v<n>.json}, every version kept by the content store) holds the chain's
 * steps in the definition's order, each approval (who, when, the comment), every send-back, and the narrative the maker wrote. Rules: the
 * steps go in order; one person signs one step of a run (four eyes); a failed check blocks every approval; a check that needs a note needs
 * the maker's comment; the narrative is the maker's, edited before the maker signs; a send-back returns the run to the maker. The files
 * (xlsx, csv, PDF) are released after the last approval; before it a draft, marked so, may be downloaded. Submission stays with the bank.
 */
final class VarReportSignoff {

    private final VarReports reports;

    VarReportSignoff(VarReports reports) {
        this.reports = reports;
    }

    static String key(String report, String period, long version) {
        return VarReports.folder(report, period) + "signoff-v" + version + ".json";
    }

    /** The sign-off record of a run (a fresh one, with the maker, when none was written yet). */
    @SuppressWarnings("unchecked")
    Map<String, Object> record(Map<String, Object> run) {
        long v = ((Number) run.get("version")).longValue();
        String report = String.valueOf(run.get("report"));
        String period = String.valueOf(run.get("period"));
        return reports.docs().value(key(report, period, v), Map.class).map(x -> (Map<String, Object>) x).orElseGet(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("report", report);
            m.put("period", period);
            m.put("version", v);
            m.put("steps", new ArrayList<>());
            m.put("history", new ArrayList<>());
            m.put("narrative", ((Map<?, ?>) run.getOrDefault("facts", Map.of())).get("text"));
            m.put("state", state(run, List.of()));
            m.put("next", ((List<?>) run.get("signOff")).get(0));
            return m;
        });
    }

    private static String state(Map<String, Object> run, List<?> steps) {
        List<?> chain = (List<?>) run.get("signOff");
        return steps.size() >= chain.size() ? "signed" : "with " + chain.get(steps.size());
    }

    /** A run with its sign-off record beside it (the review screen's answer). */
    Map<String, Object> view(Map<String, Object> run) {
        Map<String, Object> out = new LinkedHashMap<>(run);
        out.put("signoff", record(run));
        out.put("versions", reports.versions(String.valueOf(run.get("report")), String.valueOf(run.get("period"))));
        return out;
    }

    /**
     * One sign-off action on a run: {@code approve} the next step, {@code return} it to the maker, or {@code narrative} (the maker's text,
     * before the maker signs). Refused in words when the rules say no.
     */
    @SuppressWarnings("unchecked")
    synchronized Map<String, Object> act(Map<String, Object> run, String who, String action, String step, String comment, String narrative) {
        Map<String, Object> rec = new LinkedHashMap<>(record(run));
        List<Map<String, Object>> steps = new ArrayList<>((List<Map<String, Object>>) rec.get("steps"));
        List<Map<String, Object>> history = new ArrayList<>((List<Map<String, Object>>) rec.get("history"));
        List<String> chain = (List<String>) run.get("signOff");
        String next = steps.size() < chain.size() ? chain.get(steps.size()) : null;
        String now = Instant.now().toString();
        switch (action == null ? "" : action) {
            case "narrative" -> {
                if (!"maker".equals(next)) {
                    throw refused("the narrative is the maker's, written before the maker signs: the run is " + rec.get("state"));
                }
                rec.put("narrative", narrative == null ? "" : narrative.strip());
                history.add(event(who, now, "narrative", "maker", null));
            }
            case "approve" -> {
                if (next == null) {
                    throw refused("the run is signed already: a changed run is a new run, with its own sign-off");
                }
                if (step != null && !step.isBlank() && !step.equals(next)) {
                    throw refused("the next step is " + next + ", not " + step);
                }
                for (Map<String, Object> s : steps) {
                    if (who.equals(s.get("who"))) {
                        throw refused(who + " signed this run as " + s.get("step") + ": one person signs one step (four eyes)");
                    }
                }
                for (Map<String, Object> c : (List<Map<String, Object>>) run.getOrDefault("checks", List.of())) {
                    if ("fail".equals(c.get("status"))) {
                        throw refused("the check '" + c.get("title") + "' failed (" + c.get("evidence") + "): a failed check blocks sign-off; "
                                + "fix the data and generate the run again");
                    }
                    if ("note".equals(c.get("status")) && "maker".equals(next) && (comment == null || comment.isBlank())) {
                        throw refused("the check '" + c.get("title") + "' needs a note: write it as the maker's comment");
                    }
                }
                Map<String, Object> s = event(who, now, "approve", next, comment);
                steps.add(s);
                history.add(s);
            }
            case "return" -> {
                if (next == null || "maker".equals(next)) {
                    throw refused(next == null ? "the run is signed: nothing to send back" : "the run is with the maker already");
                }
                if (comment == null || comment.isBlank()) {
                    throw refused("say why the run goes back to the maker (a comment)");
                }
                history.add(event(who, now, "return", next, comment));
                steps.clear();
            }
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "the action is approve, return or narrative, not " + action);
        }
        rec.put("steps", steps);
        rec.put("history", history);
        rec.put("state", state(run, steps));
        rec.put("next", steps.size() < chain.size() ? chain.get(steps.size()) : null);
        if (steps.size() >= chain.size()) {
            rec.put("signedAt", now);
        }
        reports.docs().putLatest(key(String.valueOf(run.get("report")), String.valueOf(run.get("period")), ((Number) run.get("version")).longValue()), rec);
        return rec;
    }

    private static Map<String, Object> event(String who, String when, String action, String step, String comment) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", step);
        m.put("who", who);
        m.put("when", when);
        m.put("action", action);
        m.put("comment", comment == null ? "" : comment.strip());
        return m;
    }

    private static DrishtiException refused(String why) {
        return new DrishtiException(ErrorCode.CONTENT_CONFLICT, why);
    }

    // ------------------------------------------------------------------------------------------------------------- files

    /** The file of a run: released after the last approval; a draft before it (marked so) when asked. */
    byte[] file(Map<String, Object> run, String format, boolean draft) {
        Map<String, Object> rec = record(run);
        boolean signed = "signed".equals(rec.get("state"));
        if (!signed && !draft) {
            throw refused("the files are released after the last approval (the run is " + rec.get("state") + "); ask for a draft to preview it");
        }
        if (!((List<?>) run.get("outputs")).contains(format)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the report's outputs are " + run.get("outputs") + ", not " + format);
        }
        ExportDoc doc = doc(run, rec, !signed);
        return switch (format) {
            case "xlsx" -> XlsxWriter.write(doc);
            case "csv" -> CsvWriter.write(doc);
            case "pdf" -> {
                PdfWriter.Geometry g = PdfWriter.Geometry.of(true, 1400);
                yield PdfWriter.write(new DocLayout(g.width(), g.slice(), 500, 300, 0).build(doc), g, doc.title(), doc.subtitle(),
                        "Run v" + run.get("version") + " frozen " + run.get("frozenAt"));
            }
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "a file is xlsx, csv or pdf, not " + format);
        };
    }

    static String filename(Map<String, Object> run, String format, boolean draft) {
        return run.get("report") + "_" + run.get("period") + "_v" + run.get("version") + (draft ? "_DRAFT" : "") + "." + format;
    }

    @SuppressWarnings("unchecked")
    static ExportDoc doc(Map<String, Object> run, Map<String, Object> rec, boolean draft) {
        boolean thousands = "thousands".equals(run.get("units"));
        String title = (draft ? "DRAFT, not signed off: " : "") + run.get("title") + " · " + run.get("period");
        List<String> notes = new ArrayList<>();
        notes.add("Read from the official VaR records, never recomputed. Run v" + run.get("version") + ", frozen " + run.get("frozenAt") + " by "
                + run.get("frozenBy") + "; definition " + ((Map<?, ?>) run.get("definition")).get("file") + ".");
        if (run.get("previewNote") != null) {
            notes.add("Preview: " + run.get("previewNote"));
        }
        if (Number.class.isInstance(run.get("synthetic")) && ((Number) run.get("synthetic")).longValue() > 0) {
            notes.add(run.get("synthetic") + " of the records are synthetic demo history.");
        }
        if (rec.get("narrative") != null && !String.valueOf(rec.get("narrative")).isBlank()) {
            notes.add(String.valueOf(rec.get("narrative")));
        }
        for (Map<String, Object> s : (List<Map<String, Object>>) rec.getOrDefault("steps", List.of())) {
            notes.add("Signed as " + s.get("step") + " by " + s.get("who") + " on " + s.get("when") + (String.valueOf(s.get("comment")).isBlank() ? ""
                    : ": " + s.get("comment")));
        }
        List<ExportDoc.Section> sections = new ArrayList<>();
        if ("backtesting-chart".equals(run.get("type"))) {
            Map<String, Object> bt = (Map<String, Object>) run.get("backtest");
            List<List<Object>> rows = new ArrayList<>();
            for (Map<String, Object> r : (List<Map<String, Object>>) bt.getOrDefault("rows", List.of())) {
                List<Object> row = new ArrayList<>();
                row.add(LocalDate.parse(String.valueOf(r.get("date"))));
                row.add(r.get("actual"));
                row.add(r.get("hypothetical"));
                row.add(r.get("var990") instanceof Number n ? -n.longValue() : null);
                row.add(r.get("var975") instanceof Number n ? -n.longValue() : null);
                row.add(Boolean.TRUE.equals(r.get("exceptionActual")) ? "actual" : Boolean.TRUE.equals(r.get("exceptionHypothetical")) ? "hypothetical" : "");
                rows.add(row);
            }
            List<String> cols = List.of("Date", "Actual P&L", "Hypothetical P&L", "-VaR 99% (day before)", "-VaR 97.5% (day before)", "Exception");
            sections.add(new ExportDoc.Section("chart", "VaR against actual and hypothetical P&L, " + run.get("node"), ExportDoc.CHART, cols, rows,
                    null, List.of(), "line"));
            List<List<Object>> zone = new ArrayList<>();
            for (Map<String, Object> c : (List<Map<String, Object>>) bt.getOrDefault("counts", List.of())) {
                zone.add(List.of(String.valueOf(c.get("confidence")), String.valueOf(c.get("actual")), String.valueOf(c.get("hypothetical")),
                        String.valueOf(c.get("expected"))));
            }
            sections.add(new ExportDoc.Section("zone", "Zone " + bt.get("zone") + ", multiplier " + bt.get("multiplier") + " (" + bt.get("days") + " days)",
                    ExportDoc.TABLE, List.of("Confidence", "Actual exceptions", "Hypothetical exceptions", "Expected"), zone, String.valueOf(bt.get("rule")),
                    List.of()));
        } else {
            List<String> columns = (List<String>) run.get("columns");
            List<String> cols = new ArrayList<>();
            cols.add("Risk type" + (thousands ? " (USD thousands)" : " (USD)"));
            for (String c : columns) {
                cols.add(header(c, run));
            }
            List<List<Object>> rows = new ArrayList<>();
            for (Map<String, Object> r : (List<Map<String, Object>>) run.get("rows")) {
                List<Object> row = new ArrayList<>();
                row.add(r.get("label") + (r.get("code") == null ? "" : " (" + r.get("code") + ")"));
                Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) r.get("cells");
                for (String c : columns) {
                    Map<String, Object> cell = cells.get(c);
                    Object v = cell == null ? null : cell.get("value");
                    if (v instanceof Number n) {
                        row.add(c.equals("movement") ? (Object) (Math.round(n.doubleValue() * 1000) / 10.0) : (Object) Math.round(thousands ? n.doubleValue() / 1000 : n.doubleValue()));
                    } else {
                        row.add(cell == null ? "" : cell.getOrDefault("text", ""));
                    }
                }
                rows.add(row);
            }
            sections.add(new ExportDoc.Section("table", String.valueOf(run.get("title")), ExportDoc.TABLE, cols, rows,
                    String.join(" ", (List<String>) run.getOrDefault("footnotes", List.of())), List.of()));
        }
        List<List<Object>> checks = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) run.getOrDefault("checks", List.of())) {
            checks.add(List.of(String.valueOf(c.get("status")), String.valueOf(c.get("title")), String.valueOf(c.get("evidence"))));
        }
        sections.add(new ExportDoc.Section("checks", "Checks", ExportDoc.TABLE, List.of("Status", "Check", "Evidence"), checks, null, List.of()));
        return new ExportDoc(title, "Period " + run.get("from") + " to " + run.get("to") + " · run v" + run.get("version"), notes, sections);
    }

    private static String header(String c, Map<String, Object> run) {
        return switch (c) {
            case "period-end" -> "Period end";
            case "average" -> "Average";
            case "high" -> "High";
            case "low" -> "Low";
            case "prior-period-end" -> run.get("priorPeriod") + " period end";
            case "movement" -> "Movement %";
            default -> c;
        };
    }
}
