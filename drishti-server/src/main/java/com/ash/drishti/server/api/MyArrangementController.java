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
package com.ash.drishti.server.api;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A reader's own arrangement of a screen laid out with {@code layout:} (SUTRA_NEXT.md 13), per user and Sutra, in the
 * {@link PreferenceStore}: where the split's divider sits ({@code columns}), which sections and panels are folded ({@code collapsed}),
 * and, where the Sutra says {@code user-can-arrange: true}, each panel's area, width and order ({@code panels}). It never changes the
 * shared Sutra; {@code DELETE} is "Reset layout". Checked for shape and size here; the console applies only what the Sutra allows.
 */
@RestController
@RequestMapping("/api/v1/me/arrange")
public class MyArrangementController {

    static final String NS = "screen-arrange";
    private static final Pattern SUTRA = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9][\\w-]{0,63}$");
    private static final int MAX_PANELS = 100;

    private final PreferenceStore store;
    private final ObjectMapper json = new ObjectMapper();

    public MyArrangementController(PreferenceStore store) {
        this.store = store;
    }

    @GetMapping("/{sutra}")
    public ObjectNode get(@PathVariable String sutra, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        name(sutra);
        return store.get(p.user(), NS, sutra).filter(JsonNode::isObject).map(n -> ((ObjectNode) n).deepCopy()).orElseGet(json::createObjectNode);
    }

    @PutMapping("/{sutra}")
    public ObjectNode put(@PathVariable String sutra, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        name(sutra);
        ObjectNode clean = clean(body);
        store.put(p.user(), NS, sutra, clean);
        return clean;
    }

    @DeleteMapping("/{sutra}")
    public ObjectNode reset(@PathVariable String sutra, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        name(sutra);
        store.put(p.user(), NS, sutra, json.createObjectNode());
        return json.createObjectNode();
    }

    private static void name(String sutra) {
        if (!SUTRA.matcher(sutra).matches()) {
            throw bad("a screen is named by its Sutra (a-z, 0-9, -)");
        }
    }

    /** Only the known parts, each checked: {@code columns} (widths of 0.5 to 11.5 grid columns), {@code collapsed}, {@code panels}. */
    ObjectNode clean(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw bad("an arrangement is an object: columns, collapsed, panels");
        }
        ObjectNode out = json.createObjectNode();
        JsonNode cols = body.path("columns");
        if (cols.isArray()) {
            ArrayNode a = out.putArray("columns");
            if (cols.size() > 12) {
                throw bad("at most 12 column widths");
            }
            for (JsonNode c : cols) {
                if (!c.isNumber() || c.asDouble() < 0.5 || c.asDouble() > 11.5) {
                    throw bad("a column width is a number of grid columns, 0.5 to 11.5");
                }
                a.add(Math.round(c.asDouble() * 100) / 100.0);
            }
        }
        JsonNode folded = body.path("collapsed");
        if (folded.isObject()) {
            ObjectNode o = out.putObject("collapsed");
            if (folded.size() > MAX_PANELS) {
                throw bad("too many folded parts");
            }
            folded.fields().forEachRemaining(e -> {
                if (!ID.matcher(e.getKey()).matches() || !e.getValue().isBoolean()) {
                    throw bad("collapsed maps a section or panel id to true or false");
                }
                o.put(e.getKey(), e.getValue().asBoolean());
            });
        }
        JsonNode panels = body.path("panels");
        if (panels.isArray()) {
            if (panels.size() > MAX_PANELS) {
                throw bad("at most " + MAX_PANELS + " panels");
            }
            ArrayNode a = out.putArray("panels");
            for (JsonNode x : panels) {
                String id = x.path("id").asText("");
                if (!ID.matcher(id).matches()) {
                    throw bad("each panel is { id, area, span, order }");
                }
                ObjectNode q = a.addObject().put("id", id);
                String area = x.path("area").asText("");
                if (!area.isEmpty()) {
                    if (!SUTRA.matcher(area).matches()) {
                        throw bad("an area is a name of the screen's layout");
                    }
                    q.put("area", area);
                }
                if (x.has("span")) {
                    int span = x.path("span").asInt(0);
                    if (span < 1 || span > 12) {
                        throw bad("a width is 1 to 12 grid columns");
                    }
                    q.put("span", span);
                }
                if (x.has("order")) {
                    int order = x.path("order").asInt(-1);
                    if (order < 0 || order > 999) {
                        throw bad("an order is 0 to 999");
                    }
                    q.put("order", order);
                }
            }
        }
        return out;
    }

    private static DrishtiException bad(String why) {
        return new DrishtiException(ErrorCode.BAD_REQUEST, why);
    }
}
