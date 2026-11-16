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
package com.ash.drishti.engine.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.model.Control;
import com.ash.drishti.rachana.model.ControlKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A slider's value: held to its bounds, snapped to its step, a single number or {@code from..to}, and the default when the request's value is no use. */
class SliderValueTest {

    private static Control slider(boolean range, String def) {
        return new Control("s", ControlKind.SLIDER, null, List.of(), null, null, def, false, false, Control.Placement.STRIP, null, null, "0", "100", "10", range, null);
    }

    @Test
    void aSingleValueIsHeldToTheBoundsAndSnappedToTheStep() {
        Control c = slider(false, null);
        assertThat(ControlResolver.slider(c, "40", "0", "100", "10")).isEqualTo("40");
        assertThat(ControlResolver.slider(c, "43", "0", "100", "10")).isEqualTo("40");
        assertThat(ControlResolver.slider(c, "47", "0", "100", "10")).isEqualTo("50");
        assertThat(ControlResolver.slider(c, "-500", "0", "100", "10")).isEqualTo("0");
        assertThat(ControlResolver.slider(c, "5000", "0", "100", "10")).isEqualTo("100");
        assertThat(ControlResolver.slider(c, "0.25", "0", "1", "0.25")).isEqualTo("0.25");
    }

    @Test
    void theTopOfTheScaleIsReachableWhenTheStepDoesNotDivideIt() {
        Control c = slider(false, null);
        assertThat(ControlResolver.slider(c, "100", "0", "95", "10")).isEqualTo("95");
        assertThat(ControlResolver.slider(c, "90", "0", "95", "10")).isEqualTo("90");
    }

    @Test
    void aValueThatIsNotANumberFallsBackToTheDefaultThenToTheLowerBound() {
        assertThat(ControlResolver.slider(slider(false, "30"), "abc", "0", "100", "10")).isEqualTo("30");
        assertThat(ControlResolver.slider(slider(false, "30"), null, "0", "100", "10")).isEqualTo("30");
        assertThat(ControlResolver.slider(slider(false, null), "abc", "0", "100", "10")).isEqualTo("0");
        assertThat(ControlResolver.slider(slider(false, null), "20..80", "0", "100", "10")).as("a range for one handle is no use").isEqualTo("0");
    }

    @Test
    void aRangeIsOrderedHeldToTheBoundsAndStartsAtBothEnds() {
        Control c = slider(true, null);
        assertThat(ControlResolver.slider(c, "20..80", "0", "100", "10")).isEqualTo("20..80");
        assertThat(ControlResolver.slider(c, "80..20", "0", "100", "10")).isEqualTo("20..80");
        assertThat(ControlResolver.slider(c, "-5..999", "0", "100", "10")).isEqualTo("0..100");
        assertThat(ControlResolver.slider(c, null, "0", "100", "10")).isEqualTo("0..100");
        assertThat(ControlResolver.slider(c, "..50", "0", "100", "10")).as("an open end is no use for a slider").isEqualTo("0..100");
        assertThat(ControlResolver.slider(slider(true, "10..30"), "x", "0", "100", "10")).isEqualTo("10..30");
    }

    @Test
    void boundsThatCannotBeReadLeaveNoValueForASingleHandleAndTheTextAsGivenOtherwise() {
        assertThat(ControlResolver.slider(slider(false, null), null, null, "100", "10")).isEmpty();
        assertThat(ControlResolver.slider(slider(false, null), "42", null, null, null)).isEqualTo("42");
    }
}
