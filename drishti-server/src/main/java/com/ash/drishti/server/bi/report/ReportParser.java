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

import com.ash.drishti.server.bi.engine.QueryRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a report's YAML (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 8, docs/guides/BI_GUIDE.md) into a {@link Report} and checks its structure; what the report names inside its
 * dataset is checked by {@link ReportValidator}. Never throws for a bad document: the problems come back with their DRS code and the path of the
 * offending key, so an author sees every mistake at once. Missing page and visual ids are made up ({@code page1}, {@code v1}) so the result is
 * complete; nothing in a report is executed.
 */
public final class ReportParser {

    /** One finding. {@code level} is {@code error} (the report cannot be drawn) or {@code warning}. */
    public record Problem(String level, String code, String path, String message) {
        public boolean error() {
            return "error".equals(level);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level);
            m.put("code", code);
            m.put("path", path);
            m.put("message", message);
            return m;
        }
    }

    /** The outcome: the report when the structure was readable (null otherwise), and every problem found. */
    public record Result(Report report, List<Problem> problems) {
        public boolean ok() {
            return report != null && problems.stream().noneMatch(Problem::error);
        }

        public String firstError() {
            return problems.stream().filter(Problem::error).findFirst().map(p -> p.code() + " " + p.path() + ": " + p.message()).orElse("");
        }
    }

    static final String INVALID = "DRS-9030";
    static final String REFERENCE = "DRS-9031";
    static final String VISUAL = "DRS-9032";
    static final String NOT_BUILT = "DRS-9033";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,39}");
    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Set<String> TOP = Set.of("rachana", "report", "version", "title", "description", "sample", "dataset", "model", "perspective", "measures", "theme", "dates", "filters", "bookmarks", "layouts", "links", "tests", "pages",
            "owner", "tags", "endorsement", "endorsedBy", "audience", "board", "statement", "as-of");
    private static final Set<String> PAGE = Set.of("id", "title", "filters", "drillthrough", "visuals", "layout", "tooltip", "interactions");
    private static final Set<String> VISUAL_KEYS = Set.of("id", "type", "title", "at", "wells", "filters", "sort", "limit", "hierarchy", "drillthrough", "format", "text", "src", "alt",
            "interacts", "action", "dataset", "tooltip", "from", "alert");
    private static final Set<String> THEME_KEYS = Set.of("palette", "background", "use");
    private static final Set<String> BOARD_KEYS = Set.of("rotate", "notify");
    private static final Set<String> FILTER = Set.of("field", "op", "values", "value", "n", "by", "dir");
    /** Limits that keep one report a sensible size. */
    public static final int MAX_PAGES = 30;
    public static final int MAX_VISUALS_PER_PAGE = 60;

    private ReportParser() {}

    public static Result parse(String yaml) {
        List<Problem> out = new ArrayList<>();
        JsonNode root;
        try {
            root = yaml == null || yaml.isBlank() ? null : YAML.readTree(yaml);
        } catch (JsonProcessingException e) {
            out.add(error(INVALID, "", "the YAML is not valid: " + com.ash.drishti.server.bi.YamlErrors.describe(e)));
            return new Result(null, out);
        }
        if (root == null || !root.isObject()) {
            out.add(error(INVALID, "", "a report is a YAML mapping (key: value) with at least `report:`, `dataset:` and `pages:`"));
            return new Result(null, out);
        }
        root.fieldNames().forEachRemaining(k -> {
            if (!TOP.contains(k)) {
                out.add(new Problem("warning", INVALID, k, "unknown key '" + k + "' is ignored"));
            }
        });
        int rachana = root.path("rachana").asInt(1);
        if (rachana != 1 && rachana != 2) {
            out.add(error(INVALID, "rachana", "this server reads Rachana version 1 or 2 (a report on a model), not " + root.path("rachana").asInt()));
        } else if (rachana == 2 && !root.hasNonNull("model")) {
            out.add(error("DRS-9140", "model", "a rachana: 2 report reads a model (`model: <name>@<major>`), not a dataset (docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, section 4.1)"));
        }
        String name = text(root, "report", "");
        if (name.isBlank()) {
            out.add(error(INVALID, "report", "`report:` names the report (letters, digits, hyphen, underscore)"));
        } else if (!NAME.matcher(name).matches()) {
            out.add(error(INVALID, "report", "'" + name + "' is not a usable name (letters, digits, hyphen, underscore, dot; up to 64)"));
        }
        String dataset = text(root, "dataset", "");
        String model = text(root, "model", "");
        Map<String, String> theme = theme(root.path("theme"), out);
        Report.Dates dates = dates(root.path("dates"), "dates", out);
        List<Report.Filter> filters = filters(root.path("filters"), "filters", out);
        List<Report.Page> pages = pages(root.path("pages"), out);
        boolean tilesOnly = !pages.isEmpty() && pages.stream().anyMatch(Report.Page::board) && pages.stream().flatMap(x -> x.visuals().stream())
                .allMatch(x -> "tile".equals(x.type()) || "static".equals(VisualCatalogue.type(x.type()).group()));
        if (!model.isBlank() && !dataset.isBlank()) {
            out.add(error("DRS-9140", "model", "a report reads one model: `model:` or `dataset:`, not both (docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, section 4.1)"));
        } else if (!model.isBlank()) {
            dataset = Report.MODEL_PREFIX + model;                              // a report bound to a model (wave W1): Report.model() names it
        } else if (dataset.isBlank() && !tilesOnly) {                         // a wall of tiles reads no data of its own (R7): each tile is answered by its report
            out.add(error(INVALID, "dataset", "`dataset:` names the dataset the report reads (its catalogue id, file name or name), or `model:` the model"));
        }
        Map<String, Object> statement = map(root.path("statement"), "statement", out);
        Map<String, Object> board = map(root.path("board"), "board", out);
        board.keySet().stream().filter(k -> !BOARD_KEYS.contains(k)).forEach(k -> out.add(new Problem("warning", INVALID, "board." + k, "unknown board key '" + k + "' is ignored (rotate, notify)")));
        if (!board.isEmpty() && pages.stream().noneMatch(Report.Page::board)) {
            out.add(new Problem("warning", INVALID, "board", "`board:` sets the board pages (rotation, notifications), and no page has layout: board"));
        }
        List<Report.Bookmark> bookmarks = bookmarks(root.path("bookmarks"), out);
        List<com.ash.drishti.server.bi.dataset.ExpectedValue.Problem> testProblems = new ArrayList<>();
        List<com.ash.drishti.server.bi.dataset.ExpectedValue> tests = com.ash.drishti.server.bi.dataset.ExpectedValue.parse(root.path("tests"), "tests", true, testProblems);
        testProblems.forEach(tp -> out.add(error(INVALID, tp.path(), tp.message())));
        Map<String, Report.LocalMeasure> local = localMeasures(root.path("measures"), out);
        String perspective = text(root, "perspective", "");
        if ((!local.isEmpty() || !perspective.isBlank()) && model.isBlank()) {
            out.add(error("DRS-9140", local.isEmpty() ? "perspective" : "measures", "a report's own measures and its perspective belong to a report on a model (`model:`)"));
        }
        Report r = new Report(name, root.path("version").asInt(1), text(root, "title", name), text(root, "description", ""), root.path("sample").asBoolean(false), dataset,
                theme, dates, filters, pages, bookmarks, layouts(root.path("layouts"), out), links(root.path("links"), out), tests, local, perspective, board, statement,
                AsOfRule.parse(root.path("as-of"), out));
        out.addAll(com.ash.drishti.server.bi.board.ReportBoards.check(r));
        out.addAll(StatementSpec.check(r));                                     // statement pages and their paper (2.6.0, DRS-9038)
        pageReferences(r, out);
        return new Result(r, out);
    }

    /** What pages and visuals point at inside the report: tooltip pages, the interactions matrix, sync groups (the dataset is the validator's). */
    private static void pageReferences(Report r, List<Problem> out) {
        Map<String, String> syncField = new LinkedHashMap<>();
        for (int pi = 0; pi < r.pages().size(); pi++) {
            Report.Page page = r.pages().get(pi);
            String pp = "pages[" + pi + "]";
            if (page.tooltip() && page.board()) {
                out.add(error(INVALID, pp + ".tooltip", "a tooltip page is a grid page: a board cannot be drawn in a popover"));
            }
            page.interactions().forEach((src, row) -> {
                if (page.visual(src) == null) {
                    out.add(error(REFERENCE, pp + ".interactions." + src, "the page has no visual '" + src + "' to take a selection from"));
                }
                row.keySet().stream().filter(t -> page.visual(t) == null).forEach(t -> out.add(error(REFERENCE, pp + ".interactions." + src + "." + t, "the page has no visual '" + t + "'")));
            });
            for (int vi = 0; vi < page.visuals().size(); vi++) {
                Report.Visual v = page.visuals().get(vi);
                String vp = pp + ".visuals[" + vi + "]";
                if (v.tooltip() != null) {
                    Report.Page tip = r.page(v.tooltip());
                    if (tip == null || !tip.tooltip()) {
                        out.add(error(REFERENCE, vp + ".tooltip", "'" + v.tooltip() + "' is not a tooltip page of this report (a page with tooltip: true)" + (tip == null ? "" : "; mark it as one in its properties")));
                    } else if (page.tooltip()) {
                        out.add(error(INVALID, vp + ".tooltip", "a visual on a tooltip page cannot open another tooltip page"));
                    }
                }
                Object group = v.format().get("syncGroup");
                if ("slicer".equals(v.type()) && group != null && !v.well("field").isEmpty()) {
                    String was = syncField.putIfAbsent(String.valueOf(group), v.well("field").get(0));
                    if (was != null && !was.equals(v.well("field").get(0))) {
                        out.add(error(VISUAL, vp + ".format.syncGroup", "the slicers of sync group '" + group + "' share one choice, so they slice one field (" + was + "), not " + v.well("field").get(0)));
                    }
                }
            }
        }
    }

    // ---- sections ----------------------------------------------------------------------------------------------------

    /** {@code measures: { Name: "DEL" }} or {@code { Name: { expr, format, description } }}: the report's own measures (a report on a model). */
    private static Map<String, Report.LocalMeasure> localMeasures(JsonNode n, List<Problem> out) {
        Map<String, Report.LocalMeasure> m = new LinkedHashMap<>();
        if (n.isMissingNode() || n.isNull()) {
            return m;
        }
        if (!n.isObject()) {
            out.add(error(INVALID, "measures", "measures is a mapping: Name: \"DEL expression\" or Name: { expr, format, description }"));
            return m;
        }
        n.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            String expr = v.isTextual() ? v.asText() : v.path("expr").asText("");
            if (expr.isBlank()) {
                out.add(error(INVALID, "measures." + e.getKey(), "a measure needs `expr:`, a DEL aggregate (docs/guides/BI_LANGUAGE_REFERENCE.md)"));
                return;
            }
            m.put(e.getKey(), new Report.LocalMeasure(e.getKey(), expr, v.isObject() && v.hasNonNull("format") ? v.path("format").asText() : null,
                    v.isObject() && v.hasNonNull("description") ? v.path("description").asText() : null));
        });
        return m;
    }

    private static Map<String, String> theme(JsonNode n, List<Problem> out) {
        Map<String, String> theme = new LinkedHashMap<>();
        if (n.isMissingNode() || n.isNull()) {
            return theme;
        }
        if (n.isTextual()) {
            theme.put(Report.PALETTES.contains(n.asText()) ? "palette" : "use", n.asText());      // a name that is no palette names a theme item (R6)
        } else if (n.isObject()) {
            n.fields().forEachRemaining(e -> {
                if (!THEME_KEYS.contains(e.getKey())) {
                    out.add(new Problem("warning", INVALID, "theme." + e.getKey(), "unknown theme key '" + e.getKey() + "' is ignored (use, palette, background)"));
                } else {
                    theme.put(e.getKey(), e.getValue().asText());
                }
            });
        } else {
            out.add(error(INVALID, "theme", "theme is a palette name, a theme item's name, or { use: <theme>, palette, background }"));
        }
        if (theme.containsKey("background") && !theme.get("background").matches("#[0-9a-fA-F]{6}")) {
            out.add(new Problem("warning", INVALID, "theme.background", "background is a colour (#rrggbb); it is ignored"));
        }
        return theme;
    }

    private static Report.Dates dates(JsonNode n, String path, List<Problem> out) {
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isObject()) {
            out.add(error(INVALID, path, "dates is { from, to } with yyyy-mm-dd days"));
            return null;
        }
        String from = day(n, "from", path, out);
        String to = day(n, "to", path, out);
        if (from != null && to != null && to.compareTo(from) < 0) {
            out.add(error(INVALID, path, "dates.to is before dates.from"));
        }
        return from == null && to == null ? null : new Report.Dates(from, to);
    }

    private static String day(JsonNode n, String key, String path, List<Problem> out) {
        JsonNode v = n.path(key);
        if (v.isMissingNode() || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        if (!DAY.matcher(v.asText()).matches()) {
            out.add(error(INVALID, path + "." + key, "'" + v.asText() + "' is not a day (yyyy-mm-dd)"));
            return null;
        }
        return v.asText();
    }

    static List<Report.Filter> filters(JsonNode n, String path, List<Problem> out) {
        List<Report.Filter> list = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return list;
        }
        if (!n.isArray()) {
            out.add(error(INVALID, path, "filters is a list: - { field: table.column, op: in, values: [...] }"));
            return list;
        }
        int i = 0;
        for (JsonNode f : n) {
            String p = path + "[" + i++ + "]";
            if (!f.isObject() || !f.path("field").isTextual()) {
                out.add(error(INVALID, p, "a filter is { field, op, values }"));
                continue;
            }
            f.fieldNames().forEachRemaining(k -> {
                if (!FILTER.contains(k)) {
                    out.add(new Problem("warning", INVALID, p + "." + k, "unknown filter key '" + k + "' is ignored"));
                }
            });
            String op = f.path("op").asText("in").toLowerCase(Locale.ROOT);
            if ("top".equals(op)) {
                int count = f.path("n").asInt(0);
                if (count < 1 || !f.path("by").isTextual()) {
                    out.add(error(INVALID, p, "a top filter is { field, op: top, n: 5, by: <measure>, dir: desc }"));
                    continue;
                }
                String dir = f.path("dir").asText("desc").toLowerCase(Locale.ROOT);
                if (!dir.equals("desc") && !dir.equals("asc")) {
                    out.add(error(INVALID, p + ".dir", "dir is desc or asc"));
                    continue;
                }
                list.add(new Report.Filter(f.path("field").asText(), "top", List.of(), count, f.path("by").asText(), dir));
                continue;
            }
            if (!QueryRequest.OPS.contains(op)) {
                out.add(error(INVALID, p + ".op", "'" + op + "' is not an operator; use top or one of " + new java.util.TreeSet<>(QueryRequest.OPS)));
                continue;
            }
            List<Object> values = new ArrayList<>();
            JsonNode vs = f.has("values") ? f.get("values") : f.get("value");
            if (vs != null && vs.isArray()) {
                vs.forEach(v -> values.add(scalar(v)));
            } else if (vs != null && !vs.isNull()) {
                values.add(scalar(vs));
            }
            if (values.contains(null)) {
                out.add(error(INVALID, p + ".values", "a filter value is a number, text or true/false"));
                continue;
            }
            list.add(new Report.Filter(f.path("field").asText(), op, values, null, null, null));
        }
        return list;
    }

    private static Object scalar(JsonNode v) {
        if (v.isIntegralNumber() && v.canConvertToLong()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isTextual()) {
            return v.asText();
        }
        return null;
    }

    private static List<Report.Page> pages(JsonNode n, List<Problem> out) {
        List<Report.Page> pages = new ArrayList<>();
        if (n.isMissingNode() || n.isNull() || (n.isArray() && n.isEmpty())) {
            out.add(error(INVALID, "pages", "a report needs `pages:`, a list with at least one page"));
            return pages;
        }
        if (!n.isArray()) {
            out.add(error(INVALID, "pages", "pages is a list: - { id, title, visuals: [...] }"));
            return pages;
        }
        if (n.size() > MAX_PAGES) {
            out.add(error(INVALID, "pages", "a report holds at most " + MAX_PAGES + " pages"));
        }
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonNode p : n) {
            String path = "pages[" + i + "]";
            i++;
            if (!p.isObject()) {
                out.add(error(INVALID, path, "a page is { id, title, visuals: [...] }"));
                continue;
            }
            p.fieldNames().forEachRemaining(k -> {
                if (!PAGE.contains(k)) {
                    out.add(new Problem("warning", INVALID, path + "." + k, "unknown page key '" + k + "' is ignored"));
                }
            });
            String title = text(p, "title", "");
            String id = text(p, "id", slug(title, "page" + i));
            if (!ID.matcher(id).matches()) {
                out.add(error(INVALID, path + ".id", "'" + id + "' is not a page id (letters, digits, hyphen, underscore; up to 40)"));
            } else if (!seen.add(id)) {
                out.add(error(INVALID, path + ".id", "page id '" + id + "' is used twice"));
            }
            Report.PageDrill drill = null;
            JsonNode dt = p.path("drillthrough");
            if (!dt.isMissingNode() && !dt.isNull()) {
                List<String> fields = strings(dt.path("fields"));
                if (fields.isEmpty()) {
                    out.add(error(INVALID, path + ".drillthrough", "drillthrough is { fields: [table.column] }: the fields this page takes as filters"));
                } else {
                    drill = new Report.PageDrill(fields);
                }
            }
            List<Report.Visual> visuals = visuals(p.path("visuals"), path + ".visuals", out);
            String layout = text(p, "layout", "grid");
            if (!layout.equals("grid") && !layout.equals("board") && !layout.equals("statement")) {
                out.add(error(INVALID, path + ".layout", "layout is grid (the default), board (a wall of tiles with kiosk, rotation and tile alerts) or statement (page-exact paper: PDF and Excel)"));
                layout = "grid";
            }
            JsonNode tip = p.path("tooltip");
            if (!tip.isMissingNode() && !tip.isNull() && !tip.isBoolean()) {
                out.add(error(INVALID, path + ".tooltip", "tooltip is true (this page is drawn over a hovered mark) or false"));
            }
            pages.add(new Report.Page(id, title.isBlank() ? id : title, filters(p.path("filters"), path + ".filters", out), drill, visuals, layout, tip.asBoolean(false),
                    interactions(p.path("interactions"), path + ".interactions", out)));
        }
        return pages;
    }

    private static List<Report.Visual> visuals(JsonNode n, String path, List<Problem> out) {
        List<Report.Visual> list = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return list;
        }
        if (!n.isArray()) {
            out.add(error(INVALID, path, "visuals is a list: - { type: bar, at: { x: 0, y: 0, w: 6, h: 4 }, wells: {...} }"));
            return list;
        }
        if (n.size() > MAX_VISUALS_PER_PAGE) {
            out.add(error(INVALID, path, "a page holds at most " + MAX_VISUALS_PER_PAGE + " visuals"));
        }
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonNode v : n) {
            String p = path + "[" + i + "]";
            i++;
            Report.Visual visual = visual(v, p, "v" + i, seen, out);
            if (visual != null) {
                list.add(visual);
            }
        }
        return list;
    }

    private static Report.Visual visual(JsonNode v, String p, String fallbackId, Set<String> seen, List<Problem> out) {
        if (!v.isObject()) {
            out.add(error(INVALID, p, "a visual is { type, at, wells ... }"));
            return null;
        }
        v.fieldNames().forEachRemaining(k -> {
            if (!VISUAL_KEYS.contains(k)) {
                out.add(new Problem("warning", INVALID, p + "." + k, "unknown visual key '" + k + "' is ignored"));
            }
        });
        String type = text(v, "type", "");
        String id = text(v, "id", fallbackId);
        if (!ID.matcher(id).matches()) {
            out.add(error(INVALID, p + ".id", "'" + id + "' is not a visual id (letters, digits, hyphen, underscore; up to 40)"));
        } else if (!seen.add(id)) {
            out.add(error(INVALID, p + ".id", "visual id '" + id + "' is used twice on the page"));
        }
        if (VisualCatalogue.RESERVED.contains(type)) {
            out.add(error(NOT_BUILT, p + ".type", "the visual '" + type + "' is reserved and not built yet (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 20.3); built: " + String.join(", ", VisualCatalogue.names())));
            return null;
        }
        VisualCatalogue.Type spec = VisualCatalogue.type(type);
        if (spec == null) {
            out.add(error(INVALID, p + ".type", type.isBlank() ? "a visual needs a type: " + String.join(", ", VisualCatalogue.names())
                    : "'" + type + "' is not a visual type; use one of " + String.join(", ", VisualCatalogue.names())));
            return null;
        }
        Report.Grid at = grid(v.path("at"), p + ".at", out);
        Map<String, List<String>> wells = new LinkedHashMap<>();
        JsonNode w = v.path("wells");
        if (!w.isMissingNode() && !w.isNull()) {
            if (!w.isObject()) {
                out.add(error(INVALID, p + ".wells", "wells is a mapping from a well to its fields or measures: { axis: [trades.desk], values: [MTM] }"));
            } else {
                w.fields().forEachRemaining(e -> {
                    List<String> items = strings(e.getValue());
                    if (!spec.wells().containsKey(e.getKey())) {
                        out.add(error(VISUAL, p + ".wells." + e.getKey(), "a " + type + " has no well '" + e.getKey() + "'; its wells are " + String.join(", ", spec.wells().keySet())));
                    } else {
                        wells.put(e.getKey(), items);
                    }
                });
            }
        }
        Report.Sort sort = null;
        JsonNode s = v.path("sort");
        if (s.isTextual()) {
            String[] bits = s.asText().trim().split("\\s+(?=(asc|desc)$)", 2);
            sort = new Report.Sort(bits[0], bits.length > 1 && bits[1].equalsIgnoreCase("desc"));
        } else if (s.isObject() && s.path("by").isTextual()) {
            sort = new Report.Sort(s.path("by").asText(), s.path("desc").asBoolean(false));
        } else if (!s.isMissingNode() && !s.isNull()) {
            out.add(error(INVALID, p + ".sort", "sort is { by: <field or measure>, desc: true }"));
        }
        Integer limit = null;
        if (v.has("limit")) {
            if (v.get("limit").canConvertToInt() && v.get("limit").asInt() >= 1) {
                limit = v.get("limit").asInt();
            } else {
                out.add(error(INVALID, p + ".limit", "limit is a positive number"));
            }
        }
        Report.DrillThrough dt = null;
        JsonNode d = v.path("drillthrough");
        if (d.isObject() && d.path("page").isTextual() && d.path("field").isTextual()) {
            dt = new Report.DrillThrough(d.path("page").asText(), d.path("field").asText());
        } else if (!d.isMissingNode() && !d.isNull()) {
            out.add(error(INVALID, p + ".drillthrough", "drillthrough is { page: <page id>, field: table.column }"));
        }
        Map<String, Object> format = map(v.path("format"), p + ".format", out);
        Map<String, Object> action = map(v.path("action"), p + ".action", out);
        checkSlicerMode(type, format, p, out);
        out.addAll(FormatSchemas.get().check(type, format, p));                 // the typed format of the visual's family (R6)
        String tooltip = text(v, "tooltip", "");
        if (!tooltip.isEmpty() && !ID.matcher(tooltip).matches()) {
            out.add(error(INVALID, p + ".tooltip", "tooltip names a tooltip page of this report by its id"));
            tooltip = "";
        }
        Report.TileRef from = null;
        JsonNode fr = v.path("from");
        if (fr.isObject()) {
            from = new Report.TileRef(text(fr, "report", ""), text(fr, "page", ""), text(fr, "visual", ""));
            if (from.report().isBlank() || from.visual().isBlank()) {
                out.add(error(INVALID, p + ".from", "from is { report: <report>, visual: <visual id>, page: <page id, optional> }"));
            }
        } else if (!fr.isMissingNode() && !fr.isNull()) {
            out.add(error(INVALID, p + ".from", "from is { report: <report>, visual: <visual id> }"));
        }
        if (from != null && !"tile".equals(type)) {
            out.add(new Problem("warning", INVALID, p + ".from", "only a tile shows a visual of another report; `from` is ignored"));
            from = null;
        }
        Map<String, Object> alert = map(v.path("alert"), p + ".alert", out);
        String src = text(v, "src", "");
        if (!src.isEmpty() && !(src.startsWith("/static/") || src.startsWith("data:image/")) || src.contains("..")) {
            out.add(error(INVALID, p + ".src", "an image comes from this site (/static/...) or is a data:image/ URI, never another address"));
            src = "";
        }
        return new Report.Visual(id, type, text(v, "title", ""), at, wells, filters(v.path("filters"), p + ".filters", out), sort, limit, v.path("hierarchy").isTextual()
                ? v.path("hierarchy").asText() : null, dt, format, text(v, "text", ""), src, text(v, "alt", ""), v.path("interacts").asBoolean(true), action,
                text(v, "dataset", "").isBlank() ? null : text(v, "dataset", ""), tooltip.isEmpty() ? null : tooltip, from, alert);
    }

    private static void checkSlicerMode(String type, Map<String, Object> format, String p, List<Problem> out) {
        if (!"slicer".equals(type) || !format.containsKey("mode")) {
            return;
        }
        String mode = String.valueOf(format.get("mode"));
        if (!VisualCatalogue.SLICER_MODES.contains(mode)) {
            out.add(error(INVALID, p + ".format.mode", "a slicer's mode is list, dropdown, range or relative-date"));
        }
        if ("relative-date".equals(mode)) {
            Object def = format.get("default");
            if (def != null && !RelativeDates.valid(String.valueOf(def))) {
                out.add(error(INVALID, p + ".format.default", "'" + def + "' is not a relative date: last-N-days, last-N-business-days, last-N-weeks, last-N-months, this-week|month|quarter|year, previous-week|month|quarter|year or all"));
            }
            Object options = format.get("options");
            if (options != null && !(options instanceof List<?> l && !l.isEmpty() && l.stream().allMatch(x -> RelativeDates.valid(String.valueOf(x))))) {
                out.add(error(INVALID, p + ".format.options", "options is a list of relative dates, for example [last-7-days, last-30-days, this-month, all]"));
            }
        }
        Object scope = format.get("scope");
        if (scope != null && !"page".equals(scope) && !"report".equals(scope)) {
            out.add(error(INVALID, p + ".format.scope", "a slicer's scope is page or report"));
        }
    }

    private static Report.Grid grid(JsonNode n, String path, List<Problem> out) {
        if (!n.isObject() || !n.has("w") || !n.has("h")) {
            out.add(error(INVALID, path, "at is the visual's place on the 12-column grid: { x: 0, y: 0, w: 6, h: 4 }"));
            return new Report.Grid(0, 0, 6, 4);
        }
        int x = n.path("x").asInt(0);
        int y = n.path("y").asInt(0);
        int w = n.path("w").asInt(0);
        int h = n.path("h").asInt(0);
        if (x < 0 || x >= Report.COLUMNS || w < 1 || x + w > Report.COLUMNS) {
            out.add(error(INVALID, path, "x is 0 to 11 and w is 1 to 12, and x + w is at most 12 (the grid has 12 columns); this is x=" + x + ", w=" + w));
        }
        if (y < 0 || h < 1 || h > 40 || y > 1000) {
            out.add(error(INVALID, path, "y is 0 or more and h is 1 to 40 rows"));
        }
        return new Report.Grid(Math.max(0, x), Math.max(0, y), Math.max(1, w), Math.max(1, h));
    }

    private static List<Report.Bookmark> bookmarks(JsonNode n, List<Problem> out) {
        List<Report.Bookmark> list = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return list;
        }
        if (!n.isArray()) {
            out.add(error(INVALID, "bookmarks", "bookmarks is a list: - { name, page, slicers: { <slicer id>: [values] } }"));
            return list;
        }
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonNode b : n) {
            String p = "bookmarks[" + i++ + "]";
            String name = text(b, "name", "");
            if (!b.isObject() || name.isBlank() || !b.path("page").isTextual()) {
                out.add(error(INVALID, p, "a bookmark is { name, page, slicers?, filters? }"));
                continue;
            }
            if (!seen.add(name)) {
                out.add(error(INVALID, p + ".name", "bookmark '" + name + "' is used twice"));
            }
            Map<String, List<Object>> slicers = new LinkedHashMap<>();
            JsonNode sl = b.path("slicers");
            if (sl.isObject()) {
                sl.fields().forEachRemaining(e -> {
                    List<Object> vals = new ArrayList<>();
                    if (e.getValue().isArray()) {
                        e.getValue().forEach(x -> vals.add(scalar(x)));
                    } else if (!e.getValue().isNull()) {
                        vals.add(scalar(e.getValue()));
                    }
                    slicers.put(e.getKey(), vals);
                });
            } else if (!sl.isMissingNode() && !sl.isNull()) {
                out.add(error(INVALID, p + ".slicers", "slicers maps a slicer's id to the values it selects"));
            }
            list.add(new Report.Bookmark(name, b.path("page").asText(), slicers, filters(b.path("filters"), p + ".filters", out)));
        }
        return list;
    }

    /** {@code interactions: { <source visual>: { <target visual>: filter | highlight | none } }}: Power BI's Edit interactions, per page; references are checked after the page. */
    private static Map<String, Map<String, String>> interactions(JsonNode n, String path, List<Problem> out) {
        Map<String, Map<String, String>> all = new LinkedHashMap<>();
        if (n.isMissingNode() || n.isNull()) {
            return all;
        }
        if (!n.isObject()) {
            out.add(error(INVALID, path, "interactions maps a visual to how its selection acts on the others: { by-desk: { trend: highlight, kpis: none } }"));
            return all;
        }
        n.fields().forEachRemaining(src -> {
            if (!src.getValue().isObject()) {
                out.add(error(INVALID, path + "." + src.getKey(), "maps each other visual to filter, highlight or none"));
                return;
            }
            Map<String, String> row = new LinkedHashMap<>();
            src.getValue().fields().forEachRemaining(t -> {
                String how = t.getValue().asText("").toLowerCase(Locale.ROOT);
                if (!Report.INTERACTIONS.contains(how)) {
                    out.add(error(INVALID, path + "." + src.getKey() + "." + t.getKey(), "'" + t.getValue().asText() + "' is not an interaction: filter, highlight or none"));
                } else if (t.getKey().equals(src.getKey())) {
                    out.add(new Problem("warning", INVALID, path + "." + src.getKey() + "." + t.getKey(), "a visual's selection never filters itself; this entry is ignored"));
                } else {
                    row.put(t.getKey(), how);
                }
            });
            if (!row.isEmpty()) {
                all.put(src.getKey(), row);
            }
        });
        return all;
    }

    /** {@code links: [ { fields: [a.counterparty, b.counterparty] } ]}: fields of different datasets that mean the same thing; each field is table.column. */
    private static List<Report.Link> links(JsonNode n, List<Problem> out) {
        List<Report.Link> list = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return list;
        }
        if (!n.isArray()) {
            out.add(error(INVALID, "links", "links is a list: - { fields: [trade.counterparty, exposure.counterparty] }"));
            return list;
        }
        for (int i = 0; i < n.size(); i++) {
            JsonNode l = n.get(i);
            List<String> fields = new ArrayList<>();
            if (l.path("fields").isArray()) {
                l.path("fields").forEach(f -> fields.add(f.asText()));
            }
            if (fields.size() < 2 || fields.stream().anyMatch(f -> f.isBlank() || f.split("\\.").length < 2)) {
                out.add(error(INVALID, "links[" + i + "]", "a link names two or more fields (table.column) of different datasets that mean the same: - { fields: [trade.counterparty, exposure.counterparty] }"));
                continue;
            }
            list.add(new Report.Link(fields));
        }
        return list;
    }

    /**
     * {@code layouts: { phone: { <page id>: { order, hide, span } }, tablet: { <page id>: { order, hide, span, wide } } }}; references are checked by the validator.
     * {@code wide} (visuals that take the whole width instead of half) belongs to a tablet.
     */
    private static Map<String, Map<String, Report.PhonePage>> layouts(JsonNode n, List<Problem> out) {
        Map<String, Map<String, Report.PhonePage>> all = new LinkedHashMap<>();
        if (n.isMissingNode() || n.isNull()) {
            return all;
        }
        if (!n.isObject()) {
            out.add(error(INVALID, "layouts", "layouts is { phone: { <page id>: { order, hide, span } }, tablet: { <page id>: { order, hide, span, wide } } }"));
            return all;
        }
        n.fields().forEachRemaining(dev -> {
            String dp = "layouts." + dev.getKey();
            if (!Report.DEVICES.contains(dev.getKey())) {
                out.add(new Problem("warning", INVALID, dp, "unknown layout '" + dev.getKey() + "' is ignored (phone, tablet)"));
                return;
            }
            if (!dev.getValue().isObject()) {
                out.add(error(INVALID, dp, dev.getKey() + " maps a page's id to { order, hide, span" + ("tablet".equals(dev.getKey()) ? ", wide" : "") + " }"));
                return;
            }
            Set<String> keys = "tablet".equals(dev.getKey()) ? Set.of("order", "hide", "span", "wide") : Set.of("order", "hide", "span");
            Map<String, Report.PhonePage> pages = new LinkedHashMap<>();
            dev.getValue().fields().forEachRemaining(pg -> {
                String pp = dp + "." + pg.getKey();
                JsonNode v = pg.getValue();
                if (!v.isObject()) {
                    out.add(error(INVALID, pp, "a page's " + dev.getKey() + " layout is { order: [visual ids], hide: [visual ids], span: { <visual id>: rows }" + ("tablet".equals(dev.getKey()) ? ", wide: [visual ids]" : "") + " }"));
                    return;
                }
                v.fieldNames().forEachRemaining(k -> {
                    if (!keys.contains(k)) {
                        out.add(new Problem("warning", INVALID, pp + "." + k, "unknown key '" + k + "' is ignored (" + String.join(", ", keys.stream().sorted().toList()) + ")"));
                    }
                });
                Map<String, Integer> span = new LinkedHashMap<>();
                JsonNode sp = v.path("span");
                if (sp.isObject()) {
                    sp.fields().forEachRemaining(e -> {
                        int rows = e.getValue().asInt(0);
                        if (!e.getValue().canConvertToInt() || rows < 1 || rows > 40) {
                            out.add(error(INVALID, pp + ".span." + e.getKey(), "span is the height in grid rows, 1 to 40"));
                        } else {
                            span.put(e.getKey(), rows);
                        }
                    });
                } else if (!sp.isMissingNode() && !sp.isNull()) {
                    out.add(error(INVALID, pp + ".span", "span maps a visual's id to its height in grid rows"));
                }
                pages.put(pg.getKey(), new Report.PhonePage(strings(v.path("order")), strings(v.path("hide")), span, strings(v.path("wide"))));
            });
            all.put(dev.getKey(), pages);
        });
        return all;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(JsonNode n, String path, List<Problem> out) {
        if (n.isMissingNode() || n.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!n.isObject()) {
            out.add(error(INVALID, path, "this is a mapping (key: value)"));
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(new ObjectMapper().convertValue(n, Map.class));
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> out.add(x.asText()));
        } else if (n.isTextual()) {
            out.add(n.asText());
        }
        return out;
    }

    private static String text(JsonNode n, String key, String fallback) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? fallback : v.asText();
    }

    static String slug(String title, String fallback) {
        String s = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? fallback : s.length() > 40 ? s.substring(0, 40) : s;
    }

    public static Problem error(String code, String path, String message) {
        return new Problem("error", code, path, message);
    }
}
