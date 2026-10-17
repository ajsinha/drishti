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
package com.ash.drishti.server.bi.layer;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.server.bi.expr.DelException;
import com.ash.drishti.server.bi.expr.DelParser;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads wave B's model keys (docs/architecture/BI_DESIGN.md sections 3.3.5 and 12.2) into {@link ModelDef.Calculation}:
 *
 * <pre>
 * calc-groups:
 *   Time:
 *     precedence: 10
 *     items: { Current: "SELECTED", YTD: "ytd(SELECTED, trades.tradeDate)", Prior: "prior_period(SELECTED, trades.tradeDate)" }
 * parameters:
 *   shock:     { kind: number, default: 0, min: -0.5, max: 0.5, step: 0.01 }
 *   breakdown: { kind: field, fields: [desks.region, trades.currency], default: desks.region }
 * calendars: { holidays: USNY, fiscal-year-start: 4 }
 * fx: { rates: fx_rates, from: fromCcy, to: toCcy, rate: rate, date: businessDate, base: USD }
 * </pre>
 *
 * An item is DEL with {@code SELECTED} for the measure it is applied to ({@code ${measure}}, the earlier spelling, is read as {@code SELECTED}). Problems are
 * {@code DRS-9120} (the shape) and {@code DRS-9127} (an item that is not DEL); what names fields is checked with the datasets (ModelChecker).
 */
final class CalculationParser {

    static final Set<String> KINDS = Set.of("number", "integer", "text", "date", "boolean", "field");
    /** The name of the measure a calculation item applies to, in its template. */
    static final String SELECTED = "SELECTED";

    private CalculationParser() {}

    static ModelDef.Calculation read(JsonNode root, Map<String, ModelDef.Use> datasets, LayerYaml y) {
        Map<String, ModelDef.CalcGroup> groups = new LinkedHashMap<>();
        JsonNode cg = root.path("calc-groups");
        if (!cg.isMissingNode() && !cg.isNull()) {
            if (!cg.isObject()) {
                y.error("DRS-9120", "calc-groups", "`calc-groups:` maps a group's name to { precedence, items: { item: \"template with SELECTED\" } }");
            }
            cg.fields().forEachRemaining(e -> groups.put(e.getKey(), group(e.getKey(), e.getValue(), y)));
        }
        Map<String, ModelDef.Parameter> params = new LinkedHashMap<>();
        JsonNode ps = root.path("parameters");
        if (!ps.isMissingNode() && !ps.isNull()) {
            if (!ps.isObject()) {
                y.error("DRS-9120", "parameters", "`parameters:` maps a parameter's name to { kind, default, min, max, step, values, fields }");
            }
            ps.fields().forEachRemaining(e -> params.put(e.getKey(), parameter(e.getKey(), e.getValue(), y)));
        }
        ModelDef.CalendarDef calendar = null;
        JsonNode cal = root.path("calendars");
        if (!cal.isMissingNode() && !cal.isNull()) {
            y.unknownKeys(cal, Set.of("holidays", "fiscal-year-start"), "DRS-9120", "calendars");
            String holidays = LayerYaml.text(cal, "holidays", "WEEKENDS");
            try {
                BusinessCalendar.of(holidays);
            } catch (RuntimeException ex) {
                y.error("DRS-9120", "calendars.holidays", "'" + holidays + "' is not a business calendar (WEEKENDS, USNY, GBLO, USNY+GBLO, ...): " + ex.getMessage());
            }
            int start = cal.path("fiscal-year-start").asInt(1);
            if (start < 1 || start > 12) {
                y.error("DRS-9120", "calendars.fiscal-year-start", "the fiscal year starts in a month, 1 to 12");
                start = 1;
            }
            calendar = new ModelDef.CalendarDef(holidays, start);
        }
        ModelDef.FxDef fx = null;
        JsonNode f = root.path("fx");
        if (!f.isMissingNode() && !f.isNull()) {
            y.unknownKeys(f, Set.of("rates", "from", "to", "rate", "date", "base"), "DRS-9120", "fx");
            String rates = LayerYaml.text(f, "rates", "");
            if (!datasets.containsKey(rates)) {
                y.error("DRS-9120", "fx.rates", "fx.rates names the alias of the model's dataset of FX rates; there is no '" + rates + "'");
            }
            fx = new ModelDef.FxDef(rates, LayerYaml.text(f, "from", "from"), LayerYaml.text(f, "to", "to"), LayerYaml.text(f, "rate", "rate"),
                    LayerYaml.text(f, "date", "businessDate"), f.has("base") ? LayerYaml.text(f, "base", "USD") : null);
        }
        return new ModelDef.Calculation(groups, params, calendar, fx);
    }

