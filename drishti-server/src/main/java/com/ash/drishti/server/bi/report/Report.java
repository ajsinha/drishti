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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed report, the model of RUPAKA.md section 8 (immutable): pages of visuals on a 12-column grid, bound to the fields and measures of one
 * dataset, with filters at report, page and visual level, bookmarks, drill-through targets and a theme. Produced by {@link ReportParser}; the YAML
 * reference is docs/guides/REPORTS.md. {@link #toMap()} gives the same structure the YAML has (defaults filled in), which is what the viewer and the
 * designer exchange with the server.
 *
 * @param name the {@code report:} name
 * @param dataset the dataset it reads: a catalogue id, a shipped file's name, or the dataset's own name
 * @param theme the colours the visuals use: {@code palette} (a name the console knows) and optional {@code background}
 * @param dates the business dates the report reads (null: each query reads the latest date)
 * @param layouts per device class ({@code phone}: 640 px and narrower; {@code tablet}: 1024 px and narrower), per page id, how the page is arranged; empty: the automatic
 *        one column on a phone and the grid on a tablet
 * @param tests expected values Check and publishing run ({@link com.ash.drishti.server.bi.dataset.ExpectedValue}); empty when the report declares none
 * @param links which fields of different datasets mean the same thing, so a slicer or a selection on one filters the visuals of the others (RUPAKA.md section 20.10)
 */
public record Report(String name, int version, String title, String description, boolean sample, String dataset, Map<String, String> theme, Dates dates, List<Filter> filters,
        List<Page> pages, List<Bookmark> bookmarks, Map<String, Map<String, PhonePage>> layouts, List<Link> links, List<com.ash.drishti.server.bi.dataset.ExpectedValue> tests) {

    /** Compatibility: a report without tests. */
    public Report(String name, int version, String title, String description, boolean sample, String dataset, Map<String, String> theme, Dates dates, List<Filter> filters,
            List<Page> pages, List<Bookmark> bookmarks, Map<String, Map<String, PhonePage>> layouts, List<Link> links) {
        this(name, version, title, description, sample, dataset, theme, dates, filters, pages, bookmarks, layouts, links, List.of());
    }

    /** Compatibility: a report without links. */
    public Report(String name, int version, String title, String description, boolean sample, String dataset, Map<String, String> theme, Dates dates, List<Filter> filters,
            List<Page> pages, List<Bookmark> bookmarks, Map<String, Map<String, PhonePage>> layouts) {
        this(name, version, title, description, sample, dataset, theme, dates, filters, pages, bookmarks, layouts, List.of());
    }

    /**
     * Fields that mean the same thing in different datasets ({@code [trade.counterparty, exposure.counterparty]}). A choice on a field of one dataset filters the
     * visuals that read another dataset through the linked field; a visual whose dataset has none of them is not filtered by it.
     */
    public record Link(List<String> fields) {}

    /** The grid is always this many columns wide. */
    public static final int COLUMNS = 12;
    /** The device classes a report may arrange a page for: a phone (640 px and narrower) and a tablet (1024 px and narrower). */
    public static final List<String> DEVICES = List.of("phone", "tablet");

    /** Inclusive business dates, yyyy-mm-dd; either may be null. */
    public record Dates(String from, String to) {}

    /**
     * A filter: {@code op} is one of the dataset query's operators, or {@code top} (the {@code n} values of {@code field} with the highest, or with
     * {@code dir: asc} the lowest, measure {@code by}).
     */
    public record Filter(String field, String op, List<Object> values, Integer n, String by, String dir) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", field);
            m.put("op", op);
            if ("top".equals(op)) {
                m.put("n", n);
                m.put("by", by);
                m.put("dir", dir);
            } else if (!values.isEmpty()) {
                m.put("values", values);
            }
            return m;
        }
    }

    /** Where a visual sits: column {@code x} (0 to 11), row {@code y}, {@code w} columns wide, {@code h} rows high. */
    public record Grid(int x, int y, int w, int h) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", x);
            m.put("y", y);
            m.put("w", w);
            m.put("h", h);
            return m;
        }
    }

    /** A sort on a field or measure of the visual. */
    public record Sort(String by, boolean desc) {}

    /**
     * One visual.
     *
     * @param wells by well name, the fields and measures bound to it (what the type allows is {@link VisualCatalogue})
     * @param hierarchy a dataset hierarchy the axis drills down along
     * @param drillThrough the page a click on a mark may open, carrying the mark's value of {@code field} (null: none)
     * @param format display settings (title, labels, stacking, colour, conditional formats, slicer mode and scope ...), a map of plain values
     * @param text the words of a text visual or a button
     * @param interacts false: other visuals' selections do not filter or highlight this one
     * @param alt a text alternative for people who cannot see the visual
     * @param dataset the dataset this visual reads, when it is not the report's (null: the report's); a catalogue id, a file name or the dataset's name
     */
    public record Visual(String id, String type, String title, Grid at, Map<String, List<String>> wells, List<Filter> filters, Sort sort, Integer limit, String hierarchy,
            DrillThrough drillThrough, Map<String, Object> format, String text, String src, String alt, boolean interacts, Map<String, Object> action, String dataset) {

        /** Compatibility: a visual that reads the report's own dataset. */
        public Visual(String id, String type, String title, Grid at, Map<String, List<String>> wells, List<Filter> filters, Sort sort, Integer limit, String hierarchy,
                DrillThrough drillThrough, Map<String, Object> format, String text, String src, String alt, boolean interacts, Map<String, Object> action) {
            this(id, type, title, at, wells, filters, sort, limit, hierarchy, drillThrough, format, text, src, alt, interacts, action, null);
        }

        public List<String> well(String name) {
            return wells.getOrDefault(name, List.of());
        }
    }

    /** A click on a mark of the visual opens {@code page} with the mark's value of {@code field} carried as a filter. */
    public record DrillThrough(String page, String field) {}

    /** A page that is a drill-through target takes values of these fields as filters. */
    public record PageDrill(List<String> fields) {}

    public record Page(String id, String title, List<Filter> filters, PageDrill drillThrough, List<Visual> visuals) {

        public Visual visual(String id) {
            return visuals.stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
        }
    }

    /**
     * How one page is arranged on a phone (at most 640 px wide): the visuals in {@code order} first (others follow in the grid's reading order), those in
     * {@code hide} not drawn, and {@code span} the height in grid rows of a visual (1 to 40). On a phone the width is always the whole screen; on a tablet a visual
     * takes half the width unless it is listed in {@code wide} (the whole width). The same record serves both device classes.
     */
    public record PhonePage(List<String> order, List<String> hide, Map<String, Integer> span, List<String> wide) {

        public PhonePage(List<String> order, List<String> hide, Map<String, Integer> span) {
            this(order, hide, span, List.of());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (!order.isEmpty()) {
                m.put("order", order);
            }
            if (!hide.isEmpty()) {
                m.put("hide", hide);
            }
            if (!span.isEmpty()) {
                m.put("span", span);
            }
            if (!wide.isEmpty()) {
                m.put("wide", wide);
            }
            return m;
        }
    }

    /** A saved state: a page, slicer selections (by slicer visual id) and extra filters. */
    public record Bookmark(String name, String page, Map<String, List<Object>> slicers, List<Filter> filters) {}

    /** How {@code dataset} holds the binding of a report to a model ({@code model: trades@1} in the YAML; docs/architecture/BI_MODEL_SPEC.md section 4.1). */
    public static final String MODEL_PREFIX = "model:";

    /** The model the report binds to ({@code model:}), or null for a report on a dataset. */
    public String model() {
        return dataset != null && dataset.startsWith(MODEL_PREFIX) ? dataset.substring(MODEL_PREFIX.length()) : null;
    }

    /** The dataset a visual reads: its own, else the report's. */
    public String datasetOf(Visual v) {
        return v.dataset() == null || v.dataset().isBlank() ? dataset : v.dataset();
    }

    /** Every dataset the report reads, the report's own first. */
    public List<String> datasets() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        all.add(dataset);
        pages.forEach(p -> p.visuals().forEach(v -> all.add(datasetOf(v))));
        return new ArrayList<>(all);
    }

    public Page page(String id) {
        return pages.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    /** The structure the YAML has, in a stable key order (what the viewer and the designer read). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rachana", 1);
        m.put("report", name);
        m.put("version", version);
        m.put("title", title);
        if (!description.isBlank()) {
            m.put("description", description);
        }
        if (sample) {
            m.put("sample", true);
        }
        if (model() != null) {
            m.put("model", model());
        } else {
            m.put("dataset", dataset);
        }
        if (!theme.isEmpty()) {
            m.put("theme", theme);
        }
        if (dates != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            if (dates.from() != null) {
                d.put("from", dates.from());
            }
            if (dates.to() != null) {
                d.put("to", dates.to());
            }
            m.put("dates", d);
        }
        if (!filters.isEmpty()) {
            m.put("filters", filters.stream().map(Filter::toMap).toList());
        }
        if (!bookmarks.isEmpty()) {
            m.put("bookmarks", bookmarks.stream().map(b -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("name", b.name());
                x.put("page", b.page());
                if (!b.slicers().isEmpty()) {
                    x.put("slicers", b.slicers());
                }
                if (!b.filters().isEmpty()) {
                    x.put("filters", b.filters().stream().map(Filter::toMap).toList());
                }
                return x;
            }).toList());
        }
        if (!tests.isEmpty()) {
            m.put("tests", tests.stream().map(com.ash.drishti.server.bi.dataset.ExpectedValue::toMap).toList());
        }
        if (!links.isEmpty()) {
            m.put("links", links.stream().map(l -> Map.of("fields", l.fields())).toList());
        }
        if (!layouts.isEmpty()) {
            Map<String, Object> l = new LinkedHashMap<>();
            layouts.forEach((device, byPage) -> {
                Map<String, Object> pm = new LinkedHashMap<>();
                byPage.forEach((page, lay) -> pm.put(page, lay.toMap()));
                l.put(device, pm);
            });
            m.put("layouts", l);
        }
        List<Object> ps = new ArrayList<>();
        for (Page p : pages) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", p.id());
            x.put("title", p.title());
            if (p.drillThrough() != null) {
                x.put("drillthrough", Map.of("fields", p.drillThrough().fields()));
            }
            if (!p.filters().isEmpty()) {
                x.put("filters", p.filters().stream().map(Filter::toMap).toList());
            }
            x.put("visuals", p.visuals().stream().map(Report::visualMap).toList());
            ps.add(x);
        }
        m.put("pages", ps);
        return m;
    }

    private static Map<String, Object> visualMap(Visual v) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", v.id());
        x.put("type", v.type());
        if (!v.title().isBlank()) {
            x.put("title", v.title());
        }
        x.put("at", v.at().toMap());
        if (v.dataset() != null && !v.dataset().isBlank()) {
            x.put("dataset", v.dataset());
        }
        if (!v.wells().isEmpty()) {
            x.put("wells", v.wells());
        }
        if (!v.filters().isEmpty()) {
            x.put("filters", v.filters().stream().map(Filter::toMap).toList());
        }
        if (v.sort() != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("by", v.sort().by());
            s.put("desc", v.sort().desc());
            x.put("sort", s);
        }
        if (v.limit() != null) {
            x.put("limit", v.limit());
        }
        if (v.hierarchy() != null) {
            x.put("hierarchy", v.hierarchy());
        }
        if (v.drillThrough() != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("page", v.drillThrough().page());
            d.put("field", v.drillThrough().field());
            x.put("drillthrough", d);
        }
        if (!v.format().isEmpty()) {
            x.put("format", v.format());
        }
        if (v.text() != null && !v.text().isEmpty()) {
            x.put("text", v.text());
        }
        if (v.src() != null && !v.src().isEmpty()) {
            x.put("src", v.src());
        }
        if (v.alt() != null && !v.alt().isEmpty()) {
            x.put("alt", v.alt());
        }
        if (!v.interacts()) {
            x.put("interacts", false);
        }
        if (v.action() != null && !v.action().isEmpty()) {
            x.put("action", v.action());
        }
        return x;
    }
}
