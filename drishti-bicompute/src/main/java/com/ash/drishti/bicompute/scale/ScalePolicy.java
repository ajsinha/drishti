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
package com.ash.drishti.bicompute.scale;

import com.ash.drishti.bicompute.core.AggregationPlan.FunctionCall;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.core.ScaleOptions;
import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.bicompute.functions.Sketches;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Exact or ≈ for distinct counts, percentiles and top values (PIVOT_ENGINE.md sections 5.2 and 5.3). A view names a measure as it wants it
 * shown; how it is computed follows the request:
 *
 * <ul>
 *   <li><b>exact on request</b> ({@code "exact": true}): every sketch is replaced by its exact form ({@code approx_count_distinct} by
 *       {@code count_distinct}, {@code approx_percentile} by {@code percentile}, {@code top} by {@code top_exact});</li>
 *   <li><b>approximate</b> ({@code "exact": false}): every exact distinct count and percentile is answered by its sketch;</li>
 *   <li><b>auto</b> (the default): exact while the view reads at most {@code approximate-above-rows} rows (exact is cheap there), its
 *       sketch above, with a note saying which and how to ask for exact.</li>
 * </ul>
 *
 * The label stays the view's, so sorting, top N and references are unchanged; the answer's measure says ≈ and its stated error.
 */
public final class ScalePolicy implements PivotCore.Hooks {

    /** The exact functions that have a sketch: the sketch's name is {@code approx_} and the same arguments. */
    static final Set<String> TWINNED = Set.of("count_distinct", "median", "percentile");

    private volatile long approximateAboveRows;

    /** @param approximateAboveRows in auto, a view estimated to read more rows answers distinct counts and percentiles ≈ (0: never) */
    public ScalePolicy(long approximateAboveRows) {
        this.approximateAboveRows = approximateAboveRows;
    }

    public void approximateAboveRows(long rows) {
        this.approximateAboveRows = rows;
    }

    @Override
    public void functions(Viewer v, PivotView view, PivotProvider p, ScaleOptions o, List<FunctionCall> fns,
            Supplier<PivotProvider.Estimate> estimate, List<String> notes) {
        for (int i = 0; i < fns.size(); i++) {
            FunctionCall f = fns.get(i);
            if (o.exactness() == ScaleOptions.Exactness.EXACT && f.fn() instanceof Sketches.Sketch sk) {
                FunctionCall e = Functions.parse(sk.exactText(f.inputs()), f.label(), p);
                fns.set(i, e);
                notes.add(f.label() + ": computed exactly as asked (" + e.id() + "), not by its sketch");
                continue;
            }
            String name = f.fn().name().toLowerCase(Locale.ROOT);
            if (!TWINNED.contains(name) || !p.capabilities().builtIns() || o.exactness() == ScaleOptions.Exactness.EXACT) {
                continue;
            }
            boolean approx = o.exactness() == ScaleOptions.Exactness.APPROXIMATE;
            long rows = -1;
            if (!approx && approximateAboveRows > 0) {
                rows = estimate.get().rows();
                approx = rows > approximateAboveRows;
            }
            if (!approx) {
                continue;
            }
            FunctionCall s = Functions.parse("approx_" + f.id(), f.label(), p);
            fns.set(i, s);
            String err = s.fn() instanceof Sketches.Sketch sk ? sk.error() : "a stated error";
            notes.add(f.label() + ": ≈ by its sketch, " + err + (rows > 0 ? String.format(Locale.ROOT, "; the view reads ≈%,d rows, more than "
                    + "%,d (drishti.pivot.sketches.approximate-above-rows)", rows, approximateAboveRows) : " (asked approximate)")
                    + "; ask for exact to compute it from the values");
        }
    }
}
