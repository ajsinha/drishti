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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A visual query (RUPAKA.md section 7): fields to group by, measures to compute, filters, business dates and a sort. It names things of the
 * dataset only; the compiler checks every name against the model and binds every value as a parameter.
 *
 * <pre>
 * { "fields": ["trades.desk", "trades.tradeDate.month"], "measures": ["Notional", "MTM"],
 *   "filters": [{"field": "trades.currency", "op": "in", "values": ["USD", "EUR"]}],
 *   "dates": {"from": "2026-10-01", "to": "2026-10-09"}, "orderBy": [{"by": "MTM", "desc": true}], "limit": 100, "layer": "auto" }
 * </pre>
 *
 * @param dates {@code from} and {@code to} (inclusive), or null for the latest business date
 * @param layer {@code auto} (the planner picks rollup, columns or the source), or force {@code columns} / {@code rollup} / {@code direct}
 * @param limit rows wanted, or null for the server's maximum
 * @param detail one row per row of the base (fact) table, duplicates kept (a model query's {@code shape: rows}, BI_MODEL_SPEC.md 5.1); false: a query
 *        with fields and no measures answers each distinct combination once
 */
public record QueryRequest(List<String> fields, List<String> measures, List<Filter> filters, LocalDate from, LocalDate to, List<Sort> orderBy,
        Integer limit, String layer, boolean detail) {

    /** A query that groups (no measures: each distinct combination of the fields once). */
    public QueryRequest(List<String> fields, List<String> measures, List<Filter> filters, LocalDate from, LocalDate to, List<Sort> orderBy, Integer limit,
            String layer) {
        this(fields, measures, filters, from, to, orderBy, limit, layer, false);
    }

    /** The comparisons a filter may make. */
    public static final Set<String> OPS = Set.of("eq", "ne", "in", "not-in", "lt", "le", "gt", "ge", "between", "contains", "starts-with", "is-null", "not-null");

    public record Filter(String field, String op, List<Object> values) {}

    public record Sort(String by, boolean desc) {}

    public QueryRequest {
        fields = List.copyOf(fields);
        measures = List.copyOf(measures);
        filters = List.copyOf(filters);
        orderBy = List.copyOf(orderBy);
        layer = layer == null || layer.isBlank() ? "auto" : layer;
    }

    /** Reads the JSON body; anything unexpected is DRS-9020 naming the key. */
    public static QueryRequest of(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw invalid("the query is a JSON object: { fields, measures, filters, dates, orderBy, limit }");
        }
        List<String> fields = strings(body, "fields");
        List<String> measures = strings(body, "measures");
        List<Filter> filters = new ArrayList<>();
        for (JsonNode f : array(body, "filters")) {
            String op = f.path("op").asText("eq").toLowerCase(Locale.ROOT);
            if (!OPS.contains(op) || !f.path("field").isTextual()) {
                throw invalid("a filter is { field, op, values }; op is one of " + new java.util.TreeSet<>(OPS));
            }
            List<Object> values = new ArrayList<>();
            JsonNode vs = f.has("values") ? f.get("values") : f.get("value");
            if (vs != null && vs.isArray()) {
                vs.forEach(v -> values.add(scalar(v)));
            } else if (vs != null && !vs.isNull()) {
                values.add(scalar(vs));
            }
            filters.add(new Filter(f.path("field").asText(), op, values));
        }
        List<Sort> sorts = new ArrayList<>();
        for (JsonNode s : array(body, "orderBy")) {
            sorts.add(s.isTextual() ? new Sort(s.asText(), false) : new Sort(s.path("by").asText(), s.path("desc").asBoolean(false)));
        }
        JsonNode dates = body.path("dates");
        LocalDate from = date(dates.has("from") ? dates.path("from") : body.path("date"));
        LocalDate to = date(dates.path("to"));
        if (from == null && to != null) {
            from = to;
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw invalid("dates.to is before dates.from");
        }
        Integer limit = body.has("limit") && body.get("limit").canConvertToInt() ? body.get("limit").asInt() : null;
        if (limit != null && limit < 1) {
            throw invalid("limit is a positive number");
        }
        if (fields.isEmpty() && measures.isEmpty()) {
            throw invalid("a query needs at least one field or measure");
        }
        return new QueryRequest(fields, measures, filters, from, to, sorts, limit, body.path("layer").asText("auto"));
    }

    private static List<JsonNode> array(JsonNode body, String key) {
        JsonNode n = body.path(key);
        if (n.isMissingNode() || n.isNull()) {
            return List.of();
        }
        if (!n.isArray()) {
            throw invalid("'" + key + "' is a list");
        }
        List<JsonNode> out = new ArrayList<>();
        n.forEach(out::add);
        return out;
    }

    private static List<String> strings(JsonNode body, String key) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array(body, key)) {
            if (!n.isTextual()) {
                throw invalid("'" + key + "' is a list of names");
            }
            out.add(n.asText());
        }
        return out;
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
        throw invalid("a filter value is a number, text or true/false");
    }

    private static LocalDate date(JsonNode n) {
        if (n.isMissingNode() || n.isNull() || n.asText().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(n.asText());
        } catch (RuntimeException e) {
            throw invalid("dates are yyyy-mm-dd, not '" + n.asText() + "'");
        }
    }

    static DrishtiException invalid(String message) {
        return new DrishtiException(ErrorCode.QUERY_INVALID, message);
    }
}
