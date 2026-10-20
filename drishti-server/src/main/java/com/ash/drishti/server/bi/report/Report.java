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
 * @param board the settings of the report's board pages ({@code layout: board}, R7): {@code rotate} (kiosk rotation, "20s") and {@code notify} (the tile alerts'
 *        default notification), as written; empty when the report has none
 * @param statement the settings of the report's statement pages ({@code layout: statement}, 2.6.0): paper, orientation, margins, header, footer, period and
 *        dates ({@link StatementSpec}), as written; empty when the report has none
 */
public record Report(String name, int version, String title, String description, boolean sample, String dataset, Map<String, String> theme, Dates dates, List<Filter> filters,
        List<Page> pages, List<Bookmark> bookmarks, Map<String, Map<String, PhonePage>> layouts, List<Link> links, List<com.ash.drishti.server.bi.dataset.ExpectedValue> tests,
        Map<String, LocalMeasure> measures, String perspective, Map<String, Object> board, Map<String, Object> statement) {

    /**
     * A report's own measure (docs/architecture/BI_MODEL_SPEC.md section 4.2): a DEL aggregate over the model's fields and measures, used by this report only,
     * sent with each visual's model query as {@code define}; it may not take a model measure's name ({@code DRS-9154}).
     */
    public record LocalMeasure(String name, String expr, String format, String description) {}

    public Report {
        measures = measures == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(measures));
        perspective = perspective == null || perspective.isBlank() ? null : perspective;
        board = board == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(board));
        statement = statement == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(statement));
    }

    /** Compatibility: a report without statement settings. */
    public Report(String name, int version, String title, String description, boolean sample, String dataset, Map<String, String> theme, Dates dates, List<Filter> filters,
            List<Page> pages, List<Bookmark> bookmarks, Map<String, Map<String, PhonePage>> layouts, List<Link> links, List<com.ash.drishti.server.bi.dataset.ExpectedValue> tests,
            Map<String, LocalMeasure> measures, String perspective, Map<String, Object> board) {
        this(name, version, title, description, sample, dataset, theme, dates, filters, pages, bookmarks, layouts, links, tests, measures, perspective, board, Map.of());
    }

    /** Compatibility: a report without board settings. */
    public Report(String name, int version, String title, String description, boolean sample, String dataset, Map<String, String> theme, Dates dates, List<Filter> filters,
            List<Page> pages, List<Bookmark> bookmarks, Map<String, Map<String, PhonePage>> layouts, List<Link> links, List<com.ash.drishti.server.bi.dataset.ExpectedValue> tests,
            Map<String, LocalMeasure> measures, String perspective) {
        this(name, version, title, description, sample, dataset, theme, dates, filters, pages, bookmarks, layouts, links, tests, measures, perspective, Map.of());
    }

    /** The palettes the console draws with; a {@code theme:} that names something else names a theme item ({@code theme: { use: corporate }}). */
    public static final java.util.Set<String> PALETTES = java.util.Set.of("default", "colour-blind", "mono");
    /** How a selection on one visual acts on another ({@code interactions:} of a page, Power BI's Edit interactions). */
    public static final java.util.Set<String> INTERACTIONS = java.util.Set.of("filter", "highlight", "none");

    /** Compatibility: a report without local measures or perspective. */
    public Report(String name, int version, String title, String description, boolean sample, String dataset, Map<String, String> theme, Dates dates, List<Filter> filters,
            List<Page> pages, List<Bookmark> bookmarks, Map<String, Map<String, PhonePage>> layouts, List<Link> links, List<com.ash.drishti.server.bi.dataset.ExpectedValue> tests) {
        this(name, version, title, description, sample, dataset, theme, dates, filters, pages, bookmarks, layouts, links, tests, Map.of(), null);
    }

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
     * @param tooltip the id of the tooltip page drawn over a hovered mark (a page with {@code tooltip: true}), or null
     * @param from what a {@code tile} shows (R7): a visual of another report, answered by that report as the person looking; null for any other type
     * @param alert a tile's colour rule ({@code on, warn, crit, hysteresis, notify}), as written; empty when it has none
     */
    public record Visual(String id, String type, String title, Grid at, Map<String, List<String>> wells, List<Filter> filters, Sort sort, Integer limit, String hierarchy,
            DrillThrough drillThrough, Map<String, Object> format, String text, String src, String alt, boolean interacts, Map<String, Object> action, String dataset,
            String tooltip, TileRef from, Map<String, Object> alert) {

        public Visual {
            alert = alert == null ? Map.of() : alert;
        }

        /** Compatibility: a visual with no tooltip page and that is no tile. */
        public Visual(String id, String type, String title, Grid at, Map<String, List<String>> wells, List<Filter> filters, Sort sort, Integer limit, String hierarchy,
                DrillThrough drillThrough, Map<String, Object> format, String text, String src, String alt, boolean interacts, Map<String, Object> action, String dataset) {
            this(id, type, title, at, wells, filters, sort, limit, hierarchy, drillThrough, format, text, src, alt, interacts, action, dataset, null, null, Map.of());
        }

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

    /** What a tile shows: the visual {@code visual} of the report {@code report} (a catalogue id, a file name or its title), on {@code page} (empty: the page that holds it). */
    public record TileRef(String report, String page, String visual) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("report", report);
            if (page != null && !page.isBlank()) {
                m.put("page", page);
            }
            m.put("visual", visual);
            return m;
        }
    }

    /** A page that is a drill-through target takes values of these fields as filters. */
    public record PageDrill(List<String> fields) {}

    /**
     * A page.
     *
     * @param layout {@code grid} (the default) or {@code board} (R7: a wall of tiles and visuals with kiosk, rotation and tile alerts)
     * @param tooltip true for a tooltip page: left out of the tabs, drawn in a popover over a hovered mark of a visual that names it, filtered to that mark
     * @param interactions by source visual id, by target visual id: {@code filter}, {@code highlight} or {@code none} (Power BI's Edit interactions); a pair
     *        not listed keeps the default (the viewer's Filter or Highlight choice)
     */
    public record Page(String id, String title, List<Filter> filters, PageDrill drillThrough, List<Visual> visuals, String layout, boolean tooltip,
            Map<String, Map<String, String>> interactions) {

        public Page {
            layout = layout == null || layout.isBlank() ? "grid" : layout;
            interactions = interactions == null ? Map.of() : interactions;
        }

        /** Compatibility: a grid page with the default interactions. */
        public Page(String id, String title, List<Filter> filters, PageDrill drillThrough, List<Visual> visuals) {
            this(id, title, filters, drillThrough, visuals, "grid", false, Map.of());
        }

        public Visual visual(String id) {
            return visuals.stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
        }

        public boolean board() {
            return "board".equals(layout);
        }

        /** A statement page (2.6.0): its visuals are bands laid out on paper, page by page, top to bottom ({@link StatementSpec}). */
        public boolean statement() {
            return "statement".equals(layout);
        }

        /** How a selection on {@code source} acts on {@code target}: {@code filter}, {@code highlight}, {@code none}, or null when the page does not say. */
        public String interaction(String source, String target) {
            Map<String, String> row = interactions.get(source);
            return row == null ? null : row.get(target);
        }
    }

    /** True when some page is a statement ({@code layout: statement}). */
    public boolean hasStatement() {
        return pages.stream().anyMatch(Page::statement);
    }

    /** True when some page is a board ({@code layout: board}). */
    public boolean hasBoard() {
        return pages.stream().anyMatch(Page::board);
    }

    /** True when the report reads no data of its own: every visual of every page is a tile or static, so it needs no {@code model:} or {@code dataset:}. */
    public boolean tilesOnly() {
        return pages.stream().flatMap(p -> p.visuals().stream()).allMatch(v -> {
            VisualCatalogue.Type t = VisualCatalogue.type(v.type());
            return t != null && ("board".equals(t.group()) || "static".equals(t.group()));
        });
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
        if (dataset != null && !dataset.isBlank()) {
            all.add(dataset);
        }
        pages.forEach(p -> p.visuals().stream().filter(v -> !"tile".equals(v.type())).forEach(v -> {
            String d = datasetOf(v);
            if (d != null && !d.isBlank()) {
                all.add(d);
            }
        }));
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
            if (perspective != null) {
                m.put("perspective", perspective);
            }
            if (!measures.isEmpty()) {
                Map<String, Object> lm = new LinkedHashMap<>();
                measures.values().forEach(x -> {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("expr", x.expr());
                    if (x.format() != null) {
                        one.put("format", x.format());
                    }
                    if (x.description() != null) {
                        one.put("description", x.description());
                    }
                    lm.put(x.name(), one);
                });
                m.put("measures", lm);
            }
        } else if (dataset != null && !dataset.isBlank()) {
            m.put("dataset", dataset);
        }
        if (!board.isEmpty()) {
            m.put("board", board);
        }
        if (!statement.isEmpty()) {
            m.put("statement", statement);
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
            if (p.board() || p.statement()) {
                x.put("layout", p.layout());
            }
            if (p.tooltip()) {
                x.put("tooltip", true);
            }
            if (p.drillThrough() != null) {
                x.put("drillthrough", Map.of("fields", p.drillThrough().fields()));
            }
            if (!p.filters().isEmpty()) {
                x.put("filters", p.filters().stream().map(Filter::toMap).toList());
            }
            if (!p.interactions().isEmpty()) {
                x.put("interactions", p.interactions());
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
        if (v.tooltip() != null && !v.tooltip().isBlank()) {
            x.put("tooltip", v.tooltip());
        }
        if (v.from() != null) {
            x.put("from", v.from().toMap());
        }
        if (!v.alert().isEmpty()) {
            x.put("alert", v.alert());
        }
        return x;
    }
}
