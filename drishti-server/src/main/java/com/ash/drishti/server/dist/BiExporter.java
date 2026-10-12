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
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tables of a BI report, a page or one visual, as one reader sees them: each visual is asked of the report service as that reader (their
 * row rules and masks are applied by the dataset engine, as for the screen) under the view state (slicers, selections, drill path, dates)
 * and written into an {@link ExportDoc}. A visual that cannot be read does not fail the export; its section says why.
 */
public final class BiExporter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> NOT_DATA = Set.of("slicer", "text", "image", "button", "entity-view");
    private static final Set<String> BARS = Set.of("bar", "column", "line", "area", "pie", "donut", "waterfall", "treemap", "funnel", "histogram");
    private static final Set<String> CARDS = Set.of("card", "multirow-card", "kpi", "gauge");

    private final BiReportService reports;
    private final DistProperties.Export limits;

    public BiExporter(BiReportService reports, DistProperties.Export limits) {
        this.reports = reports;
        this.limits = limits;
    }

    /** Throws unless {@code who} may read the report. */
    public void check(Principal who, String reportId) {
        reports.open(who, reportId);
    }

    /**
     * @param page the page id, or blank for every page
     * @param visual the visual id, or blank for every visual of the page(s)
     * @param state the report view state ({@code slicers}, {@code selections}, {@code drill}, {@code carry}, {@code dates}); may be null
     * @param date a business date (yyyy-mm-dd) every visual reads, or blank for the report's own dates
     */
    public ExportDoc build(Principal who, String reportId, String page, String visual, JsonNode state, String date) {
        BiReportService.Opened opened = reports.open(who, reportId);
        Report report = opened.report();
        List<Report.Page> pages = new ArrayList<>();
        if (page == null || page.isBlank()) {
            pages.addAll(report.pages());
        } else if (report.page(page) != null) {
            pages.add(report.page(page));
        } else {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the report has no page '" + page + "'");
        }
        ObjectNode st = state != null && state.isObject() ? ((ObjectNode) state).deepCopy() : JSON.createObjectNode();
        if (date != null && !date.isBlank()) {
            st.set("dates", JSON.createObjectNode().put("from", date).put("to", date));
        }
        if (!st.has("limit")) {
            st.put("limit", limits.maxRows() + 1);
        }
        List<String> notes = new ArrayList<>(filters(report, st));
        List<ExportDoc.Section> sections = new ArrayList<>();
        String asOf = null;
        String one = null;
        for (Report.Page p : pages) {
            for (Report.Visual v : p.visuals()) {
                if (visual != null && !visual.isBlank() && !visual.equals(v.id())) {
                    continue;
                }
                if (NOT_DATA.contains(v.type())) {
                    continue;
                }
                if (sections.size() >= limits.maxVisuals()) {
                    notes.add("Only the first " + limits.maxVisuals() + " visuals are exported (drishti.distribution.export.max-visuals).");
                    break;
                }
                one = v.title();
                String name = (pages.size() > 1 ? p.title() + ": " : "") + (v.title() == null || v.title().isBlank() ? v.id() : v.title());
                Built b = section(who, reportId, p, v, st, name);
                asOf = asOf == null ? b.asOf() : asOf;
                sections.add(b.section());
            }
        }
        if (visual != null && !visual.isBlank() && sections.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the report has no exportable visual '" + visual + "'");
        }
        String title = visual != null && !visual.isBlank() && one != null ? opened.title() + " · " + one : opened.title();
        String sub = (pages.size() == 1 ? "Page: " + pages.get(0).title() + " · " : "") + "Data as of " + (asOf == null ? "the latest date" : asOf);
        return new ExportDoc(title, sub, notes, sections);
    }

    private record Built(ExportDoc.Section section, String asOf) {}

    private Built section(Principal who, String reportId, Report.Page p, Report.Visual v, JsonNode state, String name) {
        boolean countries = "map".equals(v.type()) && !v.well("location").isEmpty();
        String type = CARDS.contains(v.type()) ? ExportDoc.CARD : BARS.contains(v.type()) || countries ? ExportDoc.CHART : ExportDoc.TABLE;
        try {
            Map<String, Object> a = reports.query(who, reportId, null, p.id(), v.id(), state, false);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cols = (List<Map<String, Object>>) a.get("columns");
            @SuppressWarnings("unchecked")
            List<List<Object>> rows = (List<List<Object>>) a.get("rows");
            List<String> names = new ArrayList<>();
            List<String> masked = new ArrayList<>();
            for (Map<String, Object> c : cols) {
                String n = shortName(String.valueOf(c.get("name")));
                names.add(n);
                if (Boolean.TRUE.equals(c.get("masked"))) {
                    masked.add(n);
                }
            }
            String note = null;
            List<List<Object>> kept = rows;
            if (ExportDoc.CARD.equals(type) && rows.size() > 1) {
                kept = new ArrayList<>(rows.subList(0, 1));                  // a card is one value (a masked measure may come back as many empty rows)
            } else if (rows.size() > limits.maxRows()) {
                kept = new ArrayList<>(rows.subList(0, limits.maxRows()));
                note = "Cut at " + limits.maxRows() + " rows (drishti.distribution.export.max-rows).";
            }
            if (!masked.isEmpty()) {
                note = (note == null ? "" : note + " ") + "Masked for you: " + String.join(", ", masked) + ".";
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) a.get("meta");
            Object dates = meta == null ? null : meta.get("dates");
            String asOf = dates instanceof List<?> l && !l.isEmpty() ? String.valueOf(l.get(l.size() - 1)) : null;
            return new Built(new ExportDoc.Section(v.id(), name, type, names, typed(kept), note, masked, v.type()), asOf);
        } catch (DrishtiException e) {
            return new Built(new ExportDoc.Section(v.id(), name, ExportDoc.TABLE, List.of(), List.of(), "Not available: " + e.getMessage(), List.of()), null);
        }
    }

    /** {@code trade.trader} reads as {@code trader}: the table prefix means nothing to a reader of the file. */
    static String shortName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : name;
    }

    /** Numbers stay numbers; an ISO date in a text column becomes a date cell; the rest is text. */
    private static List<List<Object>> typed(List<List<Object>> rows) {
        List<List<Object>> out = new ArrayList<>(rows.size());
        for (List<Object> r : rows) {
            List<Object> row = new ArrayList<>(r.size());
            for (Object v : r) {
                row.add(v instanceof String s && s.length() == 10 && s.charAt(4) == '-' && s.charAt(7) == '-' ? date(s) : v);
            }
            out.add(row);
        }
        return out;
    }

    private static Object date(String s) {
        try {
            return LocalDate.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            return s;
        }
    }

    /** What narrows the numbers, in words: dates, slicers, selections, carried values, the drill path. */
    static List<String> filters(Report report, JsonNode state) {
        List<String> out = new ArrayList<>();
        JsonNode dates = state.path("dates");
        if (dates.path("from").isTextual() || dates.path("to").isTextual()) {
            out.add("Dates: " + dates.path("from").asText("…") + " to " + dates.path("to").asText("…"));
        }
        JsonNode slicers = state.path("slicers");
        if (slicers.isObject()) {
            slicers.fields().forEachRemaining(e -> out.add("Slicer " + e.getKey() + ": " + text(e.getValue())));
        }
        for (JsonNode s : state.path("selections")) {
            out.add("Selected " + s.path("field").asText() + ": " + text(s.path("values")));
        }
        for (JsonNode c : state.path("carry")) {
            out.add("Carried " + c.path("field").asText() + " " + c.path("op").asText("in") + " " + text(c.path("values")));
        }
        if (state.path("drill").isArray() && !state.path("drill").isEmpty()) {
            out.add("Drilled to: " + text(state.path("drill")));
        }
        if (out.isEmpty()) {
            out.add("Filters: none beyond the report's own");
        }
        return out;
    }

    private static String text(JsonNode n) {
        if (n.isArray()) {
            List<String> parts = new ArrayList<>();
            n.forEach(x -> parts.add(x.asText()));
            return String.join(", ", parts);
        }
        if (n.isObject()) {
            return n.toString();
        }
        return n.asText();
    }
}
