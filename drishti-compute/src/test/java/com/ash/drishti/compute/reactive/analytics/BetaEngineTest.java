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
package com.ash.drishti.compute.reactive.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.compute.EngineInputs;
import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.Viewer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** EQB: an equity's beta against its index from their price histories, replacing the stored BETA. */
class BetaEngineTest {

    @Test
    void twiceTheIndexsLogReturnIsABetaOfTwo() throws Exception {
        TreeMap<String, Double> idx = new TreeMap<>();
        TreeMap<String, Double> eq = new TreeMap<>();
        double i = 100;
        double e = 50;
        for (int d = 1; d <= 25; d++) {
            double r = (d % 3 == 0 ? -0.01 : 0.007) + (d % 5) * 0.001;
            i *= Math.exp(r);
            e *= Math.exp(2 * r);
            idx.put(String.format("2026-09-%02d", d), i);
            eq.put(String.format("2026-09-%02d", d), e);
        }
        Map<String, Object> row = BetaEngine.beta("EQ-A", "EQX-I", eq, idx, 0);
        assertThat((double) row.get("BETA")).isCloseTo(2.0, offset(1e-4));
        assertThat((double) row.get("CORRELATION")).isCloseTo(1.0, offset(1e-4));
        assertThat(row.get("WINDOW")).isEqualTo(24);
        assertThat(BetaEngine.beta("EQ-A", "EQX-I", eq, idx, 10).get("FROM")).isEqualTo("2026-09-15");
    }

    @Test
    void anEquityWithoutAnIndexIsRefusedInWords() {
        Map<String, List<DataNode>> in = new LinkedHashMap<>();
        in.put("equity", List.of(DataNode.of(Map.of("TICKER", "EQ-A"))));
        in.put("index", List.of());
        EngineRequest req = new EngineRequest("r1", "equity-analytics", "equity-beta", "EQ-A", Map.of(), null, null, new Viewer("t", List.of(), "s"), null, 0, null);
        assertThatThrownBy(() -> new BetaEngine().compute(req, new EngineInputs(in), p -> {})).hasMessageContaining("names no index");
    }
}
