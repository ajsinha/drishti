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
package com.ash.drishti.rachana.ui.migrate;

import com.ash.drishti.rachana.ui.BoardCheck;
import com.ash.drishti.rachana.ui.BoardCheckContext;
import com.ash.drishti.rachana.ui.ChipCatalog;
import com.ash.drishti.rachana.ui.ChipDoc;
import com.ash.drishti.rachana.ui.ChipRef;
import com.ash.drishti.rachana.ui.PinSpec;
import com.ash.drishti.rachana.ui.UiProblem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Converts a BI report into boards, one per page (docs/architecture/UI_BOARD.md 11.1, phase P5): each visual an instance of the seed chip
 * {@code bi-<type>} supplied by the report's model, its wells as the model query and the pin map, its place on the page as the desktop layout.
 * A visual with options the seed chip has no pin for ({@code format}, {@code filters}, {@code hierarchy}, ...) becomes a board-local copy of the
 * seed chip with those options written into its body. A visual type with no seed chip ({@code python}, {@code tile}, ...) fails the page, with
 * the reason. The page's cross-filters, slicers and drill are traces into the chips' {@code filter} pins, and a page is accepted only when
 * {@link BoardReport} paints its board back into exactly the page (12.4).
 */
public final class ReportConverter {

    private static final Set<String> CARRIED = Set.of("id", "type", "title", "at", "wells");
    /** The report keys a text, image, button or tile visual gives its content by (the chip's {@code content} pin). */
    private static final Set<String> CONTENT = Set.of("text", "src", "from", "label");

    private final ChipCatalog chips;

    public ReportConverter(ChipCatalog chips) {
        this.chips = chips;
    }

    /** One conversion per page of the report. */
    public List<Conversion> convert(String pack, JsonNode report) {
        String name = report.path("report").asText("?");
        String model = report.path("model").asText("").replaceAll("@.*", "").trim();
        String dataset = report.path("dataset").asText("").trim();
        String[] supply = !model.isEmpty() ? new String[] {"model", model} : !dataset.isEmpty() ? new String[] {"dataset", dataset} : null;
        List<Conversion> out = new ArrayList<>();
        for (JsonNode page : report.path("pages")) {
            String pid = page.path("id").asText("page");
            String screen = "report:" + name + "/" + pid;
            String board = PageConverter.name(name + "-" + pid);
            out.add(page(screen, pack, board, report, page, supply));    // no supply: a board page, its tiles ask their own reports
        }
        return out;
    }

