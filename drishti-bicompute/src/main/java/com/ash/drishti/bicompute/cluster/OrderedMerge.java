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
package com.ash.drishti.bicompute.cluster;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.bicompute.core.Partials;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Partial states of several shards (nodes, files, threads) merged into one answer in a fixed order (PIVOT_ENGINE.md section 9.1, step 5;
 * lifted from VaR sharding's ordered combine): shard order, then cell-key order. So the same question on the same data gives the same digits
 * on every run and every node count: floating-point addition is not associative, and a fixed order makes it repeatable.
 */
public final class OrderedMerge {

    private OrderedMerge() {}

    /** The shards' partials ({@code shards} in shard order) as one; every shard answered the same plan. */
    public static Partials merge(AggregationPlan plan, List<Partials> shards) {
        if (shards.size() == 1) {
            return shards.get(0);
        }
        List<Partials.SetResult> out = new ArrayList<>();
        Partials.Stats stats = Partials.Stats.none();
        for (Partials p : shards) {
            stats = stats.plus(p.stats());
        }
        int nf = plan.functions().size();
        for (int s = 0; s < plan.sets().size(); s++) {
            TreeMap<CellKey, Partials.Entry> cells = new TreeMap<>();
            for (Partials p : shards) {                                  // shard order
                for (Map.Entry<CellKey, Partials.Entry> e : p.sets().get(s).cells().entrySet()) {   // cell-key order
                    Partials.Entry have = cells.get(e.getKey());
                    if (have == null) {
                        cells.put(e.getKey(), e.getValue());
                        continue;
                    }
                    Object[] st = new Object[nf];
                    for (int f = 0; f < nf; f++) {
                        st[f] = plan.functions().get(f).fn().merge(have.states()[f], e.getValue().states()[f]);
                    }
                    cells.put(e.getKey(), new Partials.Entry(have.members() + e.getValue().members(), st, have.scope(), have.notes()));
                }
            }
            out.add(new Partials.SetResult(plan.sets().get(s), cells));
        }
        return new Partials(out, stats);
    }

    /** True when every function of the plan merges partial states (no holistic figure finished per shard). */
    public static boolean mergeable(AggregationPlan plan) {
        return plan.functions().stream().allMatch(f -> f.fn().sqlStateMergeable());
    }
}
