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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the person has done on a page, sent with a visual's query (RUPAKA.md section 8): slicer choices, the marks selected on other visuals, the
 * drill-down path of this visual, values carried by a drill-through, the business dates, and whether the visual is shown as its rows. The server
 * turns it into filters; nothing in it is trusted as a name (every field is checked against the dataset by the query compiler).
 *
 * <pre>
 * { "slicers": {"s1": ["USD"], "s2": {"from": 1, "to": 5}}, "selections": [{"visual": "v2", "field": "trades.desk", "values": ["DESK-FX"], "highlight": false}],
 *   "drill": ["2026"], "carry": [{"field": "counterparties.name", "values": ["Orbital"]}], "dates": {"from": "2026-10-01", "to": "2026-10-08"},
 *   "rows": {"field": "trades.desk", "values": ["DESK-FX"]}, "limit": 200 }
 * </pre>
 *
 * @param slicers by slicer visual id: a list of values (list, dropdown) or {@code {from, to}} (range)
 * @param drill the values clicked so far down the visual's hierarchy
 * @param rows non-null: show the rows behind the visual (and, when {@code field} is given, only those of the mark)
 */
public record ReportState(Map<String, JsonNode> slicers, List<Selection> selections, List<Object> drill, List<Carry> carry, String from, String to, Mark rows, Integer limit) {

    /** A mark selected on another visual: its field, its values, and whether other visuals highlight it (instead of filtering to it). */
    public record Selection(String visual, String field, List<Object> values, boolean highlight) {}

    /** A filter carried to a drill-through page (or applied by a bookmark): {@code in} unless {@code op} says another comparison. */
    public record Carry(String field, String op, List<Object> values) {}

    /** The mark whose rows are wanted. */
    public record Mark(String field, List<Object> values) {}

    public static final ReportState NONE = new ReportState(Map.of(), List.of(), List.of(), List.of(), null, null, null, null);

    /** Reads the JSON; a malformed state is DRS-9035. */
    public static ReportState of(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return NONE;
        }
        if (!n.isObject()) {
            throw bad("the state is an object: { slicers, selections, drill, carry, dates, rows, limit }");
        }
        Map<String, JsonNode> slicers = new LinkedHashMap<>();
        JsonNode s = n.path("slicers");
        if (s.isObject()) {
            s.fields().forEachRemaining(e -> slicers.put(e.getKey(), e.getValue()));
        } else if (!s.isMissingNode() && !s.isNull()) {
            throw bad("slicers maps a slicer's id to its values");
        }
        List<Selection> selections = new ArrayList<>();
        for (JsonNode x : list(n, "selections")) {
            if (!x.path("visual").isTextual() || !x.path("field").isTextual()) {
                throw bad("a selection is { visual, field, values }");
            }
            selections.add(new Selection(x.path("visual").asText(), x.path("field").asText(), values(x.path("values")), x.path("highlight").asBoolean(false)));
        }
        List<Carry> carry = new ArrayList<>();
        for (JsonNode x : list(n, "carry")) {
            if (!x.path("field").isTextual()) {
                throw bad("a carried value is { field, values }");
            }
            carry.add(new Carry(x.path("field").asText(), x.path("op").asText("in").toLowerCase(java.util.Locale.ROOT), values(x.path("values"))));
        }
        JsonNode dates = n.path("dates");
        Mark rows = null;
        if (n.has("rows") && !n.get("rows").isNull() && !n.get("rows").asText().equals("false")) {
            JsonNode r = n.get("rows");
            rows = r.isObject() && r.path("field").isTextual() ? new Mark(r.path("field").asText(), values(r.path("values"))) : new Mark(null, List.of());
        }
        Integer limit = n.has("limit") && n.get("limit").canConvertToInt() && n.get("limit").asInt() > 0 ? n.get("limit").asInt() : null;
        return new ReportState(slicers, selections, values(n.path("drill")), carry, dates.path("from").isTextual() ? dates.path("from").asText() : null,
                dates.path("to").isTextual() ? dates.path("to").asText() : null, rows, limit);
    }

    private static List<JsonNode> list(JsonNode n, String key) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode a = n.path(key);
        if (a.isArray()) {
            a.forEach(out::add);
        } else if (!a.isMissingNode() && !a.isNull()) {
            throw bad("'" + key + "' is a list");
        }
        return out;
    }

    static List<Object> values(JsonNode a) {
        List<Object> out = new ArrayList<>();
        if (a.isArray()) {
            a.forEach(v -> out.add(scalar(v)));
        } else if (!a.isMissingNode() && !a.isNull()) {
            out.add(scalar(a));
        }
        return out;
    }

    static Object scalar(JsonNode v) {
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
        throw bad("a value is a number, text or true/false");
    }

    static DrishtiException bad(String message) {
        return new DrishtiException(ErrorCode.REPORT_STATE, message);
    }
}
