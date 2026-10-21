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
 * the reason. Cross-filtering is not drawn as traces in P5 (the {@code bi-*} seed chips have no filter in pin); see the as-built note.
 */
public final class ReportConverter {

    private static final Set<String> CARRIED = Set.of("id", "type", "title", "at", "wells");

    private final ChipCatalog chips;

    public ReportConverter(ChipCatalog chips) {
        this.chips = chips;
    }

    /** One conversion per page of the report. */
    public List<Conversion> convert(String pack, JsonNode report) {
        String name = report.path("report").asText("?");
        String model = report.path("model").asText("").replaceAll("@.*", "").trim();
        List<Conversion> out = new ArrayList<>();
        for (JsonNode page : report.path("pages")) {
            String pid = page.path("id").asText("page");
            String screen = "report:" + name + "/" + pid;
            String board = PageConverter.name(name + "-" + pid);
            if (model.isEmpty()) {
                out.add(PageConverter.failed(screen, pack, null, board, "the report names no model"));
                continue;
            }
            out.add(page(screen, pack, board, report, page, model));
        }
        return out;
    }

    private Conversion page(String screen, String pack, String board, JsonNode report, JsonNode page, String model) {
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
            ObjectNode supply = in.putObject("supply");
            supply.put("model", model);
            ObjectNode query = supply.putObject("query");
            ArrayNode measures = query.putArray("measures");
            ArrayNode dims = query.putArray("dimensions");
            ObjectNode map = in.putObject("map");
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
            v.fieldNames().forEachRemaining(k -> {
                if (!CARRIED.contains(k)) {
                    extra.add(k);
                }
            });
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
        ObjectNode layout = b.putObject("layout");
        if (!areas.isEmpty()) {
            ObjectNode d = layout.putObject("desktop");
            d.put("columns", 12);
            d.set("areas", areas);
        }
        ArrayNode order = layout.putObject("phone").putArray("order");
        used.forEach(order::add);
        List<UiProblem> problems = BoardCheck.check(b.toString(), BoardCheckContext.personal(chips));
        Optional<UiProblem> error = problems.stream().filter(UiProblem::error).findFirst();
        Conversion.Status st = error.isPresent() ? Conversion.Status.FAILS : made.isEmpty() ? Conversion.Status.CLEAN : Conversion.Status.LOCAL;
        return new Conversion(screen, pack, null, board, b, st, seeds, made, 0, 0,
                error.map(e -> "board check " + e.rule() + " at " + e.path() + ": " + e.message()).orElse(null), problems);
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
