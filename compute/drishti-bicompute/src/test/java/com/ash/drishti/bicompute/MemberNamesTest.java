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
package com.ash.drishti.bicompute;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.kernel.api.pivot.Viewer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A provider's member names reach the row labels; ids, paths and figures stay as they are. */
class MemberNamesTest {

    @Test
    @SuppressWarnings("unchecked")
    void aNamedMemberShowsItsNameAndKeepsItsIdAndFigures() throws Exception {
        Map<String, Object> v = Map.of("source", "trades", "rows", List.of("DESK"), "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N")));
        try (PivotFixture fx = new PivotFixture(500, StateCache.none(), PivotCore.Settings.defaults())) {
            Viewer rita = Viewer.of("rita");
            Map<String, Object> before = fx.core.query(rita, PivotView.parse(v));
            fx.counting.names = (dim, value) -> "DESK".equals(dim) && "RATES".equals(value) ? "Rates Trading" : null;
            Map<String, Object> after = fx.core.query(rita, PivotView.parse(v));

            assertThat(after.get("cells")).isEqualTo(before.get("cells"));
            Map<String, Object> rates = ((List<Map<String, Object>>) after.get("rows")).stream().filter(r -> "RATES".equals(r.get("value"))).findFirst().orElseThrow();
            assertThat(rates).containsEntry("label", "Rates Trading").containsEntry("name", "Rates Trading");
            assertThat(String.valueOf(rates.get("path"))).contains("RATES").doesNotContain("Rates Trading");
            Map<String, Object> fx2 = ((List<Map<String, Object>>) after.get("rows")).stream().filter(r -> "FX".equals(r.get("value"))).findFirst().orElseThrow();
            assertThat(fx2).containsEntry("label", "FX").doesNotContainKey("name");
        }
    }
}
