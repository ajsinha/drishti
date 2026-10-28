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
package com.ash.drishti.engine.view;

import com.ash.drishti.engine.live.Frame;
import com.ash.drishti.engine.live.Patch;
import com.ash.drishti.rachana.model.PanelKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schema (draft 2020-12) of the ViewModel, its panels' {@code data} and the live stream's frames
 * ({@code GET /api/v1/viewmodel/schema}, docs/architecture/VIEWMODEL.md section 9).
 *
 * <p>Generated from the records themselves, so a field added to {@link ViewModel}, {@link PanelData}, {@link Frame} or
 * {@link Patch} is in the schema at once. Each record's Jackson inclusion decides what is required and what may be null:
 * a component that is always written is required, and nullable when it is a reference; one left out when null, empty or
 * default is optional and never null. On top of the generated shape sit the few rules the types cannot say: the panel
 * kind that goes with each {@code data} record ({@link #DATA_BY_KIND}), the closed sets of words ({@link #ENUMS}), the
 * tones, and the fields a ViewModel always carries ({@link #REQUIRED}). {@code ViewModelSchemaTest} validates real views
 * and frames against it, so these rules cannot drift from what the server sends.
 */
public final class ViewModelSchema {

    /** The record that carries a panel's {@code data}, per panel kind: every {@link PanelKind}, and every {@link PanelData} record. */
    public static final Map<String, Class<? extends PanelData>> DATA_BY_KIND = dataByKind();

    /** The tones a value may carry ({@code Cell.tone} and the tones of bars, steps, markers, events and candles). */
    public static final List<String> TONES = List.of("pos", "neg", "link", "accent", "ok", "warn", "bad", "muted", "inactive");

    /** Closed sets of words, by {@code Record.component}. */
    static final Map<String, List<String>> ENUMS = Map.of(
            "Patch.op", List.of("strip", "panel", "provenance", "deleted", "restored"),
            "KeyView.action", List.of("panel", "link", "raw", "impact", "denied"),
            "LinkItem.status", List.of("resolved", "pending", "missing", "denied", "inactive"),
            "PanelCompute.state", List.of("ready", "computing", "stale", "failed", "refused"),
            "Tabs.layout", List.of("tabs", "columns"),
            "Graph.layout", List.of("tree", "force"),
            "Surface.view", List.of("heatmap", "3d"),
            "GeoMap.mode", List.of("world", "region"));

    /** Components written as a tone ({@link #TONES}); a Series' tone is the author's own word and stays free text. */
    static final List<String> TONED = List.of("Cell.tone", "Bar.tone", "Step.tone", "Marker.tone", "Event.tone", "Candles.tone");

    /** Fields the server always sends although the record would let them be left out. */
    static final Map<String, List<String>> REQUIRED = Map.of(
            "ViewModel", List.of("ref", "title", "strip", "panels", "keys", "provenance", "timings"),
            "PanelView", List.of("id", "kind"),
            "Ref", List.of("kind", "id"),
            "LinkView", List.of("kind", "id"),
            "Patch", List.of("op"));

    /** One line on each record, carried into the schema as its description. */
    private static final Map<String, String> ABOUT = Map.ofEntries(
            Map.entry("ViewModel", "A bound view: everything a client renders, and nothing it must compute"),
            Map.entry("Cell", "One formatted value: text, an optional label, tone, link and the document path it came from"),
            Map.entry("LinkView", "A navigable link to another entity"),
            Map.entry("PanelView", "One panel: its id, kind and place, and its kind-specific data"),
            Map.entry("Provenance", "How the view was built: layout, source, generation, business date, freshness and masks"),
            Map.entry("KeyView", "A function key"),
            Map.entry("Frame", "One live-stream frame event: the patches that turn the client's view into the current one"),
            Map.entry("Patch", "One change to a rendered view"),
            Map.entry("Fields", "data of kv, status and provenance"),
            Map.entry("Table", "data of table and ladder"),
            Map.entry("Tabs", "data of tabs"),
            Map.entry("Chart", "data of line and area: numeric series over x labels"),
            Map.entry("Bars", "data of hbar"),
            Map.entry("Links", "data of links"),
            Map.entry("Text", "data of markdown"),
            Map.entry("Gauge", "data of gauge"),
            Map.entry("Surface", "data of surface: z[row][column] over x and y"),
            Map.entry("Waterfall", "data of waterfall"),
            Map.entry("Histogram", "data of histogram: bins computed on the server"),
            Map.entry("Scatter", "data of scatter"),
            Map.entry("Candles", "data of candlestick"),
            Map.entry("Graph", "data of graph"),
            Map.entry("Timeline", "data of timeline"),
            Map.entry("Pivot", "data of pivot"),
            Map.entry("Metric", "data of metric"),
            Map.entry("GeoMap", "data of map"));

    public static final String ID = "https://drishti.local/schema/viewmodel.json";

    private ViewModelSchema() {}

    private static Map<String, Class<? extends PanelData>> dataByKind() {
        Map<String, Class<? extends PanelData>> m = new LinkedHashMap<>();
        m.put("kv", PanelData.Fields.class);
        m.put("table", PanelData.Table.class);
        m.put("tabs", PanelData.Tabs.class);
        m.put("line", PanelData.Chart.class);
        m.put("area", PanelData.Chart.class);
        m.put("hbar", PanelData.Bars.class);
        m.put("ladder", PanelData.Table.class);
        m.put("links", PanelData.Links.class);
        m.put("status", PanelData.Fields.class);
        m.put("provenance", PanelData.Fields.class);
        m.put("markdown", PanelData.Text.class);
        m.put("gauge", PanelData.Gauge.class);
        m.put("surface", PanelData.Surface.class);
        m.put("waterfall", PanelData.Waterfall.class);
        m.put("histogram", PanelData.Histogram.class);
        m.put("scatter", PanelData.Scatter.class);
        m.put("candlestick", PanelData.Candles.class);
        m.put("graph", PanelData.Graph.class);
        m.put("timeline", PanelData.Timeline.class);
        m.put("pivot", PanelData.Pivot.class);
        m.put("metric", PanelData.Metric.class);
        m.put("map", PanelData.GeoMap.class);
        return java.util.Collections.unmodifiableMap(m);
    }

    /** The schema: a ViewModel at the root, every record under {@code $defs} ({@code Frame} for the live stream's events). */
    public static Map<String, Object> build() {
        Map<String, Object> defs = new LinkedHashMap<>();
        record(ViewModel.class, defs);
        record(Frame.class, defs);
        for (Class<?> c : PanelData.class.getPermittedSubclasses()) {
            record(c, defs);
        }
        defs.put("Tone", Map.of("enum", TONES, "description", "A theme tone; the console maps each to a colour token and pairs it with a sign or glyph"));
        defs.put("PanelData", Map.of("description", "A panel's kind-specific content; which record depends on the panel's kind",
                "anyOf", Arrays.stream(PanelData.class.getPermittedSubclasses()).map(c -> ref(c.getSimpleName())).toList()));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        s.put("$id", ID);
        s.put("title", "Drishti ViewModel");
        s.put("description", "The JSON the server returns for every entity screen (GET /api/v1/views/{kind}/{id}, and the view event of its "
                + "stream). $defs.Frame is a frame event of the stream. See docs/architecture/VIEWMODEL.md.");
        s.put("$ref", "#/$defs/ViewModel");
        s.put("$defs", defs);
        return s;
    }

    private static Map<String, Object> ref(String name) {
        return Map.of("$ref", "#/$defs/" + name);
    }

    /** Adds {@code c}'s definition, and those of the records it holds, to {@code defs}. */
    private static void record(Class<?> c, Map<String, Object> defs) {
        String name = c.getSimpleName();
        if (defs.containsKey(name)) {
            return;
        }
        Map<String, Object> def = new LinkedHashMap<>();
        defs.put(name, def);                             // reserved first: records may hold themselves (Row.children)
        if (ABOUT.containsKey(name)) {
            def.put("description", ABOUT.get(name));
        }
        def.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>(REQUIRED.getOrDefault(name, List.of()));
        JsonInclude classLevel = c.getAnnotation(JsonInclude.class);
        for (RecordComponent rc : c.getRecordComponents()) {
            JsonInclude own = rc.getAccessor().getAnnotation(JsonInclude.class);
            JsonInclude.Include inc = own != null && own.value() != JsonInclude.Include.USE_DEFAULTS ? own.value()
                    : classLevel != null ? classLevel.value() : JsonInclude.Include.ALWAYS;
            boolean always = inc == JsonInclude.Include.ALWAYS || inc == JsonInclude.Include.USE_DEFAULTS;
            String key = name + "." + rc.getName();
            Map<String, Object> type;
            if (ENUMS.containsKey(key)) {
                type = new LinkedHashMap<>(Map.of("enum", ENUMS.get(key)));
            } else if (TONED.contains(key)) {
                type = new LinkedHashMap<>(ref("Tone"));
            } else {
                type = type(rc.getGenericType(), defs);
            }
            boolean primitive = rc.getType().isPrimitive();
            if (always && !primitive) {
                type = nullable(type);
            }
            props.put(rc.getName(), type);
            if (always && !required.contains(rc.getName())) {
                required.add(rc.getName());
            }
        }
        def.put("properties", props);
        if (!required.isEmpty()) {
            def.put("required", required);
        }
        def.put("additionalProperties", false);
        if (c == ViewModel.PanelView.class) {
            def.put("properties", panelProperties(props));
            def.put("allOf", DATA_BY_KIND.entrySet().stream().map(e -> Map.of(
                    "if", Map.of("properties", Map.of("kind", Map.of("const", e.getKey())), "required", List.of("kind")),
                    "then", Map.of("properties", Map.of("data", nullable(ref(e.getValue().getSimpleName()))))))
                    .toList());
        }
    }

    /** A panel's kind: one of the grammar's kinds; its data: the record that kind carries (null when the panel failed or is denied). */
    private static Map<String, Object> panelProperties(Map<String, Object> props) {
        Map<String, Object> out = new LinkedHashMap<>(props);
        out.put("kind", Map.of("enum", Arrays.stream(PanelKind.values()).map(PanelKind::id).toList()));
        return out;
    }

    /**
     * A number: the server writes NaN and the infinities (a gauge whose document lacks the value, imperfect source data) as null
     * ({@code JsonConfiguration}), so every number, a primitive one too, may be null.
     */
    private static Map<String, Object> number() {
        return new LinkedHashMap<>(Map.of("anyOf", List.of(Map.of("type", "number"), Map.of("type", "null"))));
    }

    private static Map<String, Object> nullable(Map<String, Object> type) {
        if (type.isEmpty() || type.equals(number())) {
            return type;                                  // anything, or a number: null included already
        }
        return new LinkedHashMap<>(Map.of("anyOf", List.of(type, Map.of("type", "null"))));
    }

    private static Map<String, Object> type(Type t, Map<String, Object> defs) {
        if (t instanceof WildcardType w) {
            return type(w.getUpperBounds()[0], defs);
        }
        if (t instanceof ParameterizedType p) {
            Class<?> raw = (Class<?>) p.getRawType();
            Type[] args = p.getActualTypeArguments();
            if (List.class.isAssignableFrom(raw)) {
                Map<String, Object> item = type(args[0], defs);
                boolean boxedNumber = args[0] == Integer.class || args[0] == Long.class;
                boolean nested = args[0] instanceof ParameterizedType;
                return new LinkedHashMap<>(Map.of("type", "array", "items", boxedNumber || nested ? nullable(item) : item));
            }
            if (Map.class.isAssignableFrom(raw)) {
                Map<String, Object> value = type(args[1], defs);
                return value.isEmpty() ? new LinkedHashMap<>(Map.of("type", "object"))
                        : new LinkedHashMap<>(Map.of("type", "object", "additionalProperties", value));
            }
            return type(raw, defs);
        }
        Class<?> c = (Class<?>) t;
        if (c == String.class) {
            return new LinkedHashMap<>(Map.of("type", "string"));
        }
        if (c == boolean.class || c == Boolean.class) {
            return new LinkedHashMap<>(Map.of("type", "boolean"));
        }
        if (c == int.class || c == Integer.class || c == long.class || c == Long.class) {
            return new LinkedHashMap<>(Map.of("type", "integer"));
        }
        if (c == double.class || c == Double.class) {
            return number();
        }
        if (c == Instant.class) {
            return new LinkedHashMap<>(Map.of("type", "string", "format", "date-time"));
        }
        if (c == Object.class) {
            return new LinkedHashMap<>();
        }
        if (c == PanelData.class) {
            return new LinkedHashMap<>(ref("PanelData"));
        }
        if (c.isRecord()) {
            record(c, defs);
            return new LinkedHashMap<>(ref(c.getSimpleName()));
        }
        throw new IllegalStateException("no JSON type for " + c);
    }
}
