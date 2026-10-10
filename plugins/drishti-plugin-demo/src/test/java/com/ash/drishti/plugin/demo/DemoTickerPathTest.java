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
package com.ash.drishti.plugin.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import org.junit.jupiter.api.Test;

/** A sample's {@code _meta.walk} may name a number deep in the document: a fixing under a cashflow under a leg. */
class DemoTickerPathTest {

    private final JsonCodec json = new JsonCodec();

    private static final String DOC = """
            {"mtm": 100, "legs": [{"leg": 1, "cashflows": [{"rate": 1.0}]},
                                  {"leg": 2, "cashflows": [{"rate": 2.0, "fixings": [{"rate": 70.0}, {"rate": 71.0}]}]}]}""";

    @Test
    void aDottedPathWalksOneNumberInsideTheDocument() {
        DemoTicker ticker = new DemoTicker(7);
        DataNode next = ticker.tick("trade", json.read(DOC), json.read("{\"legs.1.cashflows.0.fixings.1.rate\": 0.5}"));
        assertThat(next.get("legs").get(1).get("cashflows").get(0).get("fixings").get(1).get("rate").asDouble()).isNotEqualTo(71.0);
        assertThat(next.get("legs").get(1).get("cashflows").get(0).get("fixings").get(0).get("rate").asDouble()).isEqualTo(70.0);
        assertThat(next.get("legs").get(0).get("cashflows").get(0).get("rate").asDouble()).isEqualTo(1.0);
        assertThat(next.get("mtm").asDouble()).isEqualTo(100.0);
    }

    @Test
    void aPathThatLeavesTheDocumentLeavesItAlone() {
        DemoTicker ticker = new DemoTicker(7);
        DataNode next = ticker.tick("trade", json.read(DOC), json.read("{\"legs.5.cashflows.0.rate\": 0.5, \"legs.1.nothing.rate\": 1, \"mtm\": 1000}"));
        assertThat(next.get("legs").get(1).get("cashflows").get(0).get("rate").asDouble()).isEqualTo(2.0);
        assertThat(next.get("mtm").asDouble()).isNotEqualTo(100.0);
    }
}
