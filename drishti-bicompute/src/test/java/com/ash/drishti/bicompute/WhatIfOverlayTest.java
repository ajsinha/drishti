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
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.bicompute.whatif.Overlay;
import com.ash.drishti.bicompute.whatif.WhatIf;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The what-if overlay's conditions (ARCHITECTURE.md section 23.10, item 3): leaving members out is the negated condition (every other row kept,
 * including the members the condition does not name), and a change without a condition applies to every row.
 */
class WhatIfOverlayTest {

    static PivotView view() {
        return PivotView.parse(Map.of("source", "trades", "rows", List.of("DESK"), "values", List.of("sum(NOTIONAL)")));
    }

    @SuppressWarnings("unchecked")
    static double grand(Map<String, Object> w, String side) {
        Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) ((Map<String, Object>) w.get(side)).get("cells");
        return ((Number) ((Map<String, Object>) cells.get("*|*").get("values")).get("sum(NOTIONAL)")).doubleValue();
    }

    static double sql(PivotFixture fx, String query) {
        return fx.exec.query(query, List.of(), rs -> {
            rs.next();
            return rs.getDouble(1);
        });
    }

    @Test
    void leavingMembersOutKeepsEveryOtherRow() {
        try (PivotFixture fx = new PivotFixture(2_000, StateCache.none(), PivotCore.Settings.defaults())) {
            Map<String, Object> w = WhatIf.run(fx.plain, Viewer.of("rita"), view(), Overlay.parse(Map.of("exclude", Map.of("DESK", List.of("FX", "RATES")))));
            assertThat(grand(w, "whatIf")).isCloseTo(sql(fx, "SELECT sum(NOTIONAL) FROM trades WHERE DESK NOT IN ('FX', 'RATES')"), offset(1e-6));
            assertThat(grand(w, "original")).isCloseTo(sql(fx, "SELECT sum(NOTIONAL) FROM trades"), offset(1e-6));
        }
    }

    @Test
    void aChangeWithoutAConditionAppliesToEveryRow() {
        try (PivotFixture fx = new PivotFixture(2_000, StateCache.none(), PivotCore.Settings.defaults())) {
            Map<String, Object> w = WhatIf.run(fx.plain, Viewer.of("rita"), view(), Overlay.parse(Map.of("changes", List.of(Map.of("field", "NOTIONAL",
                    "scale", 2)))));
            assertThat(grand(w, "whatIf")).isCloseTo(2 * sql(fx, "SELECT sum(NOTIONAL) FROM trades"), offset(1e-6));
        }
    }
}
