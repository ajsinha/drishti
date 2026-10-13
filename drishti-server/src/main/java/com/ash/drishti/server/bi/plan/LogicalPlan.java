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

import java.util.List;
import java.util.Map;

/**
 * A question as the one IR (docs/architecture/BI_DESIGN.md section 3.6): the tree, the model it asks at its version fingerprint, the resolved time, and the
 * totals levels. Its {@link #hash} is the same whichever language asked it (MQ, Drishti SQL, a visual): canonical names, sorted keys, resolved relative
 * dates; the reader's security is not in it (that is the {@link SecuritySignature}). Immutable.
 *
 * @param version a fingerprint of the model's and its datasets' texts (a change of either is another plan)
 * @param time the resolved time: {@code as-of D}, {@code window D1..D2}, {@code last PT1H}, {@code live}, or {@code current}
 * @param ids the stable ids of the model's fields and measures by name: the canonical text names them by id, so a rename is the same question
 */
public record LogicalPlan(PlanNode root, String model, String version, String time, List<List<String>> totals, Map<String, String> ids) {

    public LogicalPlan {
        totals = totals == null ? List.of() : totals.stream().map(List::copyOf).toList();
        ids = ids == null ? Map.of() : Map.copyOf(ids);
    }

    /** The plan hash, {@code p_} and 16 hex digits. */
    public String hash() {
        return Canonicalizer.hash(this);
    }

    /** True when the answer never changes once computed (an as-of date or a closed window): cached without a time to live. */
    public boolean immutable() {
        return time.startsWith("as-of ") || time.startsWith("window ");
    }

    public LogicalPlan withRoot(PlanNode r) {
        return new LogicalPlan(r, model, version, time, totals, ids);
    }

    /** The scans of the plan. */
    public List<PlanNode.SecureScan> scans() {
        return PlanNode.scans(root);
    }

    /** The plan as a tree of maps (op, props, inputs), for {@code meta.plan} and explain. Security shows only that rules applied, never their text. */
    public Map<String, Object> tree() {
        return Explain.tree(root);
    }
}
