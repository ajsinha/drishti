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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotDecomposition;
import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.kernel.api.pivot.Viewer;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/** A split of the decomposition tree: an additive measure adds exactly, a holistic one states the gap, a path narrows the slice. */
class PivotDecompositionTest {

    final PivotFixture fx = new PivotFixture(500, StateCache.none(), PivotCore.Settings.defaults());

    private Map<String, Object> ask(String measure, String split, List<Map<String, Object>> path) {
        Map<String, Object> b = new java.util.HashMap<>(Map.of("source", "trades", "measure", measure));
        if (split != null) {
            b.put("split", split);
        }
        b.put("path", path);
        return PivotDecomposition.answer(fx.plain, Viewer.of("rita"), b);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> kids(Map<String, Object> a) {
        return (List<Map<String, Object>>) a.get("children");
    }

    private static double of(Map<String, Object> m) {
        return ((Number) m.get("value")).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private static double node(Map<String, Object> a) {
        return of((Map<String, Object>) a.get("node"));
    }

    @Test
    void aSumsChildrenAddUpExactlyAndNoGapIsStated() {
        Map<String, Object> a = ask("sum(NOTIONAL)", "DESK", List.of());
        assertThat(a.get("additive")).isEqualTo(true);
        assertThat(a).doesNotContainKey("diversification");
        assertThat(kids(a).stream().mapToDouble(PivotDecompositionTest::of).sum()).isCloseTo(node(a), Offset.offset(0.01));
    }

    @Test
    void aMedianIsItsOwnFigureAndTheGapIsTheEnginesNotTheBrowsers() {
        Map<String, Object> a = ask("median(NOTIONAL)", "DESK", List.of());
        assertThat(a.get("additive")).isEqualTo(false);
        double sum = kids(a).stream().mapToDouble(PivotDecompositionTest::of).sum();
        assertThat(((Number) a.get("sumStandalone")).doubleValue()).isCloseTo(sum, Offset.offset(0.01));
        assertThat(((Number) a.get("diversification")).doubleValue()).isCloseTo(sum - node(a), Offset.offset(0.01));
    }

    @Test
    void aPathNarrowsTheSliceAndAFixedDimensionIsRefused() {
        Map<String, Object> whole = ask("sum(NOTIONAL)", "REGION", List.of());
        Map<String, Object> first = kids(whole).get(0);
        Map<String, Object> below = ask("sum(NOTIONAL)", "DESK", List.of(Map.of("field", "REGION", "value", first.get("member"))));
        assertThat(node(below)).isCloseTo(of(first), Offset.offset(0.01));
        assertThatThrownBy(() -> ask("sum(NOTIONAL)", "REGION", List.of(Map.of("field", "REGION", "value", first.get("member")))))
                .hasMessageContaining("already fixes REGION");
    }

    @Test
    void theRootAloneNeedsNoSplit() {
        Map<String, Object> a = ask("sum(NOTIONAL)", null, List.of());
        assertThat(kids(a)).isEmpty();
        assertThat(node(a)).isPositive();
    }
}
