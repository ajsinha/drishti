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
package com.ash.drishti.server.bi.plan;

import com.ash.drishti.server.bi.engine.QueryRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 7 and 8's entry (docs/architecture/BI_DESIGN.md sections 4.3 and 12.1): the optimised plan lowered to the engine request the SQL compiler takes
 * ({@code SqlCompiler} and {@code SqlEmitter} remain the SQL generator of the {@code Aggregate} and {@code Meet} fragments): the fields and measures of the
 * plan's answer, its order and limit, with the filters as the language resolved them (relative dates as dates, a {@code top} as the values it found: data the
 * plan never holds) and the measures the answer drops (the currency guard's).
 */
public final class Lowering {

    private Lowering() {}

    /** The engine request of a plan; {@code resolved} is the language's request (for the data-dependent filters, the dates and the layer asked for). */
    public static QueryRequest request(LogicalPlan plan, QueryRequest resolved) {
        PlanNode.Answer a = (PlanNode.Answer) plan.root();
        List<String> fields = new ArrayList<>();
        List<String> measures = new ArrayList<>();
        for (String c : a.columns()) {
            (resolved.fields().contains(c) ? fields : measures).add(c);
        }
        resolved.measures().stream().filter(m -> !measures.contains(m)).forEach(measures::add);      // hidden guard measures stay last, as the language put them
        List<QueryRequest.Sort> sort = new ArrayList<>();
        Integer limit = null;
        PlanNode n = a.input();
        while (true) {                                                          // the spine above the aggregates: limit, sort, having, window
            if (n instanceof PlanNode.Limit l) {
                limit = l.n();
            } else if (n instanceof PlanNode.Sort s) {
                for (String k : s.keys()) {
                    int sp = k.lastIndexOf(' ');
                    sort.add(new QueryRequest.Sort(k.substring(0, sp), k.endsWith(" desc")));
                }
            } else if (!(n instanceof PlanNode.Having || n instanceof PlanNode.Window)) {
                break;
            }
            n = n.inputs().get(0);
        }
        return new QueryRequest(fields, measures, resolved.filters(), resolved.from(), resolved.to(), sort, limit, resolved.layer(), resolved.detail());
    }
}
