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
package com.ash.drishti.server.pivot;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.search.SearchPivot;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Pivot tab of search results and pick lists, computed on the server over every entity the search matches on the
 * business date (not only the page shown): {@code POST /api/v1/search/pivot/TRD} with the search and an arrangement
 * returns only the aggregated cells; {@code /drill} the entities behind one cell, a page at a time; {@code /values} a
 * field's values for a filter. Offered only for kinds whose pack says {@code pivot:}. The caller must be able to open the
 * kind; fields their role may not see are masked as in a search. Fields no source keeps as columns are refused with the
 * reason unless the body says {@code "documents": true}.
 */
@RestController
@RequestMapping("/api/v1/search/pivot")
public class SearchPivotController {

    static final List<String> ARRANGEMENT = List.of("rows", "columns", "values", "filters", "heat", "chart");

    private final SearchPivot pivot;
    private final Entitlements entitlements;
    private final ObjectMapper json = new ObjectMapper();

    public SearchPivotController(SearchPivot pivot, Entitlements entitlements) {
        this.pivot = pivot;
        this.entitlements = entitlements;
    }

    /**
     * The cube: {@code {"q": "TRD where desk = 'DESK-RATES'", "rows": ["book"], "columns": ["currency"],
     * "values": [{"field": "mtm", "agg": "sum"}], "filters": [{"field": "status", "values": ["Live"]}]}}.
     */
    @PostMapping("/{kind}")
    public Map<String, Object> pivot(@PathVariable String kind, @RequestBody(required = false) JsonNode body, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String k = open(kind, p);
        JsonNode b = body == null ? json.createObjectNode() : body;
        return pivot.pivot(k, text(b, "q"), arrangement(b), b.path("documents").asBoolean(false), asOf, data -> entitlements.redact(p, data));
    }

    /** The entities behind a cell: the same body plus {@code "cell": {"rows": [...], "columns": [...]}, "offset": 0, "size": 50}. */
    @PostMapping("/{kind}/drill")
    public Map<String, Object> drill(@PathVariable String kind, @RequestBody(required = false) JsonNode body, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String k = open(kind, p);
        JsonNode b = body == null ? json.createObjectNode() : body;
        JsonNode cell = b.path("cell");
        return pivot.drill(k, text(b, "q"), arrangement(b), keys(cell.path("rows")), keys(cell.path("columns")), b.path("offset").asInt(0),
                b.path("size").asInt(50), b.path("documents").asBoolean(false), asOf, data -> entitlements.redact(p, data));
    }

    /** A field's values among the matches, with counts: {@code {"q": "...", "field": "currency"}}. */
    @PostMapping("/{kind}/values")
    public Map<String, Object> values(@PathVariable String kind, @RequestBody(required = false) JsonNode body, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String k = open(kind, p);
        JsonNode b = body == null ? json.createObjectNode() : body;
        String field = text(b, "field");
        if (field == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "name the field whose values you want: {\"field\": \"currency\"}");
        }
        return pivot.values(k, text(b, "q"), field, b.path("documents").asBoolean(false), asOf, data -> entitlements.redact(p, data));
    }

    private String open(String kind, Principal p) {
        String k = pivot.kindOf(kind);
        entitlements.requireOpen(p, k);
        if (pivot.offered(k).isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, k + " search results do not offer a pivot (its pack opts a kind in with pivot: in pack.yaml)");
        }
        return k;
    }

    /** The arrangement's keys of the body, as plain maps and lists. */
    @SuppressWarnings("unchecked")
    Map<String, Object> arrangement(JsonNode body) {
        Map<String, Object> all = json.convertValue(body, Map.class);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : ARRANGEMENT) {
            if (all.get(k) != null) {
                out.put(k, all.get(k));
            }
        }
        return out;
    }

    private static List<String> keys(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> out.add(x.isNull() ? com.ash.drishti.engine.pivot.PivotCube.BLANK : x.asText()));
        }
        return out;
    }

    private static String text(JsonNode b, String key) {
        JsonNode n = b.path(key);
        return n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asText();
    }
}
