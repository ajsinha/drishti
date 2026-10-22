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

import com.ash.drishti.rachana.ui.ChipDoc;
import com.ash.drishti.rachana.ui.ChipRef;
import com.ash.drishti.rachana.ui.UiDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A converted report page's board painted back into the report page it serves (docs/architecture/UI_BOARD.md 12.4): each chip instance a
 * visual (its type from the chip's body, its wells from the instance's map, its settings from the constants, its place from the desktop
 * layout). The page's own frame ({@code id}, {@code title}, {@code layout}, {@code drillthrough}) is kept from the report: a board has no
 * key for it. The traces (cross-filters, slicers, drill) are not painted: they draw what the BI engine does by default on a page (every
 * visual filters the others), and {@link ReportConverter} draws exactly that.
 */
public final class BoardReport {

    /** The report key a {@code content} pin is written back as, per visual type. */
    static final Map<String, String> CONTENT_KEY = Map.of("text", "text", "image", "src", "button", "label", "tile", "from");
    private static final Set<String> TEMPLATE_KEYS = Set.of("id", "kind", "type", "title", "rows", "wells", "on-select", "on-click", "emits", "content");

    private BoardReport() {}

    /**
     * The page {@code frame} with its visuals painted from {@code board}.
     *
     * @param chips the Chip Store (a board-local chip is found in the board itself)
     */
    public static ObjectNode paint(JsonNode board, JsonNode frame, Function<ChipRef, Optional<ChipDoc>> chips) {
        ObjectNode page = frame.deepCopy();
        ArrayNode visuals = page.putArray("visuals");
        var it = board.path("chips").fields();
        while (it.hasNext()) {
            var e = it.next();
            String inst = e.getKey();
            JsonNode in = e.getValue();
            JsonNode body = chipBody(board, in.path("use").asText(""), chips);
            JsonNode panel = body.path("panels").path(0);
            String type = panel.path("type").asText("?");
            ObjectNode v = visuals.addObject();
            v.put("id", inst);
            v.put("type", type);
            if (in.hasNonNull("title")) {
                v.set("title", in.get("title").deepCopy());
            }
            for (JsonNode a : board.path("layout").path("desktop").path("areas")) {
                if (inst.equals(a.path("chip").asText())) {
                    ObjectNode at = v.putObject("at");
                    at.put("x", a.path("at").path(0).asInt() - 1).put("y", a.path("at").path(1).asInt() - 1);
                    at.put("w", a.path("span").path(0).asInt()).put("h", a.path("span").path(1).asInt());
                }
            }
            ObjectNode wells = BoardSutra.JSON.createObjectNode();
            in.path("map").fields().forEachRemaining(m -> {
                if (m.getKey().startsWith("rows.")) {
                    ArrayNode w = wells.putArray(m.getKey().substring("rows.".length()));
                    if (m.getValue().isArray()) {
                        m.getValue().forEach(w::add);
                    } else {
                        w.add(m.getValue().asText());
                    }
                }
            });
            if (!wells.isEmpty()) {
                v.set("wells", wells);
            }
            panel.fields().forEachRemaining(f -> {                      // a board-local copy: the visual's own options are in its body
                if (!TEMPLATE_KEYS.contains(f.getKey()) && !(f.getValue().isTextual() && f.getValue().asText().startsWith("%{"))) {
                    v.set(f.getKey(), f.getValue().deepCopy());
                }
            });
            in.path("controls").fields().forEachRemaining(c -> v.set("content".equals(c.getKey()) ? CONTENT_KEY.getOrDefault(type, "text") : c.getKey(),
                    c.getValue().deepCopy()));
        }
        return page;
    }

    private static JsonNode chipBody(JsonNode board, String use, Function<ChipRef, Optional<ChipDoc>> chips) {
        String name = use.replaceAll("@.*", "");
        for (JsonNode l : board.path("local-chips")) {
            if (name.equals(l.path("chip").asText())) {
                return l.path("body");
            }
        }
        return ChipRef.parse(use).flatMap(chips).map(ChipDoc::body).orElse(BoardSutra.JSON.createObjectNode());
    }

    /** A report visual in a canonical form for comparison: every well a list, empty wells and empty well maps dropped. */
    static JsonNode canonical(JsonNode visual) {
        ObjectNode v = visual.deepCopy();
        JsonNode wells = v.path("wells");
        if (wells.isObject()) {
            ObjectNode w = BoardSutra.JSON.createObjectNode();
            wells.fields().forEachRemaining(f -> {
                ArrayNode a = BoardSutra.JSON.createArrayNode();
                if (f.getValue().isArray()) {
                    f.getValue().forEach(a::add);
                } else if (!f.getValue().isNull()) {
                    a.add(f.getValue().asText());
                }
                if (!a.isEmpty()) {
                    w.set(f.getKey(), a);
                }
            });
            if (w.isEmpty()) {
                v.remove("wells");
            } else {
                v.set("wells", w);
            }
        }
        return v;
    }

    /** The first visual of {@code original} that {@code painted} does not give back exactly, or empty when the page paints back. */
    static Optional<String> difference(JsonNode original, JsonNode painted) {
        JsonNode a = original.path("visuals");
        JsonNode b = painted.path("visuals");
        if (a.size() != b.size()) {
            return Optional.of(b.size() + " visuals painted, " + a.size() + " in the report");
        }
        for (int i = 0; i < a.size(); i++) {
            JsonNode x = canonical(a.get(i));
            JsonNode y = canonical(b.get(i));
            if (!x.equals(y)) {
                return Optional.of("visual '" + a.get(i).path("id").asText() + "' paints back as " + y + ", not " + x);
            }
        }
        return Optional.empty();
    }

    /** Reads a report's text (YAML or JSON) as a tree. */
    public static JsonNode tree(String text) {
        return UiDocuments.tree(text);
    }
}
