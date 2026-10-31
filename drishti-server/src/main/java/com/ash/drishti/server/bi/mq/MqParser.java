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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Step 1 of the model query's validation (docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, section 5.3): reads an MQ from YAML or JSON (the same keys) and checks its shape,
 * {@code DRS-9150}: an unknown key, a value of the wrong type, {@code mq} missing or not 1, two time forms, {@code params} (wave W6). Also writes the canonical
 * form of a query (keys sorted, defaults filled), which the answer's {@code meta.query} shows and the result cache keys on, stable under key order.
 */
public final class MqParser {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    static final Set<String> TOP = Set.of("mq", "model", "define", "select", "where", "having", "time", "sort", "limit", "totals", "drill", "shape", "params", "calc",
            "perspective", "options");
    /** The comparisons a {@code where} may make: the engine's, plus {@code relative} and {@code top}. */
    public static final Set<String> OPS;

    static {
        Set<String> ops = new java.util.TreeSet<>(QueryRequest.OPS);
        ops.add("relative");
        ops.add("top");
        OPS = Set.copyOf(ops);
    }

    private MqParser() {}

    /** Reads YAML or JSON text. */
    public static ModelQuery parse(String text) {
        JsonNode root;
        try {
            root = text == null || text.isBlank() ? null : YAML.readTree(text);
        } catch (JsonProcessingException e) {
            throw new MqException(MqException.Problem.of("DRS-9150", "", "the query is not valid YAML or JSON: " + com.ash.drishti.server.bi.YamlErrors.describe(e)));
        }
        return parse(root);
    }

