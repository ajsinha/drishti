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
package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A BI table over an array at any depth ({@code each: LEGS[].CASH_FLOWS[].RESETS}): a column reads the element, the levels above it or the document. */
class NestedEachTest {

    static final DataNode DEAL = new JsonCodec().read("""
            {"TRADE_ID": 100001, "LEGS": [{"LEG_ID": 2, "CASH_FLOWS": [{"FLOW_ID": 7, "RESETS": [{"RATE": "78.5"}]}]}]}""");

    @Test
    void aColumnReadsTheElementTheLevelsAboveOrTheDocument() {
        DataNode leg = DEAL.at("LEGS").get(0);
        DataNode flow = leg.at("CASH_FLOWS").get(0);
        DataNode reset = flow.at("RESETS").get(0);
        List<DataNode> chain = List.of(DEAL, leg, flow, reset);
        assertThat(TableBuilder.resolve(chain, "RATE").asText()).isEqualTo("78.5");
        assertThat(TableBuilder.resolve(chain, "^.FLOW_ID").asDouble()).isEqualTo(7);
        assertThat(TableBuilder.resolve(chain, "^^.LEG_ID").asDouble()).isEqualTo(2);
        assertThat(TableBuilder.resolve(chain, "$.TRADE_ID").asDouble()).isEqualTo(100001);
        assertThat(TableBuilder.resolve(chain, "^^^^.TRADE_ID").isNull()).isTrue();          // above the document: nothing
        assertThat(TableBuilder.resolve(List.of(DEAL, leg), "^LEG_ID").isNull()).isTrue();    // ^ is the document here, which has no LEG_ID
    }
}
