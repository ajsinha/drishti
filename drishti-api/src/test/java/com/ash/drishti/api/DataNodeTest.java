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
package com.ash.drishti.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataNodeTest {

    private final DataNode doc = DataNode.of(Map.of(
            "tradeId", "IRS-48213",
            "legs", List.of(Map.of("rate", 0.0385, "cashflows", List.of(Map.of("amount", -1962430.56))), Map.of("rate", 0)),
            "odd key", true));

    @Test
    void walksPaths() {
        assertThat(doc.at("$.tradeId").asText()).isEqualTo("IRS-48213");
        assertThat(doc.at("legs[0].rate").asDouble()).isEqualTo(0.0385);
        assertThat(doc.at("$.legs[0].cashflows[0].amount").asDouble()).isEqualTo(-1962430.56);
        assertThat(doc.at("legs[-1].rate").asText()).isEqualTo("0");
        assertThat(doc.at("['odd key']").asBoolean()).isTrue();
    }

    @Test
    void missingNeverThrows() {
        assertThat(doc.at("legs[9].rate").isMissing()).isTrue();
        assertThat(doc.at("nope.deeper[0]").isMissing()).isTrue();
        assertThat(doc.at("legs[x]").isMissing()).isTrue();
        assertThat(doc.at("tradeId").get("x").asText()).isEmpty();
    }

    @Test
    void wholeNumbersPrintWithoutDecimals() {
        assertThat(DataNode.of(50000000.0).asText()).isEqualTo("50000000");
        assertThat(DataNode.of(1.5).asText()).isEqualTo("1.5");
    }
}
