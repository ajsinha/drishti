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
package com.ash.drishti.server.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.engine.live.Frame;
import com.ash.drishti.engine.live.Patch;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The administrator's "Node" line (DRISHTI_NET.md section 13) on a live view's frames: added to a provenance panel a frame carries whole, nothing else touched. */
class NetServedByTest {

    static ViewModel.PanelView panel(String id, String kind) {
        return new ViewModel.PanelView(id, kind, id, null, null, "right", false, null,
                new PanelData.Fields(List.of(ViewModel.Cell.of("Source", "lake, gen 4"))), null, false, null, null);
    }

    @Test
    void aFrameKeepsTheLineOnTheProvenancePanelItCarries() {
        Frame f = new Frame(7, 4, List.of(new Patch("panel", 2, null, panel("built", "provenance"), null), new Patch("panel", 1, null, panel("kv", "kv"), null)),
                1.0, 2.0);
        Frame shown = NetServedBy.annotate(f, "n-a · forwarded from n-b");
        PanelData.Fields built = (PanelData.Fields) shown.patches().get(0).panel().data();
        assertThat(built.fields()).extracting(ViewModel.Cell::label).containsExactly("Source", NetServedBy.LABEL);
        assertThat(built.fields().get(1).text()).isEqualTo("n-a · forwarded from n-b");
        assertThat(((PanelData.Fields) shown.patches().get(1).panel().data()).fields()).hasSize(1);
        assertThat(shown.seq()).isEqualTo(7);
        assertThat(NetServedBy.annotate(f, null)).as("anyone but an administrator: unchanged").isSameAs(f);
    }
}
