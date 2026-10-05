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
package com.ash.drishti.server.collab.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.engine.view.ViewModel.Cell;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The layout as data: limits, denied panels, kinds drawn, and a PNG that decodes at the size the model says. */
class SnapshotLayoutTest {

    static final SnapshotLayout.Watermark MARK = new SnapshotLayout.Watermark("Ann", "3 recipients", "2026-10-05 09:30 UTC", "2026-10-04", 7, "https://x/share/sh_1");

    PanelView panel(String id, String title, PanelData data, String denied) {
        return new PanelView(id, "kv", title, null, null, "main", false, null, data, null, false, null, null, denied);
    }

    ViewModel view(List<PanelView> panels) {
        return new ViewModel(new ViewModel.Ref("trade", "T1"), "TRD", new ViewModel.TitleView("IRS", "T1", null), List.of(Cell.of("MTM", "1,234")), panels,
                List.of(), new ViewModel.Provenance(null, null, "demo", 7, null, false, "2026-10-04"), null);
    }

    PanelData.Fields fields(String v) {
        return new PanelData.Fields(List.of(Cell.of("Field", v)));
    }

    @Test
    void aDeniedPanelIsNotEvenNamed() {
        SnapshotModel m = new SnapshotLayout(960, 1800, 8, 12).build(view(List.of(panel("a", "Open panel", fields("visible"), null),
                panel("b", "Secret panel", fields("hidden"), "no access"))), null, MARK);
        assertThat(m.allText()).contains("Open panel", "visible").doesNotContain("Secret panel").doesNotContain("hidden");
    }

    @Test
    void aPanelRequestedByIdIsTheOnlyOneDrawn() {
        SnapshotModel m = new SnapshotLayout(960, 1800, 8, 12).build(view(List.of(panel("a", "One", fields("x"), null), panel("b", "Two", fields("y"), null))), "b", MARK);
        assertThat(m.allText()).contains("Two").doesNotContain("One");
    }

    @Test
    void heightAndPanelLimitsLeaveOutTheRestAndSayHowMany() {
        List<PanelView> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(panel("p" + i, "Panel " + i, fields("v" + i), null));
        }
        SnapshotModel m = new SnapshotLayout(960, 400, 8, 12).build(view(many), null, MARK);
        assertThat(m.height()).isLessThanOrEqualTo(400);
        assertThat(m.allText()).contains("more panels not shown").doesNotContain("Panel 19");
    }

    @Test
    void rowLimitCutsATableAndSaysSo() {
        List<PanelData.Row> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(new PanelData.Row(List.of(Cell.of(null, "r" + i)), false, null, null));
        }
        PanelData.Table t = new PanelData.Table(List.of("Col"), List.of(false), rows, null, null);
        SnapshotModel m = new SnapshotLayout(960, 1800, 8, 5).build(view(List.of(panel("t", "Rows", t, null))), null, MARK);
        assertThat(m.allText()).contains("r4", "… 25 more rows").doesNotContain("r5\n");
    }

    @Test
    void aChartWithNoSimpleDrawingIsALabelledPlaceholderAndThePngDecodesAtTheModelsSize() throws Exception {
        PanelData.Candles c = new PanelData.Candles(List.of(), false, null, null, null, null, null);
        SnapshotModel m = new SnapshotLayout(960, 1800, 8, 12).build(view(List.of(panel("c", "Prices", c, null),
                panel("l", "Trend", new PanelData.Chart(List.of("a", "b"), List.of(new PanelData.Series("s", List.of(1.0, 3.0), null)), null, null, null, null, null, null), null))),
                null, MARK);
        assertThat(m.allText()).contains("open the view in Drishti to see it");
        byte[] png = new SnapshotPainter().png(m);
        var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertThat(img.getWidth()).isEqualTo(m.width());
        assertThat(img.getHeight()).isEqualTo(m.height());
    }
}
