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
package com.ash.drishti.rachana;

import com.ash.drishti.rachana.el.Functions;
import com.ash.drishti.rachana.format.Tones;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.parse.SutraParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The JSON Schema (draft 2020-12) of a Sutra, built from the grammar itself: the panel kinds and the options each
 * takes, which values are Rachana-EL, the formats and tones, the kinds this server serves. Editors use it for
 * completion and checking before a Sutra reaches the server; {@code x-rachana-functions} lists the expression
 * functions for completion inside expressions. Because it is generated, it never disagrees with the parser.
 */
public final class RachanaSchema {

    private static final String EL = "Rachana-EL expression ($ is the document, @ the row)";
    private static final List<String> KEYS = List.of("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12");

    private RachanaSchema() {}

    /**
     * @param formats format names this server knows (core and packs)
     * @param kinds entity kinds this server serves
     */
    public static Map<String, Object> build(Collection<String> formats, Collection<String> kinds) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        s.put("$id", "https://drishti.local/schema/rachana-" + SutraParser.LANGUAGE + ".json");
        s.put("title", "Rachana Sutra, language " + SutraParser.LANGUAGE);
        s.put("description", "A Sutra: how entities of a kind are laid out. One YAML document per file (<name>.v<N>.sutra.yaml).");
        s.put("type", "object");
        s.put("required", List.of("rachana", "sutra", "version", "match"));
        s.put("additionalProperties", false);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("rachana", Map.of("const", SutraParser.LANGUAGE, "description", "The Rachana language version; always " + SutraParser.LANGUAGE));
        p.put("sutra", Map.of("type", "string", "pattern", "^[a-z][a-z0-9-]{1,63}$", "description", "The Sutra's name: lower-case kebab"));
        p.put("version", Map.of("type", "integer", "minimum", 1, "description", "The newest version of a name is the one used"));
        p.put("domain", text("The folder it belongs to (defaults to its parent folder)"));
        p.put("description", text("One paragraph: what this layout shows and for which entities"));
        p.put("notes", text("Longer notes for authors and reviewers (plain text)"));
        p.put("match", object(Map.of(
                "kind", kinds.isEmpty() ? text("The entity kind") : Map.of("type", "string", "enum", List.copyOf(new TreeSet<>(kinds)),
                        "description", "The entity kind"),
                "where", text(EL + " that must be true for the Sutra to apply"),
                "priority", Map.of("type", "integer", "description", "Higher wins when several Sutras match")), List.of("kind")));
        p.put("title", object(Map.of("pill", text("The pill before the id (may hold ${…})"), "id", text(EL + " for the id shown"),
                "with", text(EL + ": the counterparty or other entity named after the id")), List.of("id")));
        p.put("strip", Map.of("type", "array", "maxItems", 8, "description", "Up to eight fields in the header strip",
                "items", object(Map.of("label", text("Defaults to the field's name"), "bind", text(EL),
                        "fmt", fmt(formats), "tone", tone(), "emphasis", Map.of("type", "boolean")), List.of("bind"))));
        p.put("panels", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/panel")));
        Map<String, Object> keys = new LinkedHashMap<>();
        KEYS.forEach(k -> keys.put(k, text(EL + ", or impact or raw")));
        p.put("keys", Map.of("type", "object", "properties", keys, "additionalProperties", false,
                "description", "Function keys: link(…) to open an entity, impact (F8), raw (F9)"));
        s.put("properties", p);
        s.put("$defs", Map.of("panel", panel(formats), "column", column(formats)));
        Map<String, Object> fns = new LinkedHashMap<>();
        Functions.arity().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> fns.put(e.getKey(), Map.of("min", e.getValue()[0], "max", e.getValue()[1])));
        s.put("x-rachana-functions", fns);
        return s;
    }

    private static Map<String, Object> panel(Collection<String> formats) {
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("id", Map.of("type", "string", "description", "Unique within the Sutra"));
        common.put("kind", Map.of("type", "string", "enum", java.util.Arrays.stream(PanelKind.values()).map(PanelKind::id).toList()));
        common.put("title", text("The panel's heading (may hold ${…})"));
        common.put("description", text("What the panel answers, for authors and reviewers"));
        common.put("key", Map.of("type", "string", "enum", KEYS));
        common.put("code", text("A short code at the heading's right (CRV, REFS)"));
        common.put("area", Map.of("type", "string", "enum", List.of("main", "right")));
        common.put("span", Map.of("type", "integer", "minimum", 1, "maximum", com.ash.drishti.rachana.model.Panel.MAX_SPAN,
                "description", "Width in columns of a 12-column grid (default: the whole column)"));
        common.put("height", Map.of("type", "integer", "minimum", 1, "maximum", com.ash.drishti.rachana.model.Panel.MAX_HEIGHT,
                "description", "Height in grid rows of 2.5rem (default: as tall as the content)"));
        common.put("infer", Map.of("type", "boolean", "description", "Let inference fill the columns"));
        common.put("columns", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/column")));
        common.put("body", Map.of("$ref", "#/$defs/panel"));
        List<Object> perKind = new ArrayList<>();
        Map<String, Object> allOptions = new LinkedHashMap<>(common);
        for (PanelKind k : PanelKind.values()) {
            Map<String, Object> props = new LinkedHashMap<>();
            java.util.Set<String> expr = SutraExpressions.expressionOptions(k);
            TreeSet<String> opts = new TreeSet<>(k.required());
            opts.addAll(k.optional());
            if (k.readsData()) {
                opts.add("source");
            }
            for (String o : opts) {
                Object schema = o.equals("fmt") ? fmt(formats) : o.equals("tone") ? tone()
                        : o.equals("search") ? Map.of("type", "boolean", "description", "false hides the table's filter")
                        : expr.contains(o) ? text(EL) : kindOption(k, o);
                props.put(o, schema);
                allOptions.putIfAbsent(o, schema);
            }
            perKind.add(Map.of("if", Map.of("properties", Map.of("kind", Map.of("const", k.id()))),
                    "then", Map.of("required", List.copyOf(k.required()), "x-rachana-options", List.copyOf(opts))));
        }
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("type", "object");
        panel.put("required", List.of("id", "kind"));
        panel.put("properties", allOptions);
        panel.put("allOf", perKind);
        return panel;
    }

    /**
     * An option whose values the kind restricts (pivot's agg, heat and totals; waterfall's colors; histogram's bins and
     * markers), else free text.
     */
    private static Map<String, Object> kindOption(PanelKind k, String o) {
        if (k == PanelKind.PIVOT && o.equals("agg")) {
            return Map.of("type", "string", "enum", com.ash.drishti.rachana.model.PanelOptions.AGGREGATIONS,
                    "description", "How the pivot combines the values of a cell (default sum)");
        }
        if (k == PanelKind.PIVOT && (o.equals("heat") || o.equals("totals"))) {
            return Map.of("type", "boolean", "description", o.equals("heat") ? "Colour each cell by its value" : "false hides the row and column totals");
        }
        if (k == PanelKind.WATERFALL && o.equals("colors")) {
            return Map.of("type", "string", "enum", com.ash.drishti.rachana.model.PanelOptions.WATERFALL_COLORS,
                    "description", "gain-loss (default): rises green, falls red; theme: the theme's positive and negative colours "
                            + "(blue and orange, colour-blind friendly). Totals are neutral");
        }
        if (o.equals("expand") && (k == PanelKind.PIVOT || k == PanelKind.TABLE || k == PanelKind.LADDER)) {
            return Map.of("oneOf", List.of(Map.of("type", "integer", "minimum", 1), Map.of("const", "all")),
                    "description", "Levels of the tree shown open at first (default 1: only the top level); all opens every level");
        }
        if (k == PanelKind.PIVOT && o.equals("by")) {
            return Map.of("oneOf", List.of(Map.of("type", "string"), Map.of("type", "array", "minItems", 1,
                    "maxItems", com.ash.drishti.rachana.model.PanelOptions.MAX_BY, "items", Map.of("type", "string"))),
                    "description", "The field the rows group by, or a list of fields for nested groups with subtotals (▸/▾)");
        }
        if (o.equals(com.ash.drishti.rachana.model.Panel.PIVOT)) {
            return pivot();
        }
        if (k == PanelKind.HISTOGRAM && o.equals("bins")) {
            return Map.of("type", "integer", "minimum", 1, "maximum", com.ash.drishti.rachana.model.PanelOptions.MAX_BINS,
                    "description", "How many bins (default: the square root of the count, 5 to 40)");
        }
        if (k == PanelKind.TABLE && o.equals("limit")) {
            return Map.of("type", "integer", "minimum", 1, "description", "Rows shown; the rest are counted in the more line");
        }
        if ((k == PanelKind.KV || k == PanelKind.STATUS) && o.equals("fields") || k == PanelKind.AREA && o.equals("series")) {
            return Map.of("type", "array", "description", o.equals("series") ? "The series: { label, value, tone }" : "The fields: { label, bind, fmt, tone }");
        }
        if (k == PanelKind.HISTOGRAM && o.equals("markers")) {
            return Map.of("type", "array", "description", "Vertical marker lines: { label, value: <expression>, tone }");
        }
        return Map.of("description", "option of " + k.id() + " panels");
    }

    /** {@code pivot}: true, or the fields a user may pivot by and the arrangement the Pivot tab opens with. */
    private static Map<String, Object> pivot() {
        List<String> aggs = com.ash.drishti.rachana.model.PivotSpec.AGGREGATIONS;
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("field", text("The field's name (a row path, or any name with a bind)"));
        field.put("bind", text(EL + " over the row (@), for a field that is not a plain path"));
        field.put("label", text("Defaults to the field's name"));
        field.put("fmt", text("A number or date format"));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("field", text("A field named in fields"));
        value.put("agg", Map.of("type", "string", "enum", aggs, "description", "How a cell combines its rows (default sum)"));
        value.put("show", Map.of("type", "string", "enum", com.ash.drishti.rachana.model.PivotSpec.SHOWS,
                "description", "As the value itself, or as a share of its row, column or the total"));
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("field", text("A field named in fields"));
        filter.put("values", Map.of("type", "array", "description", "The values kept"));
        filter.put("min", Map.of("description", "Lowest kept (a number or a date)"));
        filter.put("max", Map.of("description", "Highest kept (a number or a date)"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("fields", Map.of("type", "array", "maxItems", com.ash.drishti.rachana.model.PivotSpec.MAX_FIELDS,
                "description", "The fields a user may pivot by: row paths, or { field, bind, label, fmt } (default: the panel's columns)",
                "items", Map.of("type", List.of("string", "object"), "properties", field, "additionalProperties", false)));
        props.put("rows", Map.of("type", "array", "maxItems", com.ash.drishti.rachana.model.PivotSpec.MAX_LEVELS,
                "description", "Fields down the side, outermost first"));
        props.put("columns", Map.of("type", "array", "maxItems", com.ash.drishti.rachana.model.PivotSpec.MAX_LEVELS,
                "description", "Fields across the top, outermost first"));
        props.put("values", Map.of("type", "array", "maxItems", com.ash.drishti.rachana.model.PivotSpec.MAX_VALUES,
                "description", "The numbers in the cells: { field, agg, show }", "items", object(value, List.of("field"))));
        props.put("filters", Map.of("type", "array", "description", "Fields to filter by: names, or { field, values } / { field, min, max }",
                "items", Map.of("type", List.of("string", "object"), "properties", filter, "additionalProperties", false)));
        props.put("heat", Map.of("type", "boolean", "description", "Shade each cell by its value"));
        props.put("chart", Map.of("type", "string", "enum", com.ash.drishti.rachana.model.PivotSpec.CHARTS,
                "description", "Open with a chart of the result"));
        props.put("totals", Map.of("type", "boolean", "description", "false hides subtotals and grand totals"));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", List.of("boolean", "object"));
        o.put("description", "Offer a Pivot tab: true (by the panel's columns), or { fields, rows, columns, values, filters, heat, chart }");
        o.put("properties", props);
        o.put("additionalProperties", false);
        return o;
    }

    private static Map<String, Object> column(Collection<String> formats) {
        return object(Map.of("label", text("Defaults to the field's name"), "bind", text(EL + " over the row (@)"), "fmt", fmt(formats),
                "tone", tone(), "total", Map.of("type", "boolean", "description", "Sum it in the total row"),
                "link", Map.of("type", "boolean", "description", "Open the entity the value names"),
                "description", text("What the column shows")), List.of("bind"));
    }

    private static Map<String, Object> object(Map<String, Object> props, List<String> required) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", "object");
        o.put("properties", props);
        o.put("additionalProperties", false);
        if (!required.isEmpty()) {
            o.put("required", required);
        }
        return o;
    }

    private static Map<String, Object> text(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> fmt(Collection<String> formats) {
        return Map.of("type", "string", "enum", List.copyOf(new TreeSet<>(formats)), "description", "A number or date format");
    }

    private static Map<String, Object> tone() {
        return Map.of("type", "string", "enum", Tones.NAMES, "description", "Colours the value");
    }
}
