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
package com.ash.drishti.rachana.ui;

import com.ash.drishti.rachana.ui.BoardDoc.Area;
import com.ash.drishti.rachana.ui.BoardDoc.Grid;
import com.ash.drishti.rachana.ui.BoardDoc.Instance;
import com.ash.drishti.rachana.ui.BoardDoc.Layout;
import com.ash.drishti.rachana.ui.BoardDoc.Mapping;
import com.ash.drishti.rachana.ui.BoardDoc.Phone;
import com.ash.drishti.rachana.ui.BoardDoc.Supply;
import com.ash.drishti.rachana.ui.BoardDoc.SupplyKind;
import com.ash.drishti.rachana.ui.BoardDoc.Trace;
import com.ash.drishti.rachana.ui.BoardDoc.Transform;
import com.ash.drishti.rachana.ui.BoardDoc.TransformKind;
import com.ash.drishti.rachana.ui.PinSpec.ColumnSpec;
import com.ash.drishti.rachana.ui.PinSpec.PinRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads chip and board documents (UI_BOARD.md sections 2 and 3) from YAML or JSON into one model: the same document in
 * either syntax gives equal {@link ChipDoc} and {@link BoardDoc} values. Every problem is collected with its path (rule
 * {@code BC-01}), so a person sees them all at once; {@link #readBoard} and {@link #readChip} throw
 * {@link UiDocumentException} ({@link com.ash.drishti.common.ErrorCode#UI_DOCUMENT_INVALID}) when there is any.
 */
public final class UiDocuments {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    static final Set<String> BOARD_KEYS = Set.of("rachana", "board", "version", "title", "description", "params", "chips", "local-chips",
            "traces", "layout", "owner", "workspace");
    static final Set<String> CHIP_KEYS = Set.of("rachana", "chip", "version", "title", "category", "tags", "manual", "pins", "body", "limits",
            "compatible-with");
    private static final Set<String> INSTANCE_KEYS = Set.of("use", "title", "supply", "map", "controls", "follow");
    private static final Set<String> PIN_KEYS = Set.of("role", "type", "required", "default", "shape", "of", "unit", "ccy", "min", "max",
            "integer", "values", "shape-of", "description");
    private static final Set<String> COLUMN_KEYS = Set.of("type", "required", "many", "max", "of", "unit", "ccy", "values", "min", "integer");

    private UiDocuments() {}

    /**
     * A document read, with its problems.
     *
     * @param doc the model, or null when the document could not be read at all
     * @param problems what is wrong (rule {@code BC-01}); empty when it read cleanly
     */
    public record Read<T>(T doc, List<UiProblem> problems) {

        public boolean ok() {
            return doc != null && problems.isEmpty();
        }
    }

    /** The YAML or JSON text as a tree (JSON when it starts with a brace). */
    public static JsonNode tree(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            throw new UiDocumentException("the document is empty", List.of(UiProblem.error("BC-01", "", "the document is empty", null)));
        }
        try {
            JsonNode n = (t.startsWith("{") ? JSON : YAML).readTree(t);
            if (n == null || !n.isObject()) {
                throw new UiDocumentException("not a document", List.of(UiProblem.error("BC-01", "", "the document must be a mapping of keys", null)));
            }
            return n;
        } catch (JsonProcessingException e) {
            String where = e.getLocation() == null ? "" : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
            throw new UiDocumentException("not YAML or JSON", List.of(UiProblem.error("BC-01", where,
                    "cannot be read as " + (t.startsWith("{") ? "JSON" : "YAML") + ": " + e.getOriginalMessage(), null)));
        }
    }

    /** True when the document is a chip ({@code chip:} key) rather than a board. */
    public static boolean isChip(JsonNode root) {
        return root.has("chip") && !root.has("board");
    }

    /** Reads a board; never throws (an unreadable text is a null doc with its problem). */
    public static Read<BoardDoc> parseBoard(String text) {
        try {
            List<UiProblem> p = new ArrayList<>();
            BoardDoc d = board(tree(text), "", p, false);
            return new Read<>(d, p);
        } catch (UiDocumentException e) {
            return new Read<>(null, e.problems());
        }
    }

    /** Reads a chip; never throws. */
    public static Read<ChipDoc> parseChip(String text) {
        try {
            List<UiProblem> p = new ArrayList<>();
            ChipDoc d = chip(tree(text), "", p);
            return new Read<>(d, p);
        } catch (UiDocumentException e) {
            return new Read<>(null, e.problems());
        }
    }

    /** Reads a board. @throws UiDocumentException ({@link com.ash.drishti.common.ErrorCode#UI_DOCUMENT_INVALID}) with every problem */
    public static BoardDoc readBoard(String text) {
        return orThrow(parseBoard(text), "the board document cannot be read");
    }

    /** Reads a chip. @throws UiDocumentException ({@link com.ash.drishti.common.ErrorCode#UI_DOCUMENT_INVALID}) with every problem */
    public static ChipDoc readChip(String text) {
        return orThrow(parseChip(text), "the chip document cannot be read");
    }

    private static <T> T orThrow(Read<T> r, String what) {
        if (!r.ok()) {
            throw new UiDocumentException(what, r.problems());
        }
        return r.doc();
    }

    // ------------------------------------------------------------------------------------------------ board

    /**
     * A board from its tree. {@code fragment}: a manual example, which may leave out {@code rachana} and {@code board}.
     */
    public static BoardDoc board(JsonNode n, String at, List<UiProblem> p, boolean fragment) {
        unknownKeys(n, BOARD_KEYS, at, p);
        int rachana = fragment && !n.has("rachana") ? 1 : rachana(n, at, p);
        String name = fragment && !n.has("board") ? "example" : name(n, "board", at, p);
        int version = n.has("version") ? positive(n.get("version"), path(at, "version"), p) : 0;
        Map<String, PinSpec> params = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = mapping(n, "params", at, p, false); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String pp = path(at, "params." + e.getKey());
            wellFormed(e.getKey(), pp, p);
            PinSpec s = pin(e.getKey(), e.getValue(), PinRole.CONTROL, pp, p);
            if (s != null) {
                params.put(e.getKey(), s);
            }
        }
        Map<String, Instance> chips = new LinkedHashMap<>();
        if (!n.has("chips")) {
            p.add(UiProblem.error("BC-01", path(at, "chips"), "a board needs 'chips:', the chip instances by name", "add chips: { <name>: { use: <chip>@<version> } }"));
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = mapping(n, "chips", at, p, false); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String ip = path(at, "chips." + e.getKey());
            wellFormed(e.getKey(), ip, p);
            chips.put(e.getKey(), instance(e.getKey(), e.getValue(), ip, p));
        }
        List<ChipDoc> locals = new ArrayList<>();
        JsonNode lc = n.path("local-chips");
        if (!lc.isMissingNode() && !lc.isNull()) {
            if (!lc.isArray()) {
                p.add(UiProblem.error("BC-01", path(at, "local-chips"), "'local-chips' must be a list of chip documents", null));
            } else {
                for (int i = 0; i < lc.size(); i++) {
                    ChipDoc c = chip(lc.get(i), path(at, "local-chips[" + i + "]"), p);
                    if (c != null) {
                        locals.add(c);
                    }
                }
            }
        }
        List<Trace> traces = new ArrayList<>();
        JsonNode tr = n.path("traces");
        if (!tr.isMissingNode() && !tr.isNull()) {
            if (!tr.isArray()) {
                p.add(UiProblem.error("BC-01", path(at, "traces"), "'traces' must be a list of { from, to }", null));
            } else {
                for (int i = 0; i < tr.size(); i++) {
                    traces.add(trace(tr.get(i), path(at, "traces[" + i + "]"), p));
                }
            }
        }
        return new BoardDoc(rachana, name, version, text(n, "title"), text(n, "description"), params, chips, locals, traces,
                layout(n.path("layout"), path(at, "layout"), p), text(n, "owner"), text(n, "workspace"));
    }

    private static Instance instance(String name, JsonNode n, String at, List<UiProblem> p) {
        if (!n.isObject()) {
            p.add(UiProblem.error("BC-01", at, "a chip instance must be a mapping with 'use:'", null));
            return new Instance(name, null, null, null, null, Map.of(), Map.of(), null);
        }
        unknownKeys(n, INSTANCE_KEYS, at, p);
        String use = text(n, "use");
        ChipRef ref = ChipRef.parse(use).orElse(null);
        if (use == null) {
            p.add(UiProblem.error("BC-01", path(at, "use"), "the instance names no chip", "add use: <chip>@<version>"));
        }
        Supply supply = null;
        JsonNode s = n.path("supply");
        if (!s.isMissingNode() && !s.isNull()) {
            supply = supply(s, path(at, "supply"), p);
        }
        Map<String, Mapping> map = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = mapping(n, "map", at, p, false); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v.isArray()) {
                List<String> attrs = new ArrayList<>();
                v.forEach(x -> attrs.add(x.asText()));
                map.put(e.getKey(), new Mapping(e.getKey(), attrs, true));
            } else if (v.isValueNode() && !v.isNull()) {
                map.put(e.getKey(), new Mapping(e.getKey(), List.of(v.asText()), false));
            } else {
                p.add(UiProblem.error("BC-01", path(at, "map." + e.getKey()), "a map value is a supplier attribute or a list of them", null));
            }
        }
        Map<String, JsonNode> controls = new LinkedHashMap<>();
        mapping(n, "controls", at, p, false).forEachRemaining(e -> controls.put(e.getKey(), e.getValue()));
        return new Instance(name, ref, use, text(n, "title"), supply, map, controls, text(n, "follow"));
    }

    private static Supply supply(JsonNode n, String at, List<UiProblem> p) {
        if (!n.isObject()) {
            p.add(UiProblem.error("BC-01", at, "a supply names one supplier: connector, model, dataset or live", null));
            return null;
        }
        Supply found = null;
        for (SupplyKind k : SupplyKind.values()) {
            String v = text(n, k.yamlName());
            if (v != null) {
                if (found != null) {
                    p.add(UiProblem.error("BC-01", at, "a supply names one supplier, not " + found.kind().yamlName() + " and " + k.yamlName(), null));
                } else {
                    found = new Supply(k, v, n.has("query") ? n.get("query") : null);
                }
            }
        }
        n.fieldNames().forEachRemaining(f -> {
            if (!f.equals("query") && SupplyKindNames.NAMES.stream().noneMatch(f::equals)) {
                p.add(UiProblem.error("BC-01", path(at, f), "unknown key '" + f + "' in a supply", "use connector, model, dataset or live, and query"));
            }
        });
        if (found == null) {
            p.add(UiProblem.error("BC-01", at, "a supply names one supplier: connector, model, dataset or live", null));
        }
        return found;
    }

    private static final class SupplyKindNames {
        static final List<String> NAMES = java.util.Arrays.stream(SupplyKind.values()).map(SupplyKind::yamlName).toList();
    }

    private static Trace trace(JsonNode n, String at, List<UiProblem> p) {
        String from = text(n, "from");
        String to = text(n, "to");
        if (from == null || to == null) {
            p.add(UiProblem.error("BC-01", at, "a trace needs 'from:' and 'to:'", "write { from: <instance>.<out pin>, to: <instance>.<in pin> }"));
        }
        unknownKeys(n, Set.of("from", "to", "when", "transform"), at, p);
        Transform t = null;
        JsonNode tn = n.path("transform");
        if (tn.isTextual() && tn.asText().equals("first")) {
            t = new Transform(TransformKind.FIRST, null);
        } else if (tn.isTextual() && tn.asText().equals("date")) {
            t = new Transform(TransformKind.DATE, null);
        } else if (tn.isObject() && tn.path("filter-on").isTextual() && tn.size() == 1) {
            t = new Transform(TransformKind.FILTER_ON, tn.get("filter-on").asText());
        } else if (!tn.isMissingNode() && !tn.isNull()) {
            p.add(UiProblem.error("BC-01", path(at, "transform"), "unknown transform " + tn, "use first, date or { filter-on: <pin>.<column> }"));
        }
        return new Trace(from, to, n.has("when") ? n.get("when") : null, t);
    }

    private static Layout layout(JsonNode n, String at, List<UiProblem> p) {
        if (n.isMissingNode() || n.isNull()) {
            return Layout.EMPTY;
        }
        unknownKeys(n, Set.of("theme", "desktop", "tablet", "phone"), at, p);
        Phone phone = null;
        JsonNode ph = n.path("phone");
        if (ph.isObject()) {
            phone = new Phone(texts(ph.path("order")), texts(ph.path("collapsed")));
        }
        return new Layout(text(n, "theme"), grid(n.path("desktop"), path(at, "desktop"), p), grid(n.path("tablet"), path(at, "tablet"), p), phone);
    }

    private static Grid grid(JsonNode n, String at, List<UiProblem> p) {
        if (!n.isObject()) {
            return null;
        }
        int columns = n.path("columns").asInt(12);
        List<Area> areas = new ArrayList<>();
        JsonNode a = n.path("areas");
        for (int i = 0; i < a.size(); i++) {
            JsonNode x = a.get(i);
            JsonNode atN = x.path("at");
            JsonNode span = x.path("span");
            if (!x.path("chip").isTextual() || atN.size() != 2 || (!span.isMissingNode() && span.size() != 2)) {
                p.add(UiProblem.error("BC-01", path(at, "areas[" + i + "]"), "an area is { chip, at: [column, row], span: [columns, rows] }", null));
                continue;
            }
            areas.add(new Area(x.get("chip").asText(), atN.get(0).asInt(), atN.get(1).asInt(), span.isMissingNode() ? 1 : span.get(0).asInt(),
                    span.isMissingNode() ? 1 : span.get(1).asInt()));
        }
        return new Grid(columns, areas);
    }

    // ------------------------------------------------------------------------------------------------ chip

    /** A chip from its tree; null only when it is not a mapping. */
    public static ChipDoc chip(JsonNode n, String at, List<UiProblem> p) {
        if (!n.isObject()) {
            p.add(UiProblem.error("BC-01", at, "a chip document must be a mapping of keys", null));
            return null;
        }
        unknownKeys(n, CHIP_KEYS, at, p);
        int rachana = rachana(n, at, p);
        String name = name(n, "chip", at, p);
        int version = 0;
        if (!n.has("version")) {
            p.add(UiProblem.error("BC-01", path(at, "version"), "a chip needs 'version:', a positive integer", "add version: 1"));
        } else {
            version = positive(n.get("version"), path(at, "version"), p);
        }
        for (String k : List.of("title", "category", "manual", "pins", "body")) {
            if (!n.has(k)) {
                p.add(UiProblem.error("BC-01", path(at, k), "a chip needs '" + k + ":'", null));
            }
        }
        List<Integer> compatible = new ArrayList<>();
        n.path("compatible-with").forEach(v -> compatible.add(v.asInt()));
        Map<String, PinSpec> in = new LinkedHashMap<>();
        Map<String, PinSpec> out = new LinkedHashMap<>();
        JsonNode pins = n.path("pins");
        unknownKeys(pins, Set.of("in", "out"), path(at, "pins"), p);
        for (Iterator<Map.Entry<String, JsonNode>> it = mapping(pins, "in", path(at, "pins"), p, false); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String pp = path(at, "pins.in." + e.getKey());
            wellFormed(e.getKey(), pp, p);
            String role = e.getValue().path("role").asText("");
            if (!role.equals("data") && !role.equals("control")) {
                p.add(UiProblem.error("BC-01", path(pp, "role"), "an in pin's role is data or control", "add role: data (rows, a document) or role: control"));
            }
            PinSpec s = pin(e.getKey(), e.getValue(), role.equals("data") ? PinRole.DATA : PinRole.CONTROL, pp, p);
            if (s != null) {
                in.put(e.getKey(), s);
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = mapping(pins, "out", path(at, "pins"), p, false); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String pp = path(at, "pins.out." + e.getKey());
            wellFormed(e.getKey(), pp, p);
            if (in.containsKey(e.getKey())) {
                p.add(UiProblem.error("BC-01", pp, "'" + e.getKey() + "' is both an in pin and an out pin", "rename one"));
            }
            PinSpec s = pin(e.getKey(), e.getValue(), PinRole.OUT, pp, p);
            if (s != null) {
                out.put(e.getKey(), s);
            }
        }
        Map<String, JsonNode> limits = new LinkedHashMap<>();
        mapping(n, "limits", at, p, false).forEachRemaining(e -> limits.put(e.getKey(), e.getValue()));
        return new ChipDoc(rachana, name, version, text(n, "title"), text(n, "category"), texts(n.path("tags")), compatible,
                manual(n.path("manual"), path(at, "manual"), p), in, out, limits, n.has("body") ? n.get("body") : MissingNode.getInstance());
    }

    private static ChipManual manual(JsonNode n, String at, List<UiProblem> p) {
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        unknownKeys(n, Set.copyOf(ChipManual.KEYS), at, p);
        Map<String, String> pins = new LinkedHashMap<>();
        n.path("pins").fields().forEachRemaining(e -> pins.put(e.getKey(), e.getValue().asText()));
        List<ChipManual.Example> examples = new ArrayList<>();
        JsonNode ex = n.path("examples");
        for (int i = 0; i < ex.size(); i++) {
            JsonNode b = ex.get(i).path("board");
            String board = b.isTextual() ? b.asText() : b.isObject() ? b.toString() : null;
            examples.add(new ChipManual.Example(ex.get(i).path("title").asText(null), board));
        }
        return new ChipManual(text(n, "purpose"), pins, text(n, "behaviour"), text(n, "defaults"), text(n, "limits"), examples,
                text(n, "not-for"), text(n, "accessibility"));
    }

    private static PinSpec pin(String name, JsonNode n, PinRole role, String at, List<UiProblem> p) {
        if (!n.isObject()) {
            p.add(UiProblem.error("BC-01", at, "a pin is a mapping with 'type:'", null));
            return null;
        }
        unknownKeys(n, PIN_KEYS, at, p);
        TypeRef t = typeOf(n, at, p);
        if (t == null) {
            return null;
        }
        String of = text(n, "of");
        String ofPin = null;
        if (of != null && !of.contains("/")) {          // an out pin's of: <pin>.<column>; a kind is <pack>/<kind>
            ofPin = of;
            t = t.withKind(null);
        }
        Map<String, ColumnSpec> shape = new LinkedHashMap<>();
        JsonNode sh = n.path("shape");
        if (!sh.isMissingNode()) {
            if (t.type() != PinType.ROWSET && t.type() != PinType.DOCUMENT) {
                p.add(UiProblem.error("BC-01", path(at, "shape"), "only a rowset or document pin has a shape", null));
            }
            sh.fields().forEachRemaining(e -> {
                String cp = path(at, "shape." + e.getKey());
                if (e.getValue().isObject()) {
                    unknownKeys(e.getValue(), COLUMN_KEYS, cp, p);
                }
                TypeRef ct = typeOf(e.getValue(), cp, p);
                if (ct != null) {
                    JsonNode c = e.getValue();
                    shape.put(e.getKey(), new ColumnSpec(e.getKey(), ct, c.path("required").asBoolean(false), c.path("many").asBoolean(false),
                            c.path("max").asInt(0)));
                }
            });
        } else if (t.type() == PinType.ROWSET && role != PinRole.OUT) {
            p.add(UiProblem.error("BC-01", path(at, "shape"), "a rowset in pin declares its shape (the columns the chip reads)", "add shape: { id: { type: id } }"));
        }
        return new PinSpec(name, role, t, n.path("required").asBoolean(false), n.has("default") ? n.get("default") : null, shape, ofPin,
                text(n, "shape-of"));
    }

    /** A type with refinements, from {@code { type: id, of: ... }} or the short form {@code date}. Null (and a problem) when unknown. */
    static TypeRef typeOf(JsonNode n, String at, List<UiProblem> p) {
        String type = n.isTextual() ? n.asText() : text(n, "type");
        PinType t = PinType.of(type).orElse(null);
        if (t == null) {
            p.add(UiProblem.error("BC-01", path(at, "type"), type == null ? "no type" : "unknown type '" + type + "'",
                    "use one of id, text, number, bool, enum, date, datetime, period, measure, dimension, filter, selection, document, rowset"));
            return null;
        }
        if (n.isTextual()) {
            return TypeRef.of(t);
        }
        if (t == PinType.ENUM && !n.path("values").isArray()) {
            p.add(UiProblem.error("BC-01", path(at, "values"), "an enum pin lists its values", "add values: [a, b]"));
        }
        return new TypeRef(t, text(n, "of"), text(n, "unit"), text(n, "ccy"), n.path("min").isNumber() ? n.get("min").asDouble() : null,
                n.path("max").isNumber() && t == PinType.NUMBER ? n.get("max").asDouble() : null, n.path("integer").asBoolean(false), texts(n.path("values")));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static int rachana(JsonNode n, String at, List<UiProblem> p) {
        if (!n.has("rachana")) {
            p.add(UiProblem.error("BC-01", path(at, "rachana"), "the document needs 'rachana: 1' (the grammar version)", "add rachana: 1"));
            return 0;
        }
        if (n.get("rachana").asInt(-1) != 1) {
            p.add(UiProblem.error("BC-01", path(at, "rachana"), "only grammar version 1 is known, not " + n.get("rachana"), "write rachana: 1"));
        }
        return n.get("rachana").asInt(0);
    }

    private static String name(JsonNode n, String key, String at, List<UiProblem> p) {
        String v = text(n, key);
        if (v == null) {
            p.add(UiProblem.error("BC-01", path(at, key), "the document needs '" + key + ":', its name", null));
            return null;
        }
        wellFormed(v, path(at, key), p);
        return v;
    }

    private static void wellFormed(String name, String at, List<UiProblem> p) {
        if (!ChipRef.NAME.matcher(name).matches()) {
            p.add(UiProblem.error("BC-01", at, "'" + name + "' is not a well-formed name", "use lower-case letters, digits and hyphens, starting with a letter, up to 64"));
        }
    }

    private static int positive(JsonNode v, String at, List<UiProblem> p) {
        if (!v.canConvertToInt() || !v.isIntegralNumber() || v.asInt() < 1) {
            p.add(UiProblem.error("BC-01", at, "a version is a positive integer, not " + v, null));
            return 0;
        }
        return v.asInt();
    }

    private static Iterator<Map.Entry<String, JsonNode>> mapping(JsonNode n, String key, String at, List<UiProblem> p, boolean required) {
        JsonNode v = n.path(key);
        if (v.isMissingNode() || v.isNull()) {
            return java.util.Collections.emptyIterator();
        }
        if (!v.isObject()) {
            p.add(UiProblem.error("BC-01", path(at, key), "'" + key + "' must be a mapping", null));
            return java.util.Collections.emptyIterator();
        }
        return v.fields();
    }

    private static void unknownKeys(JsonNode n, Set<String> allowed, String at, List<UiProblem> p) {
        if (!n.isObject()) {
            return;
        }
        n.fieldNames().forEachRemaining(k -> {
            if (!allowed.contains(k)) {
                p.add(UiProblem.error("BC-01", path(at, k), "unknown key '" + k + "'", "use one of " + new java.util.TreeSet<>(allowed)));
            }
        });
    }

    static String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isValueNode() && !v.isNull() ? v.asText() : null;
    }

    private static List<String> texts(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> out.add(x.asText()));
        }
        return out;
    }

    static String path(String at, String key) {
        return at == null || at.isEmpty() ? key : at + "." + key;
    }
}