    private Conversion page(String screen, String pack, String board, JsonNode report, JsonNode page, String[] source) {
        ObjectNode b = BoardSutra.JSON.createObjectNode();
        b.put("rachana", 1).put("board", board).put("title", report.path("title").asText(board) + " · " + page.path("title").asText(""));
        b.put("description", "Converted from the page '" + page.path("id").asText() + "' of the BI report " + report.path("report").asText()
                + " (UI_BOARD.md 11).");
        ObjectNode instances = b.putObject("chips");
        ArrayNode locals = BoardSutra.JSON.createArrayNode();
        ArrayNode areas = BoardSutra.JSON.createArrayNode();
        List<String> seeds = new ArrayList<>();
        List<Conversion.Local> made = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (JsonNode v : page.path("visuals")) {
            String type = v.path("type").asText("?");
            String base = PageConverter.name(v.path("id").asText(type));
            String inst = base;
            for (int n = 2; used.contains(inst); n++) {
                inst = base + "-" + n;
            }
            used.add(inst);
            ChipDoc chip = chips.find(new ChipRef("bi-" + type, 1)).map(ChipCatalog.Entry::doc).orElse(null);
            if (chip == null) {
                return PageConverter.failed(screen, pack, null, board, "visual '" + inst + "': no chip for the BI visual type '" + type + "'");
            }
            PinSpec rows = chip.inPins().get("rows");
            ObjectNode in = instances.putObject(inst);
            in.put("use", chip.ref().toString());
            if (v.hasNonNull("title")) {
                in.put("title", v.path("title").asText());
            }
            if (source == null && v.path("wells").size() > 0) {
                return PageConverter.failed(screen, pack, null, board, "visual '" + inst + "' reads data, but the report names no model or dataset");
            }
            ObjectNode query = BoardSutra.JSON.createObjectNode();
            if (source != null && rows != null) {
                ObjectNode supply = in.putObject("supply");
                supply.put(source[0], source[1]);
                supply.set("query", query);
            }
            ArrayNode measures = query.putArray("measures");
            ArrayNode dims = query.putArray("dimensions");
            ObjectNode map = rows == null ? BoardSutra.JSON.createObjectNode() : in.putObject("map");
            Optional<String> misfit = Optional.empty();
            var wells = v.path("wells").fields();
            while (wells.hasNext()) {
                var w = wells.next();
                PinSpec.ColumnSpec col = rows == null || rows.shape() == null ? null : rows.shape().get(w.getKey());
                List<String> fields = new ArrayList<>();
                w.getValue().forEach(f -> fields.add(f.asText()));
                if (w.getValue().isTextual()) {
                    fields.add(w.getValue().asText());
                }
                if (col == null || (!col.many() && fields.size() > 1)) {
                    misfit = Optional.of("the well '" + w.getKey() + "' is not a column of " + chip.ref() + "'s rows");
                    continue;
                }
                for (String f : fields) {
                    (f.contains(".") ? dims : measures).add(f);
                }
                if (col.many()) {
                    ArrayNode a = map.putArray("rows." + w.getKey());
                    fields.forEach(a::add);
                } else if (!fields.isEmpty()) {
                    map.put("rows." + w.getKey(), fields.get(0));
                }
            }
            List<String> extra = new ArrayList<>();
            ObjectNode controls = BoardSutra.JSON.createObjectNode();
            v.fieldNames().forEachRemaining(k -> {
                if (CARRIED.contains(k)) {
                    return;
                }
                String pin = CONTENT.contains(k) && chip.inPins().containsKey("content") ? "content" : k;
                PinSpec ps = chip.inPins().get(pin);
                if (ps != null && ps.role() == PinSpec.PinRole.CONTROL && !controls.has(pin)) {
                    controls.set(pin, v.get(k).deepCopy());       // a setting of the report's visual: a constant of the chip's control pin
                } else {
                    extra.add(k);
                }
            });
            if (!controls.isEmpty()) {
                in.set("controls", controls);
            }
            if (misfit.isPresent() || !extra.isEmpty()) {
                String local = "local-" + inst;
                locals.add(localCopy(chip, local, v, extra));
                in.put("use", local + "@1");
                made.add(new Conversion.Local(local, inst, misfit.orElse("the seed chip " + chip.ref() + " has no pin for " + String.join(", ", extra))));
            } else {
                seeds.add(chip.ref().toString());
            }
            JsonNode at = v.path("at");
            if (at.isObject()) {
                ObjectNode a = areas.addObject();
                a.put("chip", inst);
                a.putArray("at").add(at.path("x").asInt() + 1).add(at.path("y").asInt() + 1);
                a.putArray("span").add(Math.max(1, at.path("w").asInt(1))).add(Math.max(1, at.path("h").asInt(1)));
            }
        }
        if (!locals.isEmpty()) {
            b.set("local-chips", locals);
        }
        int traces = crossFilters(b, instances);
        ObjectNode layout = b.putObject("layout");
        if (!areas.isEmpty()) {
            ObjectNode d = layout.putObject("desktop");
            d.put("columns", 12);
            d.set("areas", areas);
        }
        ArrayNode order = layout.putObject("phone").putArray("order");
        used.forEach(order::add);
        List<UiProblem> problems = BoardCheck.check(b.toString(), BoardCheckContext.personal(chips));
        Optional<String> error = problems.stream().filter(UiProblem::error).findFirst()
                .map(e -> "board check " + e.rule() + " at " + e.path() + ": " + e.message());
        if (error.isEmpty()) {                                           // accepted only when the board serves exactly the page it came from
            error = BoardReport.difference(page, BoardReport.paint(b, page, r -> chips.find(r).map(ChipCatalog.Entry::doc)))
                    .map(d -> "does not paint back: " + d);
        }
        Conversion.Status st = error.isPresent() ? Conversion.Status.FAILS : made.isEmpty() ? Conversion.Status.CLEAN : Conversion.Status.LOCAL;
        return new Conversion(screen, pack, null, board, b, st, seeds, made, 0, traces, error.orElse(null), problems);
    }

