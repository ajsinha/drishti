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
package com.ash.drishti.engine.design;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.engine.design.SampleChecker.Input;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.engine.view.ViewModel.Cell;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import com.ash.drishti.engine.view.ViewModel.Provenance;
import java.util.List;
import org.junit.jupiter.api.Test;

class SampleCheckerTest {

    private static PanelView panel(String id, String error, boolean empty) {
        return new PanelView(id, "table", id, null, null, "main", false, null, null, error, empty);
    }

    private static ViewModel view(String figure, PanelView... panels) {
        return new ViewModel(null, null, null, List.of(new Cell("PV", figure, null, null, false, null)), List.of(panels), List.of(),
                new Provenance("s v1", "f", "src", 0, null, false, null), null);
    }

    @Test
    void aFieldMissingInSomeSamplesShowsAsEmptyAndErrorCellsWithCounts() {
        List<Input> inputs = List.of(new Input("full", null, null), new Input("no-legs", null, null), new Input("bad-legs", null, null));
        SampleChecker.Matrix m = new SampleChecker().check(List.of("legs", "terms"), inputs, in -> switch (in.name()) {
            case "full" -> view("1", panel("legs", null, false), panel("terms", null, false));
            case "no-legs" -> view("—", panel("legs", null, true), panel("terms", null, false));
            default -> view("3", panel("legs", "'@.notional' is not a number", false), panel("terms", null, false));
        });
        assertThat(m.panel("legs").cells()).extracting(SampleChecker.Cell::status).containsExactly("ok", "empty", "error");
        assertThat(m.panel("legs").cells().get(2).message()).contains("not a number");
        assertThat(m.panel("legs").counts()).isEqualTo(new SampleChecker.Counts(1, 1, 1, 0));
        assertThat(m.panel("terms").counts()).isEqualTo(new SampleChecker.Counts(3, 0, 0, 0));
        assertThat(m.panel("legs").firstError()).contains("not a number");
        assertThat(m.counts()).isEqualTo(new SampleChecker.Counts(4, 1, 1, 0));
        assertThat(m.strip()).containsExactly(new SampleChecker.StripRow("PV", 1));
        assertThat(m.samples()).extracting(SampleChecker.SampleRow::layout).containsOnly("s v1");
        assertThat(m.ok()).isFalse();
    }

    @Test
    void aDeniedSampleOrSourceIsNoAccessAndARenderFailureIsAnErrorColumn() {
        List<Input> inputs = List.of(new Input("denied", null, null), new Input("boom", null, null), new Input("fine", null, null));
        SampleChecker.Matrix m = new SampleChecker().check(List.of("legs"), inputs, in -> {
            switch (in.name()) {
                case "denied" -> throw new SampleChecker.NoAccess("you may not open trade entities");
                case "boom" -> throw new IllegalStateException("source down");
                default -> {
                    return view("1", new PanelView("legs", "table", "legs", null, null, "main", false, null, null, null, false, null, null, "linked entity"));
                }
            }
        });
        assertThat(m.panel("legs").cells()).extracting(SampleChecker.Cell::status).containsExactly("noAccess", "error", "noAccess");
        assertThat(m.panel("legs").cells().get(0).message()).contains("may not open");
        assertThat(m.panel("legs").cells().get(1).message()).isEqualTo("source down");
        assertThat(m.samples()).extracting(SampleChecker.SampleRow::status).containsExactly("noAccess", "error", "ok");
        assertThat(m.ok()).isFalse();
    }

    @Test
    void panelsFirstSeenInALaterSampleAreEmptyInTheEarlierOnes() {
        SampleChecker.Matrix m = new SampleChecker().check(List.of(), List.of(new Input("a", null, null), new Input("b", null, null)),
                in -> "a".equals(in.name()) ? view("1", panel("x", null, false)) : view("1", panel("x", null, false), panel("y", null, false)));
        assertThat(m.panel("y").cells()).extracting(SampleChecker.Cell::status).containsExactly("empty", "ok");
        assertThat(m.ok()).isTrue();
    }
}
