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
import com.ash.drishti.bicompute.functions.Sketches;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Exact or ≈ for distinct counts and percentiles (ARCHITECTURE.md section 26.3; FUNCTION_CATALOGUE.md section 4). A function has
 * one name ({@code distinct}, {@code median}, {@code percentile}); its sketch is how it is computed, never a second name:
 *
 * <ul>
 *   <li><b>exact on request</b> ({@code "exact": true}): computed from the values at any size;</li>
 *   <li><b>approximate</b> ({@code "exact": false}): answered by the sketch, with its stated error;</li>
 *   <li><b>auto</b> (the default): exact while the view reads at most {@code approximate-above-rows} rows (exact is cheap there), the
 *       sketch above, with a note saying so and how to ask for exact.</li>
 * </ul>
 *
 * Only the built-ins of a provider that evaluates them are ever sketched: a provider's own measures (VaR, ES) and custom functions are
 * not, so VaR is never approximate. The label stays the view's; the id gains {@code ≈}, so the cache and rollups keep the two apart.
 */
public final class ScalePolicy implements PivotCore.Hooks {

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
        if (o.exactness() == ScaleOptions.Exactness.EXACT || !p.capabilities().builtIns() || "var".equals(p.id())) {
            return;
        }
        long rows = -1;
        for (int i = 0; i < fns.size(); i++) {
            FunctionCall f = fns.get(i);
            if (!Sketches.hasSketch(f.fn())) {
                continue;
            }
            boolean approx = o.exactness() == ScaleOptions.Exactness.APPROXIMATE;
            if (!approx && approximateAboveRows > 0) {
                if (rows < 0) {
                    rows = estimate.get().rows();
                }
                approx = rows > approximateAboveRows;
            }
            Optional<FunctionCall> s = approx ? Sketches.approximate(f) : Optional.empty();
            if (s.isEmpty()) {
                continue;
            }
            fns.set(i, s.get());
            String err = s.get().fn() instanceof Sketches.Sketch sk ? sk.error() : "a stated error";
            notes.add(f.label() + ": ≈ by its sketch, " + err + (rows > 0 && o.exactness() != ScaleOptions.Exactness.APPROXIMATE
                    ? String.format(Locale.ROOT, "; the view reads ≈%,d rows, more than %,d (drishti.pivot.sketches.approximate-above-rows)", rows,
                            approximateAboveRows) : " (asked approximate)") + "; ask for exact to compute it from the values");
        }
    }
}
