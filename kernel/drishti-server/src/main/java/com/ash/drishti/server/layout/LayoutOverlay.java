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
package com.ash.drishti.server.layout;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.rachana.SutraLayoutEditor;
import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A personal layout: how one user arranges the panels of one Sutra, kept apart from the Sutra. Each entry names a
 * panel of the Sutra and says its column ({@code main} or {@code right}), width ({@code span}, 1-12 columns; none: the
 * whole column), height ({@code height}, 1-24 grid rows; none: as tall as its content) and whether it is hidden; the
 * order of the entries is the order of the panels. Written only with panel ids the Sutra has. Read against the Sutra
 * as it is now: entries for panels the Sutra no longer has are left out, and panels it has gained are added at the
 * end of their column as the Sutra places them ({@code added: true}). Stateless.
 */
public final class LayoutOverlay {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LayoutOverlay() {}

    /** The stored form of {@code body} for {@code sutra}: validated, sizes normalised (a span of 12 is the default). */
    public static ObjectNode validate(JsonNode body, Sutra sutra, String kind) {
        JsonNode panels = body.path("panels");
        if (!panels.isArray() || panels.isEmpty()) {
            throw bad("a layout lists the panels it arranges: {\"panels\": [{\"id\": \"legs\", \"area\": \"main\", \"span\": 8}]}");
        }
        Map<String, Panel> known = new LinkedHashMap<>();
        sutra.panels().forEach(p -> known.put(p.id(), p));
        if (panels.size() > known.size()) {
            throw bad(sutra.name() + " has " + known.size() + " panels; the layout lists " + panels.size());
        }
        ObjectNode out = JSON.createObjectNode().put("sutra", sutra.name()).put("kind", kind).put("basedOn", sutra.version())
                .put("updatedAt", Instant.now().toString());
        ArrayNode list = out.putArray("panels");
        Set<String> seen = new HashSet<>();
        for (JsonNode e : panels) {
            String id = e.path("id").asText("");
            Panel p = known.get(id);
            if (p == null) {
                throw bad("'" + id + "' is not a panel of " + sutra.name() + " (its panels: " + String.join(", ", known.keySet()) + ")");
            }
            if (!seen.add(id)) {
                throw bad("panel '" + id + "' is listed twice");
            }
            ObjectNode o = list.addObject().put("id", id);
            String area = e.path("area").isMissingNode() || e.path("area").isNull() ? lower(p.area()) : e.path("area").asText("");
            if (!area.equals("main") && !area.equals("right")) {
                throw bad("area of '" + id + "' must be main or right, not '" + area + "'");
            }
            o.put("area", area);
            Integer span = size(e, Panel.SPAN, Panel.MAX_SPAN, id);
            Integer height = size(e, Panel.HEIGHT, Panel.MAX_HEIGHT, id);
            if (span != null && span < Panel.MAX_SPAN) {
                o.put(Panel.SPAN, span);
            }
            if (height != null) {
                o.put(Panel.HEIGHT, height);
            }
            if (e.path("hidden").isBoolean() && e.path("hidden").asBoolean()) {
                o.put("hidden", true);
            } else if (!e.path("hidden").isMissingNode() && !e.path("hidden").isNull() && !e.path("hidden").isBoolean()) {
                throw bad("hidden of '" + id + "' must be true or false");
            }
        }
        if (seen.size() == known.size() && list.findValues("hidden").size() == known.size()) {
            throw bad("a layout must leave at least one panel shown");
        }
        return out;
    }

    private static Integer size(JsonNode e, String key, int max, String id) {
        JsonNode n = e.path(key);
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isIntegralNumber() || n.asInt() < 1 || n.asInt() > max) {
            throw bad(key + " of '" + id + "' must be a whole number from 1 to " + max + ", not " + n);
        }
        return n.asInt();
    }

    /**
     * The stored layout read against the Sutra as it is now: unknown panels dropped, new ones added (each marked
     * {@code added}) after the panels of their column, as the Sutra places and sizes them.
     */
    public static ObjectNode resolve(JsonNode stored, Sutra sutra) {
        Map<String, Panel> known = new LinkedHashMap<>();
        sutra.panels().forEach(p -> known.put(p.id(), p));
        ObjectNode out = JSON.createObjectNode().put("sutra", sutra.name()).put("kind", stored.path("kind").asText(sutra.match().kind()))
                .put("sutraVersion", sutra.version()).put("basedOn", stored.path("basedOn").asInt(sutra.version()))
                .put("updatedAt", stored.path("updatedAt").asText(""));
        ArrayNode list = out.putArray("panels");
        Set<String> listed = new HashSet<>();
        List<String> dropped = new ArrayList<>();
        for (JsonNode e : stored.path("panels")) {
            String id = e.path("id").asText("");
            if (!known.containsKey(id)) {
                dropped.add(id);
            } else if (listed.add(id)) {
                list.add(e.deepCopy());
            }
        }
        for (Panel p : sutra.panels()) {
            if (!listed.contains(p.id())) {
                ObjectNode o = list.addObject().put("id", p.id()).put("area", lower(p.area())).put("added", true);
                p.span().filter(s -> s < Panel.MAX_SPAN).ifPresent(s -> o.put(Panel.SPAN, s));
                p.height().ifPresent(h -> o.put(Panel.HEIGHT, h));
            }
        }
        ArrayNode gone = out.putArray("dropped");
        dropped.forEach(gone::add);
        return out;
    }

    /** The resolved layout as the editor's placements, in order. */
    public static List<SutraLayoutEditor.Placement> placements(JsonNode resolved) {
        List<SutraLayoutEditor.Placement> out = new ArrayList<>();
        for (JsonNode e : resolved.path("panels")) {
            out.add(new SutraLayoutEditor.Placement(e.path("id").asText(), "right".equals(e.path("area").asText()) ? Area.RIGHT : Area.MAIN,
                    e.hasNonNull(Panel.SPAN) ? e.get(Panel.SPAN).asInt() : null, e.hasNonNull(Panel.HEIGHT) ? e.get(Panel.HEIGHT).asInt() : null,
                    e.path("hidden").asBoolean(false)));
        }
        return out;
    }

    private static String lower(Area a) {
        return a.name().toLowerCase(Locale.ROOT);
    }

    private static DrishtiException bad(String message) {
        return new DrishtiException(ErrorCode.BAD_REQUEST, message);
    }
}
