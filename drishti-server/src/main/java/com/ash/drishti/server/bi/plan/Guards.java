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

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 5 (docs/architecture/BI_DESIGN.md section 4.1): the correctness guards. Fan-out ({@code DRS-9160}) and reachability ({@code DRS-9156}) are refused
 * when the question is checked. From wave A the two that W1 only warned about are refused too ({@code drishti.bi.plan.guards: refuse}): a sum that adds
 * amounts in several currencies in one group ({@code DRS-9158}, known once the groups are computed) and a balance added across dates
 * ({@code DRS-9159}); a balance asked alone over a window is answered as its last date's value, which is right, with the warning kept. Pure.
 */
public final class Guards {

    /** A guard's finding that refuses the question: the code, what is wrong, and the fix. */
    public record Refusal(String code, String path, String message, String fix) {}

    private Guards() {}

    /** The refusals among the question's warnings (the validator's and the currency guard's), when the guards refuse. */
    public static List<Refusal> refusals(List<String> warnings, PlanProperties props) {
        List<Refusal> out = new ArrayList<>();
        if (!props.refuse()) {
            return out;
        }
        for (String w : warnings) {
            if (w.startsWith("DRS-9158")) {
                out.add(new Refusal("DRS-9158", "select.measures", w.substring("DRS-9158 ".length()).replace(" (refused from wave W2)", ""),
                        "add the currency to the fields (it splits each group by currency), or filter one currency"));
            } else if (w.startsWith("DRS-9159") && w.contains("added across the dates")) {
                out.add(new Refusal("DRS-9159", "select.measures", w.substring("DRS-9159 ".length()).replace(" (refused from wave W2)", ""),
                        "ask the balance alone (its last date's value), add the business date to the fields, or write the measure with a balance function: last(measure('...'), <alias>.businessDate), closing(...) or avg_over(...)"));
            }
        }
        return out;
    }

    /** The warnings that stay warnings (a refusal's warning is not repeated). */
    public static List<String> remaining(List<String> warnings, List<Refusal> refused) {
        if (refused.isEmpty()) {
            return warnings;
        }
        return warnings.stream().filter(w -> refused.stream().noneMatch(r -> w.startsWith(r.code()))).toList();
    }
}
