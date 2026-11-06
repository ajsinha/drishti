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

import com.ash.drishti.rachana.el.check.Fields;
import com.ash.drishti.rachana.el.check.Problem;
import com.ash.drishti.rachana.el.check.Scope;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.expr.DelException;
import com.ash.drishti.server.bi.expr.Expressions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The answer of {@code POST /api/v1/bi/expr/validate}: one Rachana expression read and checked in
 * its scope against the caller's fields and measures (types, names, masks, aggregates), and for a measure on a model also compiled the way a model measure
 * is (the engine's own checks: reachability, windows, time intelligence), without reading data. Pure apart from the model it is given.
 */
final class ExpressionCheck {

    /** The name a measure is checked under while probing the engine's checks. */
    static final String PROBE = "__expression";

    private ExpressionCheck() {}

    static Map<String, Object> run(String expr, Scope scope, Fields fields, Dataset flat, Map<String, String> ids) {
        List<Problem> problems = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expr", expr);
        out.put("scope", scope.word());
        String syntax = "rachana";
        com.ash.drishti.rachana.el.check.Checker.Checked c = com.ash.drishti.rachana.el.CheckedMode.check(expr, scope, fields);
        problems.addAll(c.problems());
        out.put("type", c.type().word());
        out.put("uses", Map.of("functions", c.functions(), "fields", c.fields(), "measures", c.measures()));
        if (c.ok()) {
            try {
                Expressions.parse(expr);                                       // what BI's compiler runs
            } catch (DelException e) {
                problems.add(e.problem());
            }
        }
        if (problems.stream().noneMatch(Problem::error) && flat != null && scope == Scope.AGGREGATE) {
            engineChecks(Expressions.withMeasureNames(expr, ids), flat, expr.length(), problems);
        }
        out.put("syntax", syntax);
        out.put("valid", problems.stream().noneMatch(Problem::error));
        out.put("problems", problems.stream().map(Problem::toMap).toList());
        return out;
    }

    /** The model's own checks of a measure (what publish runs), on a copy of the model with the expression as one more measure. */
    private static void engineChecks(String expr, Dataset flat, int length, List<Problem> problems) {
        Map<String, Dataset.Measure> measures = new LinkedHashMap<>(flat.measures());
        measures.put(PROBE, new Dataset.Measure(PROBE, expr, null, "being checked", null));
        Dataset ds = flat.withMeasures(measures);
        for (DatasetParser.Problem p : DatasetParser.check(ds)) {
            if (p.error() && (p.path().equals("measures." + PROBE) || p.path().startsWith("measures." + PROBE + "."))) {
                problems.add(new Problem("DRS-9127", Problem.ERROR, p.message().replace(PROBE, "this measure"), null, 0, length));
                return;
            }
        }
        // what a model query would refuse (DRS-9156): aggregates of facts with no path between them, said while the measure is written, not when it is asked
        try {
            new com.ash.drishti.server.bi.engine.SqlCompiler(ds, com.ash.drishti.server.bi.engine.Dialect.DUCKDB).plan(new com.ash.drishti.server.bi.engine.QueryRequest(
                    List.of(), List.of(PROBE), List.of(), null, null, List.of(), null, "auto"), new com.ash.drishti.server.bi.engine.SqlCompiler.Reader(false, java.util.Set.of(),
                    Map.of(), com.ash.drishti.server.bi.engine.SqlCompiler.Masks.NONE));
        } catch (com.ash.drishti.common.DrishtiException e) {
            if (e.errorCode() == com.ash.drishti.common.ErrorCode.QUERY_INVALID) {
                problems.add(new Problem("DRS-9156", Problem.ERROR, "a model query would refuse this measure: "
                        + e.getMessage().replace(PROBE, "this measure").replaceFirst("^DRS-\\d+ ", ""), "aggregate each fact in a measure of its own, or join the facts through a shared dimension",
                        0, length));
            }
        }
    }
}
