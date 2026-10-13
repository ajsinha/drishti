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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.TableRules;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 4 of the pipeline (docs/architecture/BI_DESIGN.md sections 4.1 and 5.2): every dataset the plan reads becomes a {@code SecureScan} for this reader
 * (the dataset's filters, its row rules and the model's ANDed, each layer the OR of the reader's matching rules, {@code user.*} resolved; masks; hidden
 * columns), and a dimension whose security restricts the reader reaches each fact that references it many-to-one as a {@code SemiJoin}. It runs before the
 * optimiser, so the optimiser can only move security, never drop it; no node it adds can be removed by any language construct. Pure.
 */
public final class SecurityRewriter {

    private SecurityRewriter() {}

    public static LogicalPlan rewrite(LogicalPlan p, Dataset flat, SecuritySignature.Reader reader) {
        return p.withRoot(rewrite(p.root(), flat, reader));
    }

    static PlanNode rewrite(PlanNode n, Dataset flat, SecuritySignature.Reader reader) {
        return switch (n) {
            case PlanNode.SecureScan s -> secure(s, flat, reader);
            case PlanNode.Aggregate a -> propagate((PlanNode.Aggregate) a.withInputs(a.inputs().stream().map(i -> rewrite(i, flat, reader)).toList()), flat, reader);
            default -> n.withInputs(n.inputs().stream().map(i -> rewrite(i, flat, reader)).toList());
        };
    }

    static PlanNode.SecureScan secure(PlanNode.SecureScan s, Dataset flat, SecuritySignature.Reader reader) {
        SecuritySignature.Alias a = SecuritySignature.alias(flat, s.alias(), reader);
        return new PlanNode.SecureScan(s.alias(), s.dataset(), s.version(), s.time(), s.columns(), s.pushed(), a.rules(), a.masked(), a.hidden(), a.denied());
    }

    /** Within an aggregate's filter: each restricted dimension the fact reaches many-to-one wraps the fact's scan as a semi-join. */
    private static PlanNode propagate(PlanNode.Aggregate a, Dataset flat, SecuritySignature.Reader reader) {
        if (!(a.sources().size() == 1 && a.sources().get(0) instanceof PlanNode.Filter f)) {
            return a;
        }
        PlanNode fact = null;
        List<PlanNode> others = new ArrayList<>();
        for (PlanNode s : f.scans()) {
            if (s instanceof PlanNode.SecureScan sc && sc.alias().equals(a.fact())) {
                fact = s;
            } else {
                others.add(s);
            }
        }
        if (fact == null) {
            return a;
        }
        List<PlanNode> kept = new ArrayList<>();
        for (PlanNode o : others) {
            if (o instanceof PlanNode.SecureScan dim && restricts(flat, dim) && TableRules.reaches(flat, a.fact(), dim.alias())) {
                fact = new PlanNode.SemiJoin(fact, dim, a.fact() + " -> " + dim.alias());
            } else {
                kept.add(o);
            }
        }
        List<PlanNode> scans = new ArrayList<>();
        scans.add(fact);
        scans.addAll(kept);
        return a.withInputs(List.of(new PlanNode.Filter(scans, f.conditions())));
    }

    /** True for a dimension whose security reaches the facts and restricts this reader (a rule, or a layer that shuts them out). */
    private static boolean restricts(Dataset flat, PlanNode.SecureScan dim) {
        Dataset.TableSecurity s = flat.security().tables().get(dim.alias());
        return s != null && s.dimension() && (!dim.rules().isEmpty() || dim.denied());
    }
}
