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
package com.ash.drishti.server.edit;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import java.util.ArrayList;
import java.util.List;

/**
 * <i>How this view was built</i> names the overlays laid over a record (WRITE_BACK.md › The design and its reasons, section 5.2): a line {@code Edited in Drishti} on the
 * page's provenance panel with each change id, its field and who made it.
 */
public final class EditNotes {

    public static final String LABEL = "Edited here";

    private EditNotes() {}

    /** The view with the line added when the record has overlays on the view's date (the view unchanged otherwise). */
    public static ViewModel annotate(EditRuntime runtime, ViewModel v, EntityRef ref, AsOf asOf) {
        if (runtime == null || !runtime.records().any()) {
            return v;
        }
        String line;
        try {
            line = runtime.layer().note(ref, asOf == null ? null : asOf.businessDate());
        } catch (RuntimeException e) {
            return v;
        }
        if (line == null) {
            return v;
        }
        List<ViewModel.PanelView> panels = v.panels().stream().map(pv -> annotate(pv, line)).toList();
        return new ViewModel(v.ref(), v.mnemonic(), v.title(), v.strip(), panels, v.keys(), v.provenance(), v.timings(), v.controls(), v.selects(), v.layout());
    }

    static ViewModel.PanelView annotate(ViewModel.PanelView pv, String line) {
        if (!"provenance".equals(pv.kind()) || !(pv.data() instanceof PanelData.Fields f)) {
            return pv;
        }
        List<ViewModel.Cell> cells = new ArrayList<>(f.fields());
        cells.add(ViewModel.Cell.of(LABEL, line));
        return pv.withData(new PanelData.Fields(cells));
    }
}
