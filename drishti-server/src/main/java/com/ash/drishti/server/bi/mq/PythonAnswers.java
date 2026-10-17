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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Python measures in any grouped query (wave B, R8; docs/architecture/BI_DESIGN.md section 3.5, the {@code PyAggregate} node): after the query's SQL
 * measures ran, each Python measure gets the rows of its input columns with the answer's fields, read as the reader (row rules and masks applied by the
 * engine), and the console's runner gives one value per group; the values become the measure's column of the answer, in its place. A masked input makes the
 * column {@code None} for the reader (said in the warnings); more rows than {@code drishti.bi.mq.max-limit} is {@code DRS-9072}.
 */
final class PythonAnswers {

    private PythonAnswers() {}

    static QueryService.Result add(MqService.Bound b, Dataset ds, QueryRequest main, QueryRequest sqlMain, QueryService.Result r, List<String> python,
            MqService.PythonMeasures runner, MqProperties props, List<String> warnings) {
        if (runner == null) {
            throw new DrishtiException(ErrorCode.PYTHON_BUSY, "Python measures need the console's Python runner, which this server has none of");
        }
        Map<String, ArrowIpcWriter.Column> made = new LinkedHashMap<>();
        for (String name : python) {
            made.put(name, column(b, ds, main, r, ds.measures().get(name), runner, props, warnings));
        }
        Map<String, ArrowIpcWriter.Column> byName = new LinkedHashMap<>();
        r.columns().forEach(c -> byName.put(c.name(), c));
        List<ArrowIpcWriter.Column> out = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        for (String f : main.fields()) {
            if (byName.containsKey(f) && placed.add(f)) {
                out.add(byName.get(f));
            }
        }
        for (String m : main.measures()) {
            ArrowIpcWriter.Column c = made.containsKey(m) ? made.get(m) : byName.get(m);
            if (c != null && placed.add(m)) {
                out.add(c);
            }
        }
        byName.forEach((n, c) -> {
            if (placed.add(n)) {
                out.add(c);
            }
        });
        return new QueryService.Result(out, r.rows(), r.meta());
    }

    private static ArrowIpcWriter.Column column(MqService.Bound b, Dataset ds, QueryRequest main, QueryService.Result r, Dataset.Measure m, MqService.PythonMeasures runner,
            MqProperties props, List<String> warnings) {
        List<String> fields = main.fields();
        List<String> wanted = new ArrayList<>(fields);
        m.inputs().forEach(i -> {
            if (!wanted.contains(i)) {
                wanted.add(i);
            }
        });
        List<QueryRequest.Filter> rowFilters = main.filters().stream().filter(f -> !ds.measures().containsKey(f.field())).toList();
        QueryRequest extract = new QueryRequest(wanted, List.of(), rowFilters, main.from(), main.to(), List.of(), props.maxLimit() + 1, main.layer(), true);
        QueryService.Result rows = b.runner().run(ds, extract, false);
        Object[] values = new Object[r.rows()];
        if (rows.meta() != null && m.inputs().stream().anyMatch(i -> rows.meta().masked().contains(i))) {
            warnings.add(m.name() + " reads a column masked for you: its values are not shown");
            return new ArrowIpcWriter.Column(m.name(), ArrowIpcWriter.Kind.FLOAT64, values);
        }
        if (rows.rows() > props.maxLimit()) {
            throw new DrishtiException(ErrorCode.PYTHON_LIMIT, "the Python measure '" + m.name() + "' would read more than " + props.maxLimit() + " rows (drishti.bi.mq.max-limit):"
                    + " group by less or filter");
        }
        List<String> names = new ArrayList<>();
        for (String w : wanted) {
            names.add(fields.contains(w) ? w : w.contains(".") ? w.substring(w.indexOf('.') + 1) : w);
        }
        List<List<Object>> data = new ArrayList<>();
        for (int i = 0; i < rows.rows(); i++) {
            List<Object> row = new ArrayList<>();
            for (ArrowIpcWriter.Column c : rows.columns()) {
                row.add(c.values()[i]);
            }
            data.add(row);
        }
        JsonNode answer = runner.run(b.security(), m, fields, names, data);
        Map<List<String>, Object> byGroup = new HashMap<>();
        for (JsonNode row : answer.path("rows")) {
            List<String> key = new ArrayList<>();
            for (int i = 0; i < fields.size() && i < row.size() - 1; i++) {
                key.add(text(row.get(i)));
            }
            JsonNode v = row.get(row.size() - 1);
            byGroup.put(key, v == null || v.isNull() ? null : v.isNumber() ? (Object) v.asDouble() : (Object) v.asText());
        }
        Map<String, ArrowIpcWriter.Column> cols = new LinkedHashMap<>();
        r.columns().forEach(c -> cols.put(c.name(), c));
        for (int i = 0; i < r.rows(); i++) {
            List<String> key = new ArrayList<>();
            for (String f : fields) {
                ArrowIpcWriter.Column c = cols.get(f);
                key.add(c == null ? "" : c.values()[i] == null ? "null" : String.valueOf(c.values()[i]));
            }
            values[i] = byGroup.get(key);
        }
        return new ArrowIpcWriter.Column(m.name(), ArrowIpcWriter.Kind.FLOAT64, values);
    }

    private static String text(JsonNode v) {
        if (v == null || v.isNull()) {
            return "null";
        }
        if (v.isIntegralNumber()) {
            return Long.toString(v.asLong());
        }
        if (v.isNumber()) {
            return Double.toString(v.asDouble());
        }
        return v.asText();
    }
}
