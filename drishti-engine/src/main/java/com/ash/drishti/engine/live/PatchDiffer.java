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
package com.ash.drishti.engine.live;

import com.ash.drishti.engine.view.ViewModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The minimal patches between two ViewModels of the same entity and layout: changed strip cells one by
 * one, changed panels whole, and the provenance when the generation moves. If the layout itself changed
 * (a Sutra was edited), every panel is sent.
 */
public final class PatchDiffer {

    public List<Patch> diff(ViewModel before, ViewModel after) {
        List<Patch> out = new ArrayList<>();
        List<ViewModel.Cell> a = before.strip();
        List<ViewModel.Cell> b = after.strip();
        for (int i = 0; i < b.size(); i++) {
            if (i >= a.size() || !a.get(i).equals(b.get(i))) {
                out.add(Patch.strip(i, b.get(i)));
            }
        }
        for (int i = 0; i < after.panels().size(); i++) {
            ViewModel.PanelView p = after.panels().get(i);
            ViewModel.PanelView q = i < before.panels().size() ? before.panels().get(i) : null;
            if (q == null || !q.id().equals(p.id()) || !Objects.equals(q.data(), p.data()) || !Objects.equals(q.error(), p.error())
                    || !Objects.equals(q.title(), p.title())) {
                out.add(Patch.panel(p));
            }
        }
        if (before.provenance().generation() != after.provenance().generation()
                || !before.provenance().layout().equals(after.provenance().layout())) {
            out.add(Patch.provenance(after.provenance()));
        }
        return out;
    }
}
