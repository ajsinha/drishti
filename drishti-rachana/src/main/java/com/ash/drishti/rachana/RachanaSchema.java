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
        p.put("controls", Map.of("type", "array", "maxItems", com.ash.drishti.rachana.model.ControlRules.MAX_CONTROLS,
                "description", "Controls the reader sets: dropdowns, date ranges, search. Their values are read as ctl.<id> in expressions",
                "items", controlSchema()));
        p.put("panels", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/panel")));
        Map<String, Object> keys = new LinkedHashMap<>();
        KEYS.forEach(k -> keys.put(k, text(EL + ", or impact or raw")));
        p.put("keys", Map.of("type", "object", "properties", keys, "additionalProperties", false,
                "description", "Function keys: link(…) to open an entity, impact (F8), raw (F9)"));
        s.put("properties", p);
        Map<String, Object> panel = panel(formats);
        panel.put("x-rachana-use", Map.of("$ref", "#/$defs/use"));    // an item with `use:` is a shared block's use, not a panel
        s.put("$defs", Map.of("panel", panel, "column", column(formats), "use", use()));
        Map<String, Object> fns = new LinkedHashMap<>();
        Functions.arity().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> fns.put(e.getKey(), Map.of("min", e.getValue()[0], "max", e.getValue()[1])));
        s.put("x-rachana-functions", fns);
        return s;
    }

    /** A use of a shared block in a panels list: {@code { use: legs@1, with: { rate-fmt: pct4 } }} (DRS-2048 when it does not resolve). */
    private static Map<String, Object> use() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("use", Map.of("type", "string", "pattern", "^[a-z][a-z0-9-]{1,63}(@[0-9]+)?$",
                "description", "A shared block (<name>.v<N>.block.yaml in a pack's sutras folder) as name@version: its panels go here"));
        props.put("with", Map.of("type", "object", "description", "The block's parameters (its params:), each one value; the block's defaults otherwise"));
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("type", "object");
        u.put("description", "A use of a shared block: the block's panels, in its order, as if they were written here");
        u.put("required", List.of("use"));
        u.put("properties", props);
        u.put("additionalProperties", false);
        return u;
    }

    private static Map<String, Object> controlSchema() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", Map.of("type", "string", "pattern", "^" + com.ash.drishti.rachana.model.ControlRules.ID_PATTERN + "$",
                "description", "Read as ctl.<id>"));
        c.put("kind", Map.of("type", "string", "enum", java.util.Arrays.stream(com.ash.drishti.rachana.model.ControlKind.values())
                .map(com.ash.drishti.rachana.model.ControlKind::id).toList()));
        c.put("label", text("The text beside the control"));
        c.put("options", Map.of("description", "A list of values or {value, label}, an expression over the record ($.legs[*].id), or {kind: <entity kind>}",
                "oneOf", List.of(Map.of("type", "array"), Map.of("type", "string"), Map.of("type", "object"))));
        c.put("default", Map.of("description", "A value, first, all, today, or a relative range such as next-90d"));
        c.put("remember", Map.of("type", "string", "enum", List.of("user", "none")));
        c.put("required", Map.of("type", "boolean"));
        c.put("placement", Map.of("type", "string", "enum", List.of("strip", "header", "side")));
        c.put("panel", text("The panel whose header holds the control (placement: header)"));
        c.put("parent", text("The control a cascading control depends on"));
        c.put("min", Map.of("description", "Lower bound of a number-range or slider: a number or an expression over the record"));
        c.put("max", Map.of("description", "Upper bound of a number-range or slider"));
        c.put("step", Map.of("description", "A slider's step: a number or an expression over the record (default 1)"));
        c.put("range", Map.of("type", "boolean", "description", "A slider with two handles whose value is from..to (slider only)"));
        return object(c, List.of("id", "kind"));
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
        common.put("where", text(EL + " that must be true for a row to be shown (it may read ctl.<id>)"));
        common.put("on-select", Map.of("type", "object", "description", "A click on a row or bar sets a control: {filter: [panel ids], by: <control id>, field: <row field>}"));
        common.put("columns", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/column")));
        common.put("body", Map.of("$ref", "#/$defs/panel"));
        common.put("panels", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/panel"),
                "description", "tabs only: panels shown inside each tab (kv, status, table, ladder, hbar, markdown, metric), read with @ as the tab's element"));
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
            for (String shared : List.of(com.ash.drishti.rachana.model.Panel.WHERE, com.ash.drishti.rachana.model.Panel.ON_SELECT,
                    com.ash.drishti.rachana.model.Panel.WHEN)) {
                if (k.accepts(shared)) {
                    opts.add(shared);       // the row condition and the click-sets-a-control option: accepted by the kind, so offered by its schema
                }
            }
            for (String o : opts) {
                Object schema = o.equals("fmt") ? fmt(formats) : o.equals("tone") ? tone()
                        : o.equals("search") ? Map.of("type", "boolean", "description", "false hides the table's filter")
                        : expr.contains(o) ? text(EL) : kindOption(k, o);
                props.put(o, forKind(k, o, schema));
                allOptions.putIfAbsent(o, schema);
            }
            if (k == PanelKind.TABS) {
                opts.add("body");       // the parser reads the panel drawn per tab as `body`, or several as `panels`; the workbench's editor must not call them unknown
                opts.add("panels");
                props.put("body", common.get("body"));
                props.put("panels", common.get("panels"));
            }
            // the options as THIS kind takes them (one name can mean different things on two kinds, so the shared definition above stays loose)
            perKind.add(Map.of("if", Map.of("properties", Map.of("kind", Map.of("const", k.id()))),
                    "then", Map.of("required", List.copyOf(k.required()), "x-rachana-options", List.copyOf(opts), "properties", props)));
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
        if ((k == PanelKind.TABLE || k == PanelKind.LADDER) && o.equals("levels")) {
            return Map.of("type", "array", "minItems", 1, "maxItems", com.ash.drishti.rachana.model.PanelOptions.MAX_LEVELS,
                    "items", Map.of("type", "object", "required", List.of("rows"), "properties", Map.of(
                            "rows", text(EL + " giving the rows a row of the level above opens to, read with @ that row (\"@.cashflows\")"),
                            "title", Map.of("type", "string", "description", "What the rows of this level are (Cash flows)"),
                            "crumb", text(EL + " naming a row of this level in the breadcrumb ('Flow ' + @.id)"),
                            "columns", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/column")))),
                    "description", "Drill down level by level: a row opens to its rows of the next level, with a breadcrumb, the parent row, "
                            + "counts and totals at each level, and an address (#panel=1.6) that can be shared");
        }
        if ((k == PanelKind.TABLE || k == PanelKind.LADDER) && o.equals("empty")) {
            return Map.of("type", "string", "description", "The words shown when there are no rows (default: No data available)");
        }
        if (k == PanelKind.TABLE && o.equals("limit")) {
            // one schema serves every kind that has a "limit": a table's is a count, an area's an expression, so no type here
            // (the table's rule, a whole number from 1, is kept by PanelOptions)
            return Map.of("description", TABLE_LIMIT + " Area: a path or expression for the limit line");
        }
        if ((k == PanelKind.KV || k == PanelKind.STATUS) && o.equals("fields") || k == PanelKind.AREA && o.equals("series")) {
            return Map.of("type", "array", "description", o.equals("series") ? "The series: { label, value, tone }" : "The fields: { label, bind, fmt, tone }");
        }
        if (k == PanelKind.METRIC && (o.equals("deltaFmt") || o.equals("deltaTone"))) {
            return Map.of("type", "string", "description", o.equals("deltaFmt") ? "How the change is formatted (default: the format of value)"
                    : "The change's tone: sign (default: positive green, negative red), status, or a fixed tone such as neg, warn or accent");
        }
        if (k == PanelKind.METRIC && (o.equals("unit") || o.equals("caption"))) {
            return Map.of("type", "string", "description", o.equals("unit") ? "A short unit shown small beside the figure (USD, bp)"
                    : "Small text under the figure (may hold ${…})");
        }
        if (k == PanelKind.MAP) {
            switch (o) {
                case "highlight" -> {
                    return Map.of("type", "string", "description", EL + " for the country to highlight: an ISO alpha-2 or alpha-3 code, or the English name ($.iso3)");
                }
                case "region" -> {
                    return Map.of("type", "string", "description", EL + " for a country: the map then shows that country's regions (ISO 3166-2 codes such as AU-NSW) instead of the world");
                }
                case "link" -> {
                    return Map.of("type", "object", "description", "A click on a country opens an entity: { kind: jurisdiction, id: \"JUR-{iso3}\" }. The id template holds {iso3}, {iso2} "
                            + "or (for regions) {code}; a country with no such entity is not clickable and says so on hover");
                }
                case "shade" -> {
                    return Map.of("type", "object", "description", "Shade each country by a field of every entity of a kind: { kind: economy, field: $.gdpUsdBn, by: $.iso3, fmt: amount0, label: GDP (USD bn) }. "
                            + "by defaults to $.iso3; the field is read as the caller may see it (a masked field shades nothing)");
                }
                case "unit" -> {
                    return Map.of("type", "string", "description", "A short unit shown with the shade values in the legend and the tooltip");
                }
                default -> { }
            }
        }
        if (k == PanelKind.HISTOGRAM && o.equals("markers")) {
            return Map.of("type", "array", "description", "Vertical marker lines: { label, value: <expression>, tone }");
        }
        return Map.of("description", "option of " + k.id() + " panels");
    }

    /** The option as {@code k} takes it: its fixed set when it has one, a whole {@code limit} on a table, else the shared definition. */
    private static Object forKind(PanelKind k, String o, Object shared) {
        if (k == PanelKind.TABLE && o.equals("limit")) {
            return Map.of("type", "integer", "minimum", 1, "description", TABLE_LIMIT);
        }
        List<String> choices = com.ash.drishti.rachana.model.PanelOptions.choices(k, o);
        if (choices.isEmpty()) {
            choices = SCHEMA_CHOICES.getOrDefault(k, Map.of()).getOrDefault(o, List.of());
        }
        if (!choices.isEmpty() && !(shared instanceof Map<?, ?> m && m.containsKey("enum"))) {
            return Map.of("type", "string", "enum", choices, "description", "One of " + String.join(", ", choices) + " (option of " + k.id() + " panels)");
        }
        return shared;
    }

    private static final String TABLE_LIMIT = "Table: rows shown (a whole number from 1; the rest are counted in the more line).";
    /** Options with a fixed set the parser does not police (a different value draws the default): the designer still offers the set. */
    private static final Map<PanelKind, Map<String, List<String>>> SCHEMA_CHOICES = Map.of(
            PanelKind.TABS, Map.of("layout", List.of("tabs", "columns")),
            PanelKind.SURFACE, Map.of("view", List.of("heatmap", "3d")));

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