    public static ModelQuery parse(JsonNode root) {
        List<MqException.Problem> out = new ArrayList<>();
        if (root == null || !root.isObject()) {
            throw new MqException(MqException.Problem.of("DRS-9150", "", "a model query is a mapping: { mq: 1, model, select: { fields, measures }, where, ... }"));
        }
        root.fieldNames().forEachRemaining(k -> {
            if (!TOP.contains(k)) {
                out.add(MqException.Problem.of("DRS-9150", k, "unknown key '" + k + "' (the keys are " + new java.util.TreeSet<>(TOP) + ")"));
            }
        });
        int version = root.path("mq").canConvertToInt() ? root.path("mq").asInt() : 0;
        if (version != 1 && version != 2) {
            out.add(new MqException.Problem("DRS-9150", "mq", "a model query starts `mq: 1` or `mq: 2` (the language version; 2 adds params, calc, perspective and options.budget)",
                    Map.of("set", "mq", "to", 1)));
        }
        if (version == 1) {
            for (String k : List.of("params", "calc", "perspective")) {
                if (present(root.path(k))) {
                    out.add(new MqException.Problem("DRS-9150", k, "`" + k + ":` is a key of `mq: 2`", Map.of("set", "mq", "to", 2)));
                }
            }
        }
        String model = root.path("model").isTextual() ? root.path("model").asText() : "";
        Map<String, String> define = new LinkedHashMap<>();
        JsonNode d = root.path("define");
        if (!d.isMissingNode() && !d.isNull()) {
            if (!d.isObject()) {
                out.add(MqException.Problem.of("DRS-9150", "define", "`define:` maps a local measure's name to its expression"));
            } else {
                d.fields().forEachRemaining(e -> {
                    JsonNode v = e.getValue();
                    String expr = v.isTextual() ? v.asText() : v.path("expr").asText("");
                    if (expr.isBlank()) {
                        out.add(MqException.Problem.of("DRS-9150", "define." + e.getKey(), "a local measure is an expression (text)"));
                    }
                    define.put(e.getKey(), expr);
                });
            }
        }
        JsonNode select = root.path("select");
        if (!select.isMissingNode() && !select.isObject()) {
            out.add(MqException.Problem.of("DRS-9150", "select", "`select:` is { fields: [...], measures: [...] }"));
        }
        select.fieldNames().forEachRemaining(k -> {
            if (!Set.of("fields", "measures").contains(k)) {
                out.add(MqException.Problem.of("DRS-9150", "select." + k, "select has fields and measures only"));
            }
        });
        List<String> fields = names(select.path("fields"), "select.fields", out);
        List<String> measures = names(select.path("measures"), "select.measures", out);
        List<ModelQuery.Where> where = where(root.path("where"), out);
        List<ModelQuery.Having> having = having(root.path("having"), out);
        ModelQuery.Time time = time(root.path("time"), out);
        List<ModelQuery.Sort> sort = new ArrayList<>();
        int i = 0;
        for (JsonNode s : list(root.path("sort"), "sort", out)) {
            if (s.isTextual()) {
                sort.add(new ModelQuery.Sort(s.asText(), false));
            } else if (s.path("by").isTextual()) {
                sort.add(new ModelQuery.Sort(s.path("by").asText(), s.path("desc").asBoolean(false) || "desc".equalsIgnoreCase(s.path("dir").asText())));
            } else {
                out.add(MqException.Problem.of("DRS-9150", "sort[" + i + "]", "a sort is { by: <field or measure>, desc: true|false }"));
            }
            i++;
        }
        Integer limit = null;
        if (root.has("limit")) {
            if (!root.get("limit").canConvertToInt() || root.get("limit").asInt() < 1) {
                out.add(MqException.Problem.of("DRS-9150", "limit", "limit is a whole number from 1"));
            } else {
                limit = root.get("limit").asInt();
            }
        }
        ModelQuery.Totals totals = totals(root.path("totals"), out);
        ModelQuery.Drill drill = null;
        JsonNode dr = root.path("drill");
        if (!dr.isMissingNode() && !dr.isNull()) {
            if (!dr.path("hierarchy").isTextual()) {
                out.add(MqException.Problem.of("DRS-9150", "drill", "a drill is { hierarchy: <name>, path: [values clicked] }"));
            } else {
                List<String> path = new ArrayList<>();
                list(dr.path("path"), "drill.path", out).forEach(v -> path.add(v.asText()));
                drill = new ModelQuery.Drill(dr.path("hierarchy").asText(), path);
            }
        }
        String shape = root.path("shape").asText(ModelQuery.GROUPED);
        if (!Set.of(ModelQuery.GROUPED, ModelQuery.ROWS).contains(shape)) {
            out.add(MqException.Problem.of("DRS-9150", "shape", "shape is grouped or rows"));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (root.path("params").isObject()) {
            root.path("params").fields().forEachRemaining(e -> params.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class)));
        }
        if (!root.path("params").isMissingNode() && !root.path("params").isNull() && !root.path("params").isObject()) {
            out.add(MqException.Problem.of("DRS-9150", "params", "`params:` maps a parameter's name to its value: { shock: -0.1 }"));
        }
        Map<String, String> calc = new LinkedHashMap<>();
        JsonNode cg = root.path("calc");
        if (!cg.isMissingNode() && !cg.isNull()) {
            if (!cg.isObject()) {
                out.add(MqException.Problem.of("DRS-9150", "calc", "`calc:` maps a calculation group to the item applied to the selected measures: { Time: YTD }"));
            } else {
                cg.fields().forEachRemaining(e -> {
                    if (!e.getValue().isTextual()) {
                        out.add(MqException.Problem.of("DRS-9150", "calc." + e.getKey(), "an item of a calculation group is its name (text)"));
                    } else {
                        calc.put(e.getKey(), e.getValue().asText());
                    }
                });
            }
        }
        String perspective = root.path("perspective").isTextual() ? root.path("perspective").asText() : null;
        if (root.has("perspective") && !root.path("perspective").isTextual() && !root.path("perspective").isNull()) {
            out.add(MqException.Problem.of("DRS-9150", "perspective", "`perspective:` names one perspective of the model"));
        }
        JsonNode o = root.path("options");
        ModelQuery.Options options = new ModelQuery.Options(o.path("layer").asText("auto"), o.path("explain").asBoolean(false), o.path("format").asText("json"));
        ModelQuery.Budget budget = ModelQuery.Budget.NONE;
        JsonNode bn = o.path("budget");
        if (!bn.isMissingNode() && !bn.isNull()) {
            Long millis = bn.path("millis").canConvertToLong() ? bn.path("millis").asLong() : null;
            Long rows = bn.path("rows").canConvertToLong() ? bn.path("rows").asLong() : null;
            if (!bn.isObject() || millis == null && rows == null || millis != null && millis < 1 || rows != null && rows < 1) {
                out.add(MqException.Problem.of("DRS-9150", "options.budget", "a budget is { millis: <most milliseconds>, rows: <most rows read> }, each a whole number from 1"));
            } else {
                budget = new ModelQuery.Budget(millis, rows);
            }
        }
        if (!Set.of("auto", "columns", "rollup", "direct").contains(options.layer())) {
            out.add(MqException.Problem.of("DRS-9150", "options.layer", "layer is auto, columns, rollup or direct"));
        }
        if (!Set.of("json", "arrow").contains(options.format())) {
            out.add(MqException.Problem.of("DRS-9150", "options.format", "format is json or arrow"));
        }
        if (fields.isEmpty() && measures.isEmpty() && out.isEmpty()) {
            out.add(MqException.Problem.of("DRS-9150", "select", "a query asks for at least one field or measure"));
        }
        if (!out.isEmpty()) {
            throw new MqException(out);
        }
        return new ModelQuery(version, model, define, fields, measures, where, having, time, sort, limit, totals, drill, shape, params, options,
                new ModelQuery.Ext(calc, perspective, budget));
    }

    /** True when a key holds something (not missing, null, an empty mapping or blank text). */
    private static boolean present(JsonNode n) {
        return !n.isMissingNode() && !n.isNull() && !(n.isContainerNode() && n.isEmpty()) && !(n.isTextual() && n.asText().isBlank());
    }

    private static List<JsonNode> list(JsonNode n, String path, List<MqException.Problem> out) {
        List<JsonNode> l = new ArrayList<>();
        if (n.isMissingNode() || n.isNull()) {
            return l;
        }
        if (!n.isArray()) {
            out.add(MqException.Problem.of("DRS-9150", path, "'" + path + "' is a list"));
            return l;
        }
        n.forEach(l::add);
        return l;
    }

    private static List<String> names(JsonNode n, String path, List<MqException.Problem> out) {
        List<String> l = new ArrayList<>();
        int i = 0;
        for (JsonNode x : list(n, path, out)) {
            if (!x.isTextual() || x.asText().isBlank()) {
                out.add(MqException.Problem.of("DRS-9150", path + "[" + i + "]", "a name (text)"));
            } else {
                l.add(x.asText());
            }
            i++;
        }
        return l;
    }

    private static List<ModelQuery.Where> where(JsonNode n, List<MqException.Problem> out) {
        List<ModelQuery.Where> l = new ArrayList<>();
        int i = 0;
        for (JsonNode w : list(n, "where", out)) {
            String path = "where[" + i++ + "]";
            String op = w.path("op").asText("eq").toLowerCase(Locale.ROOT);
            if (!w.path("field").isTextual()) {
                out.add(MqException.Problem.of("DRS-9150", path, "a filter is { field, op, value | values }"));
                continue;
            }
            if (!OPS.contains(op)) {
                out.add(MqException.Problem.of("DRS-9150", path + ".op", "op is one of " + new java.util.TreeSet<>(OPS) + ", not '" + op + "'"));
                continue;
            }
            List<Object> values = values(w, path, out);
            Integer top = w.has("n") && w.get("n").canConvertToInt() ? w.get("n").asInt() : null;
            l.add(new ModelQuery.Where(w.path("field").asText(), op, values, w.has("window") ? w.get("window").asText() : null, top,
                    w.has("by") ? w.get("by").asText() : null, w.path("dir").asText("desc")));
        }
        return l;
    }

    private static List<Object> values(JsonNode w, String path, List<MqException.Problem> out) {
        List<Object> values = new ArrayList<>();
        JsonNode vs = w.has("values") ? w.get("values") : w.get("value");
        if (vs == null || vs.isNull()) {
            return values;
        }
        for (JsonNode v : vs.isArray() ? vs : List.of(vs)) {
            if (v.isIntegralNumber() && v.canConvertToLong()) {
                values.add(v.asLong());
            } else if (v.isNumber()) {
                values.add(v.asDouble());
            } else if (v.isBoolean()) {
                values.add(v.asBoolean());
            } else if (v.isTextual()) {
                values.add(v.asText());
            } else {
                out.add(MqException.Problem.of("DRS-9150", path + ".values", "a value is a number, text or true/false"));
            }
        }
        return values;
    }

    private static List<ModelQuery.Having> having(JsonNode n, List<MqException.Problem> out) {
        List<ModelQuery.Having> l = new ArrayList<>();
        int i = 0;
        for (JsonNode h : list(n, "having", out)) {
            String path = "having[" + i++ + "]";
            if (!h.path("measure").isTextual()) {
                out.add(MqException.Problem.of("DRS-9150", path, "a having is { measure, op, value }"));
                continue;
            }
            l.add(new ModelQuery.Having(h.path("measure").asText(), h.path("op").asText("eq").toLowerCase(Locale.ROOT), values(h, path, out)));
        }
        return l;
    }

    private static ModelQuery.Time time(JsonNode t, List<MqException.Problem> out) {
        if (t.isMissingNode() || t.isNull()) {
            return ModelQuery.Time.DEFAULT;
        }
        if (!t.isObject()) {
            out.add(MqException.Problem.of("DRS-9150", "time", "time is one of { as-of }, { window }, { live: true }"));
            return ModelQuery.Time.DEFAULT;
        }
        int forms = (t.has("as-of") ? 1 : 0) + (t.has("window") ? 1 : 0) + (t.path("live").asBoolean(false) ? 1 : 0);
        if (forms > 1) {
            out.add(MqException.Problem.of("DRS-9150", "time", "time takes one form: as-of, window or live, not " + forms));
        }
        t.fieldNames().forEachRemaining(k -> {
            if (!Set.of("as-of", "window", "live").contains(k)) {
                out.add(MqException.Problem.of("DRS-9150", "time." + k, "time has as-of, window or live"));
            }
        });
        LocalDate asOf = date(t.path("as-of"), "time.as-of", out);
        JsonNode w = t.path("window");
        LocalDate from = date(w.path("from"), "time.window.from", out);
        LocalDate to = date(w.path("to"), "time.window.to", out);
        Duration last = null;
        if (w.has("last")) {
            last = DatasetParser.span(w.get("last").asText());
            if (last == null) {
                out.add(MqException.Problem.of("DRS-9150", "time.window.last", "a sliding window such as 1h, 30m or PT1H"));
            }
        }
        if (w.isObject() && last == null && from == null && !w.has("last")) {
            out.add(MqException.Problem.of("DRS-9150", "time.window", "a window is { from, to } or { last: 1h }"));
        }
        if (from != null && to == null) {
            to = from;
        }
        if (from != null && to.isBefore(from)) {
            out.add(MqException.Problem.of("DRS-9150", "time.window", "the window ends before it starts"));
        }
        return new ModelQuery.Time(asOf, from, to, last, t.path("live").asBoolean(false));
    }

    private static LocalDate date(JsonNode n, String path, List<MqException.Problem> out) {
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        try {
            return LocalDate.parse(n.asText());
        } catch (RuntimeException e) {
            out.add(MqException.Problem.of("DRS-9150", path, "a date is yyyy-mm-dd, not '" + n.asText() + "'"));
            return null;
        }
    }

    private static ModelQuery.Totals totals(JsonNode t, List<MqException.Problem> out) {
        if (t.isMissingNode() || t.isNull()) {
            return null;
        }
        if (t.isTextual()) {
            if (!Set.of("grand", "subtotals").contains(t.asText())) {
                out.add(MqException.Problem.of("DRS-9150", "totals", "totals is grand, subtotals or { levels: [[...], ...] }"));
            }
            return new ModelQuery.Totals(t.asText(), List.of());
        }
        List<List<String>> levels = new ArrayList<>();
        int i = 0;
        for (JsonNode l : list(t.path("levels"), "totals.levels", out)) {
            levels.add(names(l, "totals.levels[" + i++ + "]", out));
        }
        return new ModelQuery.Totals("levels", levels);
    }

    // ---- the canonical form ------------------------------------------------------------------------------------------

    /** The query as a sorted tree with its defaults written: what ran, shown in the answer's meta. */
    public static Map<String, Object> canonical(ModelQuery q) {
        Map<String, Object> m = new TreeMap<>();
        boolean two = !q.params().isEmpty() || !q.ext().empty();
        m.put("mq", two ? 2 : 1);
        m.put("model", q.model());
        if (!q.define().isEmpty()) {
            m.put("define", new TreeMap<>(q.define()));
        }
        if (!q.params().isEmpty()) {
            m.put("params", new TreeMap<>(q.params()));
        }
        if (!q.ext().calc().isEmpty()) {
            m.put("calc", new TreeMap<>(q.ext().calc()));
        }
        if (q.ext().perspective() != null) {
            m.put("perspective", q.ext().perspective());
        }
        Map<String, Object> select = new TreeMap<>();
        select.put("fields", q.fields());
        select.put("measures", q.measures());
        m.put("select", select);
        if (!q.where().isEmpty()) {
            m.put("where", q.where().stream().map(w -> {
                Map<String, Object> x = new TreeMap<>();
                x.put("field", w.field());
                x.put("op", w.op());
                if (!w.values().isEmpty()) {
                    x.put("values", w.values());
                }
                if (w.window() != null) {
                    x.put("window", w.window());
                }
                if (w.n() != null) {
                    x.put("n", w.n());
                    x.put("by", w.by());
                    x.put("dir", w.dir());
                }
                return x;
            }).toList());
        }
        if (!q.having().isEmpty()) {
            m.put("having", q.having().stream().map(h -> new TreeMap<>(Map.of("measure", h.measure(), "op", h.op(), "values", h.values()))).toList());
        }
        ModelQuery.Time t = q.time();
        if (t.asOf() != null) {
            m.put("time", Map.of("as-of", t.asOf().toString()));
        } else if (t.from() != null) {
            m.put("time", Map.of("window", new TreeMap<>(Map.of("from", t.from().toString(), "to", t.to().toString()))));
        } else if (t.last() != null) {
            m.put("time", Map.of("window", Map.of("last", t.last().toString())));
        } else if (t.live()) {
            m.put("time", Map.of("live", true));
        }
        if (!q.sort().isEmpty()) {
            m.put("sort", q.sort().stream().map(s -> new TreeMap<>(Map.of("by", s.by(), "desc", s.desc()))).toList());
        }
        if (q.limit() != null) {
            m.put("limit", q.limit());
        }
        if (q.totals() != null) {
            m.put("totals", "levels".equals(q.totals().mode()) ? Map.of("levels", q.totals().levels()) : q.totals().mode());
        }
        if (q.drill() != null) {
            m.put("drill", new TreeMap<>(Map.of("hierarchy", q.drill().hierarchy(), "path", q.drill().path())));
        }
        m.put("shape", q.shape());
        Map<String, Object> o = new TreeMap<>();
        o.put("layer", q.options().layer());
        o.put("format", q.options().format());
        ModelQuery.Budget b = q.ext().budget();
        if (!b.equals(ModelQuery.Budget.NONE)) {
            Map<String, Object> bm = new TreeMap<>();
            if (b.millis() != null) {
                bm.put("millis", b.millis());
            }
            if (b.rows() != null) {
                bm.put("rows", b.rows());
            }
            o.put("budget", bm);
        }
        m.put("options", o);
        return m;
    }

    /** A short fingerprint of the canonical form (the same query in any key order has the same hash). */
    public static String hash(ModelQuery q) {
        try {
            byte[] text = JSON.writeValueAsBytes(canonical(q));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text)).substring(0, 16);
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The canonical form as JSON text. */
    public static String json(ModelQuery q) {
        try {
            return new String(JSON.writeValueAsBytes(canonical(q)), StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