    private static ModelDef.CalcGroup group(String name, JsonNode g, LayerYaml y) {
        String path = "calc-groups." + name;
        y.unknownKeys(g, Set.of("precedence", "items", "description"), "DRS-9120", path);
        Map<String, String> items = new LinkedHashMap<>();
        Map<String, String> formats = new LinkedHashMap<>();
        JsonNode it = g.path("items");
        if (!it.isObject() || it.isEmpty()) {
            y.error("DRS-9120", path + ".items", "a calculation group has items: { YTD: \"ytd(SELECTED, trades.tradeDate)\" }");
        }
        it.fields().forEachRemaining(e -> {
            String text = e.getValue().isTextual() ? e.getValue().asText() : e.getValue().path("expr").asText("");
            text = text.replace("${measure}", SELECTED);
            if (!text.contains(SELECTED)) {
                y.error("DRS-9127", path + ".items." + e.getKey(), "an item applies to the selected measure: write SELECTED where it goes, for example ytd(SELECTED, trades.tradeDate)");
            }
            try {
                DelParser.parse(text);
            } catch (DelException ex) {
                y.error("DRS-9127", path + ".items." + e.getKey(), ex.getMessage());
            }
            items.put(e.getKey(), text);
            if (e.getValue().isObject() && e.getValue().path("format").isTextual() && !e.getValue().path("format").asText().isBlank()) {
                formats.put(e.getKey(), e.getValue().path("format").asText());            // { expr, format }: the item's answer reads in it (a share as a percent)
            }
        });
        return new ModelDef.CalcGroup(name, g.path("precedence").asInt(0), items, LayerYaml.text(g, "description", ""), formats);
    }

    private static ModelDef.Parameter parameter(String name, JsonNode p, LayerYaml y) {
        String path = "parameters." + name;
        if (!LayerYaml.ALIAS.matcher(name).matches()) {
            y.error("DRS-9120", path, "a parameter's name is a word (letters, digits, underscore): it is written param." + name);
        }
        y.unknownKeys(p, Set.of("kind", "default", "min", "max", "step", "values", "fields", "title", "description"), "DRS-9120", path);
        String kind = LayerYaml.text(p, "kind", "number");
        if (!KINDS.contains(kind)) {
            y.error("DRS-9120", path + ".kind", "kind is one of " + new java.util.TreeSet<>(KINDS));
        }
        Object dflt = p.has("default") ? LayerYaml.YAML.convertValue(p.get("default"), Object.class) : null;
        Double min = p.path("min").isNumber() ? p.path("min").asDouble() : null;
        Double max = p.path("max").isNumber() ? p.path("max").asDouble() : null;
        Double step = p.path("step").isNumber() ? p.path("step").asDouble() : null;
        List<Object> values = new ArrayList<>();
        p.path("values").forEach(v -> values.add(LayerYaml.YAML.convertValue(v, Object.class)));
        List<String> fields = new ArrayList<>();
        p.path("fields").forEach(v -> fields.add(v.asText()));
        if ("field".equals(kind)) {
            if (fields.isEmpty()) {
                y.error("DRS-9120", path + ".fields", "a field parameter lists the fields it can be: fields: [desks.region, trades.currency]");
            }
            if (dflt != null && !fields.contains(String.valueOf(dflt))) {
                y.error("DRS-9120", path + ".default", "the default of a field parameter is one of its fields " + fields);
            }
        } else if (dflt == null) {
            y.error("DRS-9120", path + ".default", "a parameter has a default value (what the measures use when a query does not set it)");
        }
        if (min != null && max != null && min > max) {
            y.error("DRS-9120", path, "min is above max");
        }
        return new ModelDef.Parameter(name, kind, dflt, min, max, step, values, fields, LayerYaml.text(p, "title", name), LayerYaml.text(p, "description", ""));
    }
}
