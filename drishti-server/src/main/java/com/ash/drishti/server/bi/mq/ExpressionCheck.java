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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.expr.DelAst;
import com.ash.drishti.server.bi.expr.DelChecker;
import com.ash.drishti.server.bi.expr.DelException;
import com.ash.drishti.server.bi.expr.DelParser;
import com.ash.drishti.server.bi.expr.Expressions;
import com.ash.drishti.server.bi.expr.Fields;
import com.ash.drishti.server.bi.expr.Scope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The answer of {@code POST /api/v1/bi/expr/validate}: one expression read (DEL v2, or the old syntax while it is read, with the converted text), checked in
 * its scope against the caller's fields and measures (types, names, masks, aggregates), and for a measure on a model also compiled the way a model measure
 * is (the engine's own checks: reachability, windows, time intelligence), without reading data. Pure apart from the model it is given.
 */
final class ExpressionCheck {

    /** The name a measure is checked under while probing the engine's checks. */
    static final String PROBE = "__expression";

    private ExpressionCheck() {}

    static Map<String, Object> run(String expr, Scope scope, Fields fields, Dataset flat, Map<String, String> ids) {
        List<DelException.Problem> problems = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expr", expr);
        out.put("scope", scope.word());
        DelAst tree = null;
        String syntax = "del2";
        String converted = null;
        try {
            Expressions.Parsed parsed = Expressions.read(expr);
            if (parsed.legacy()) {
                syntax = "legacy";
                try {
                    converted = Expressions.convert(expr).to();
                    tree = DelParser.parse(converted);
                } catch (DelException e) {
                    problems.add(e.problem());
                }
                problems.add(new DelException.Problem(Expressions.OLD_SYNTAX, DelException.Problem.WARNING,
                        "old expression syntax (read until the end of wave A, then refused): run the converter", converted == null ? null : "write: " + converted, 0, expr.length()));
            } else {
                tree = parsed.del();
            }
        } catch (DelException e) {
            if (Expressions.OLD_SYNTAX.equals(e.code())) {
                syntax = "legacy";
            }
            problems.add(e.problem());
        }
        if (tree != null) {
            DelChecker.Checked c = DelChecker.check(tree, scope, fields);
            boolean shifted = converted != null;
            c.problems().forEach(x -> problems.add(shifted ? new DelException.Problem(x.code(), x.level(), x.message() + " (in the converted text)", x.fix(), 0, expr.length()) : x));
            out.put("type", c.type().word());
            out.put("uses", Map.of("functions", c.functions(), "fields", c.fields(), "measures", c.measures()));
            if (c.ok() && flat != null && scope == Scope.AGGREGATE) {
                engineChecks(Expressions.withMeasureNames(converted == null ? expr : converted, ids), flat, expr.length(), problems);
            }
        }
        out.put("syntax", syntax);
        if (converted != null) {
            out.put("converted", converted);
        }
        out.put("valid", problems.stream().noneMatch(DelException.Problem::error));
        out.put("problems", problems.stream().map(DelException.Problem::toMap).toList());
        return out;
    }

    /** The model's own checks of a measure (what publish runs), on a copy of the model with the expression as one more measure. */
    private static void engineChecks(String expr, Dataset flat, int length, List<DelException.Problem> problems) {
        Map<String, Dataset.Measure> measures = new LinkedHashMap<>(flat.measures());
        measures.put(PROBE, new Dataset.Measure(PROBE, expr, null, "being checked", null));
        Dataset ds = new Dataset(flat.name(), flat.version(), flat.title(), flat.description(), flat.sample(), flat.engine(), flat.tables(), flat.relationships(), measures,
                flat.hierarchies(), flat.security(), flat.rollups(), flat.refresh(), flat.compose(), flat.derived(), flat.tests());
        for (DatasetParser.Problem p : DatasetParser.check(ds)) {
            if (p.error() && (p.path().equals("measures." + PROBE) || p.path().startsWith("measures." + PROBE + "."))) {
                problems.add(new DelException.Problem("DRS-9127", DelException.Problem.ERROR, p.message().replace(PROBE, "this measure"), null, 0, length));
            }
        }
    }
}
