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
package com.ash.drishti.server.bi.python;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.mq.ModelCatalog;
import com.ash.drishti.server.bi.mq.ModelQuery;
import com.ash.drishti.server.bi.mq.MqParser;
import com.ash.drishti.server.bi.mq.MqService;
import com.ash.drishti.server.bi.mq.TransformInputs;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The rows a Python run reads, read on the server as the person running it (their row rules and masks apply, as in any query of theirs): a
 * model query ({@code {mq: {...}}}), a dataset's or model's fields ({@code {dataset|model, fields, filters?, dates?}}, the Notebook's extract and a
 * test's {@code sample}), one input of a Transform ({@code {input: ...}}, as {@code /api/v1/bi/python/input} reads it) or one visual's rows
 * ({@code {report, page, visual, state}}). Answered as an Arrow stream: the row {@code id} left out, names shortened as the Notebook shows them
 * ({@code trade.desk} is {@code desk}; a name two tables share keeps its table, {@code trade_desk}), cut to the limit.
 */
public final class PythonFrames {

    /** One read: the Arrow stream, its rows, the rows there were before the cut, the masked columns (short names), the model and the business date. */
    public record Read(byte[] arrow, int rows, int total, List<String> masked, String model, String asOf, List<Map<String, String>> columns) {

        /** True when the rows were cut to the limit. */
        public boolean cut() {
            return total > rows;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9_]");

    private final ModelCatalog catalog;
    private final MqService mq;
    private final BiReportService reports;

    public PythonFrames(ModelCatalog catalog, MqService mq, BiReportService reports) {
        this.catalog = catalog;
        this.mq = mq;
        this.reports = reports;
    }

    /** The rows {@code spec} names, as {@code who} may see them, at most {@code limit}. */
    public Read read(Principal who, JsonNode spec, int limit) {
        if (spec == null || !spec.isObject()) {
            throw new DrishtiException(ErrorCode.PYTHON_INVALID, "send the rows to read: {mq}, {dataset|model, fields}, {input} or {report, page, visual}");
        }
        if (spec.hasNonNull("report")) {
            String page = spec.path("page").asText("");
            String visual = spec.path("visual").asText("");
            if (page.isBlank() || visual.isBlank()) {
                throw new DrishtiException(ErrorCode.PYTHON_INVALID, "send {report, page, visual} to read a visual's rows");
            }
            BiReportService.ArrowAnswer a = reports.queryArrow(who, spec.path("report").asText(), page, visual, spec.get("state"));
            Object m = a.meta().get("masked");
            List<String> masked = new ArrayList<>();
            if (m instanceof java.util.Collection<?> c) {
                c.forEach(x -> masked.add(String.valueOf(x)));
            }
            int rows = a.meta().get("rows") instanceof Number n ? n.intValue() : 0;
            return new Read(a.bytes(), rows, rows, masked, "", "", List.of());
        }
        if (spec.hasNonNull("input")) {
            TransformInputs.Asked a = TransformInputs.ask(catalog, who, spec.get("input"), time(spec), limit);
            ModelQuery q = a.query();
            if (q.limit() == null || q.limit() > limit) {
                q = q.with(q.fields(), q.where(), limit);
            }
            MqService.Ran ran = mq.run(a.bound(), q, false, false);
            QueryService.Result r = TransformInputs.bare(ran.shown(), a.alias());
            String asOf = r.meta() != null && !r.meta().dates().isEmpty() ? String.valueOf(r.meta().dates().get(r.meta().dates().size() - 1))
                    : String.valueOf(ran.compiled().asOf());
            return frame(r, limit, a.ref(), asOf);
        }
        JsonNode body = spec.hasNonNull("mq") ? spec.get("mq") : rowsQuery(spec, limit);
        ModelQuery q = MqParser.parse(body);
        if (q.limit() == null || q.limit() > limit + 1) {
            q = q.with(q.fields(), q.where(), limit + 1);                       // one more than the limit: "cut" is known
        }
        MqService.Bound b = catalog.bind(who, q.model());
        MqService.Ran ran = mq.run(b, q, false, false);
        return frame(ran.shown(), limit, q.model(), String.valueOf(ran.compiled().asOf()));
    }

    private static ModelQuery.Time time(JsonNode spec) {
        if (spec.path("asOf").isTextual() && !spec.path("asOf").asText().isBlank()) {
            try {
                return new ModelQuery.Time(java.time.LocalDate.parse(spec.path("asOf").asText().substring(0, Math.min(10, spec.path("asOf").asText().length()))),
                        null, null, null, false);
            } catch (java.time.format.DateTimeParseException e) {
                throw new DrishtiException(ErrorCode.PYTHON_INVALID, "asOf is a date, such as 2026-10-08");
            }
        }
        return ModelQuery.Time.DEFAULT;
    }

    /** The model query of a Notebook extract or a test's sample: the fields' rows (no measures), the filters as {@code where}, the dates as {@code time}. */
    static ObjectNode rowsQuery(JsonNode spec, int limit) {
        String model = spec.hasNonNull("model") ? spec.path("model").asText() : modelOf(spec.path("dataset").asText(""));
        if (model.isBlank()) {
            throw new DrishtiException(ErrorCode.PYTHON_INVALID, "send {model, fields}, {dataset, fields}, {mq}, {input} or {report, page, visual}");
        }
        if (!spec.path("fields").isArray() || spec.path("fields").isEmpty()) {
            throw new DrishtiException(ErrorCode.PYTHON_INVALID, "name the fields to read");
        }
        ObjectNode q = JSON.createObjectNode().put("mq", 1).put("model", model);
        ObjectNode select = q.putObject("select");
        select.set("fields", spec.get("fields").deepCopy());
        select.putArray("measures");
        q.put("shape", "rows");
        q.put("limit", limit + 1);
        if (spec.hasNonNull("filters") && !spec.get("filters").isEmpty()) {
            q.set("where", spec.get("filters").deepCopy());
        }
        JsonNode dates = spec.get("dates");
        if (dates != null && dates.isObject() && dates.hasNonNull("from")) {
            ObjectNode w = q.putObject("time").putObject("window");
            w.put("from", dates.path("from").asText());
            w.put("to", dates.hasNonNull("to") ? dates.path("to").asText() : dates.path("from").asText());
        } else if (dates != null && dates.isTextual() && !dates.asText().isBlank()) {
            q.putObject("time").put("as-of", dates.asText());
        }
        return q;
    }

    /**
     * The model an extract's source names: {@code mq:<model>@<major>} is that model, {@code dataset:<name>} the dataset's implicit model, a bare name
     * the model the converter made of that 2.1 dataset ({@code <name>@1}); a catalogue id ({@code bi-...}) as it is.
     */
    static String modelOf(String source) {
        String s = source == null ? "" : source.strip();
        if (s.startsWith("mq:")) {
            return s.substring(3);
        }
        if (s.startsWith("file:dataset:")) {
            s = s.substring("file:dataset:".length());
        }
        if (s.startsWith("dataset:") || s.startsWith("bi-") || s.contains("@") || s.isEmpty()) {
            return s;
        }
        return s + "@1";
    }

    /** The rows as an Arrow stream with the {@code id} left out, names shortened, cut to {@code limit}. */
    static Read frame(QueryService.Result r, int limit, String model, String asOf) {
        List<ArrowIpcWriter.Column> keep = new ArrayList<>();
        for (ArrowIpcWriter.Column c : r.columns()) {
            if (!"id".equals(c.name().substring(c.name().lastIndexOf('.') + 1))) {
                keep.add(c);
            }
        }
        List<String> names = shortNames(keep.stream().map(ArrowIpcWriter.Column::name).toList());
        int rows = Math.min(limit, r.rows());
        List<ArrowIpcWriter.Column> out = new ArrayList<>();
        List<Map<String, String>> described = new ArrayList<>();
        for (int i = 0; i < keep.size(); i++) {
            ArrowIpcWriter.Column c = keep.get(i);
            Object[] values = rows == c.values().length ? c.values() : java.util.Arrays.copyOf(c.values(), rows);
            out.add(new ArrowIpcWriter.Column(names.get(i), c.kind(), values));
            described.add(Map.of("name", names.get(i), "type", c.kind() == ArrowIpcWriter.Kind.TEXT ? "string" : c.kind() == ArrowIpcWriter.Kind.INT64 ? "integer" : "decimal"));
        }
        List<String> masked = new ArrayList<>();
        if (r.meta() != null) {
            for (String m : r.meta().masked()) {
                int at = keep.stream().map(ArrowIpcWriter.Column::name).toList().indexOf(m);
                masked.add(at >= 0 ? names.get(at) : m.substring(m.lastIndexOf('.') + 1));
            }
        }
        return new Read(ArrowIpcWriter.write(out, rows), rows, r.rows(), masked, model, asOf, described);
    }

    /** {@code trade.desk} as {@code desk}; a name two columns share keeps its table ({@code trade_desk}); characters a column name cannot hold become {@code _}. */
    static List<String> shortNames(List<String> columns) {
        List<String> last = columns.stream().map(c -> c.substring(c.indexOf('.') + 1)).toList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            String s = last.get(i);
            String name = UNSAFE.matcher(java.util.Collections.frequency(last, s) > 1 ? columns.get(i).replace('.', '_') : s).replaceAll("_");
            name = name.isEmpty() ? "col" : name;
            out.add(Character.isDigit(name.charAt(0)) ? "c" + name : name);
        }
        return out;
    }

    /** A JSON array of texts, for building queries. */
    static ArrayNode texts(List<String> values) {
        ArrayNode a = JSON.createArrayNode();
        values.forEach(a::add);
        return a;
    }
}
