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
package com.ash.drishti.bicompute.whatif;

import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.bicompute.sql.TabularProvider;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.List;
import java.util.Map;

/**
 * What-if inside the pivot (HOW_IT_FITS.md section A26.7, item 3): the view as it is and the view with an {@link Overlay}, cell by cell, with
 * the differences. The what-if runs on a core of its own over the same executor, the same partial-state cache (keys carry the overlay's
 * digest, so the original's states are reused and only the overlay's are new) and the same custom functions; nothing is written anywhere.
 * Sources of the tabular provider only: a compute engine's what-if is its own arithmetic, under its own API.
 */
public final class WhatIf {

    private WhatIf() {}

    /** {@code {original, whatIf, diff, overlay}}: the compare answer's shape with the what-if as the first side. */
    public static Map<String, Object> run(PivotCore core, Viewer v, PivotView view, Overlay overlay) {
        PivotProvider p = core.provider(view.source());
        if (!(p instanceof TabularProvider tab)) {
            throw new DrishtiException(ErrorCode.PIVOT_EXPLAIN, "a what-if overlay changes a table's rows; " + view.source() + " is answered by "
                    + p.id() + " (VaR: use the VaR engine's what-if)");
        }
        if (overlay.empty()) {
            throw new DrishtiException(ErrorCode.PIVOT_EXPLAIN, "the what-if changes nothing: exclude members, change a field or add rows");
        }
        PivotCore wi = new PivotCore(List.of(new TabularProvider(tab.executor(), new WhatIfResolver(tab.resolver(), overlay))), core.cache(),
                core.settings());
        wi.functions(core.functions());
        Map<String, Object> original = core.query(v, view);
        Map<String, Object> changed = wi.query(v, view);
        Map<String, Object> out = new java.util.LinkedHashMap<>(PivotCore.compareAnswers(changed, original));
        out.put("whatIf", out.remove("answer"));
        out.put("original", out.remove("other"));
        out.remove("date");
        out.remove("otherDate");
        out.put("overlay", overlay.toMap());
        out.put("note", "a what-if: private, never written to the lake; the differences are what-if minus original");
        return out;
    }
}
