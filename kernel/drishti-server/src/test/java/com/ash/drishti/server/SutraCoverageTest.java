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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The trading pack's Sutra coverage: every source system x product type (x sub type) in its data is laid out by exactly one Sutra
 * (no inferred trade, no two Sutras for one), and each Endur sub type shows its own panels inside its product's one Sutra.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading"})
@AutoConfigureMockMvc
class SutraCoverageTest {

    @Autowired MockMvc mvc;

    @Test
    void everyCombinationOfTheTradesHasExactlyOneSutra() throws Exception {
        JsonNode out = new ObjectMapper().readTree(mvc.perform(get("/api/v1/packs/trading/coverage").param("kind", "trade"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode trade = out.path("kinds").get(0);
        assertThat(trade.path("kind").asText()).isEqualTo("trade");
        assertThat(trade.path("fields").toString()).contains("SOURCE_SYSTEM", "PRODUCT_TYPE", "PRODUCT_SUB_TYPE");
        List<String> wrong = new ArrayList<>();
        for (JsonNode r : trade.path("rows")) {
            if (!"one".equals(r.path("state").asText())) {
                wrong.add(r.path("values") + " " + r.path("state").asText() + " " + r.path("sutras"));
            }
        }
        assertThat(wrong).as("combinations laid out by no Sutra or by several").isEmpty();
        assertThat(trade.path("none").asInt()).isZero();
        assertThat(trade.path("several").asInt()).isZero();
        assertThat(trade.path("combinations").asInt()).isGreaterThanOrEqualTo(132);       // 125 products, 5 Endur types with 7 sub types
        String rows = trade.path("rows").toString();
        for (String[] sub : new String[][] {{"ENGY-B-SWAP", "basis"}, {"ENGY-SWAP", "fixed-float"}, {"NGX-COMM-PHYS", "delivery"},
                {"COMM-WARRANTS-INV", "inventory"}, {"NOSTRO", "deposit"}, {"CASH", "payment"}, {"ENGY-EXCH-FUT", "contract"}}) {
            boolean shown = false;
            for (JsonNode r : trade.path("rows")) {
                if (sub[0].equals(r.path("values").path("PRODUCT_SUB_TYPE").asText())) {
                    shown |= r.path("shown").toString().contains("\"" + sub[1] + "\"");
                }
            }
            assertThat(shown).as(sub[0] + " shows " + sub[1] + " in " + rows.length() + " chars of rows").isTrue();
        }
    }
}