    /**
     * The page's cross-filters, slicers and drill as traces: every chip that emits a filter (a slicer's {@code filter-out}, a clicked or
     * drilled mark's {@code selected}) into the {@code filter} pin of every other chip on the page, as the BI engine filters a page.
     */
    private int crossFilters(ObjectNode b, ObjectNode instances) {
        List<String[]> from = new ArrayList<>();
        List<String> to = new ArrayList<>();
        instances.fields().forEachRemaining(e -> {
            ChipDoc c = ChipRef.parse(e.getValue().path("use").asText("")).flatMap(chips::find).map(ChipCatalog.Entry::doc).orElse(null);
            if (c == null) {
                return;                                                  // a board-local copy: no traces of its own
            }
            for (String out : List.of("filter-out", "selected")) {
                if (c.outPins().containsKey(out)) {
                    from.add(new String[] {e.getKey(), out});
                }
            }
            if (c.inPins().containsKey("filter")) {
                to.add(e.getKey());
            }
        });
        ArrayNode traces = BoardSutra.JSON.createArrayNode();
        for (String[] f : from) {
            for (String t : to) {
                if (!t.equals(f[0])) {
                    traces.addObject().put("from", f[0] + "." + f[1]).put("to", t + ".filter");
                }
            }
        }
        if (!traces.isEmpty()) {
            b.set("traces", traces);
        }
        return traces.size();
    }

    /** A board-local copy of a seed chip with the visual's own options written into its body. */
    private static ObjectNode localCopy(ChipDoc seed, String name, JsonNode visual, List<String> extra) {
        ObjectNode c = BoardSutra.JSON.createObjectNode();
        c.put("rachana", 1).put("chip", name).put("version", 1).put("title", visual.path("title").asText(name)).put("category", "local");
        ObjectNode man = c.putObject("manual");
        man.put("purpose", "The visual '" + visual.path("id").asText() + "' as the report drew it: " + seed.ref() + " with its own options.");
        ObjectNode mp = man.putObject("pins");
        seed.inPins().keySet().forEach(p -> mp.put(p, "As " + seed.ref() + "'s."));
        seed.outPins().keySet().forEach(p -> mp.put(p, "Out. As " + seed.ref() + "'s."));
        man.put("behaviour", "As " + seed.ref() + ".");
        man.putArray("examples");
        ObjectNode pins = c.putObject("pins");
        pins.set("in", pinsOf(seed.inPins().values()));
        if (!seed.outPins().isEmpty()) {
            pins.set("out", pinsOf(seed.outPins().values()));
        }
        JsonNode body = seed.body().deepCopy();
        JsonNode panel = body.path("panels").path(0);
        if (panel instanceof ObjectNode o) {
            extra.forEach(k -> o.set(k, visual.get(k).deepCopy()));
        }
        c.set("body", body);
        return c;
    }

    private static ObjectNode pinsOf(java.util.Collection<PinSpec> pins) {
        ObjectNode o = BoardSutra.JSON.createObjectNode();
        for (PinSpec p : pins) {
            ObjectNode n = o.putObject(p.name());
            if (p.in()) {
                n.put("role", p.role().name().toLowerCase(java.util.Locale.ROOT));
            }
            n.put("type", p.type().type().yamlName());
            if (p.required()) {
                n.put("required", true);
            }
            if (p.shapeOf() != null) {
                n.put("shape-of", p.shapeOf());
            }
            if (p.shape() != null && !p.shape().isEmpty()) {
                ObjectNode s = n.putObject("shape");
                p.shape().forEach((k, col) -> {
                    ObjectNode cn = s.putObject(k);
                    cn.put("type", col.type().type().yamlName());
                    if (col.required()) {
                        cn.put("required", true);
                    }
                    if (col.many()) {
                        cn.put("many", true);
                    }
                });
            }
        }
        return o;
    }
}
