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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance,logistics"})
@AutoConfigureMockMvc
class ImpactTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    @Test
    void aCurveMoveReachesItsTradesAndTheirNettingSets() throws Exception {
        String risk = "Bearer " + tokens.mint("rita", List.of("risk"), 300);
        mvc.perform(get("/api/v1/impact/curve/USD-SOFR").header("Authorization", risk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].level").value(1))
                .andExpect(jsonPath("$.groups[0].kind").value("trade"))
                .andExpect(jsonPath("$.groups[0].items[*].ref.id").value(hasItem("IRS-48213")))
                .andExpect(jsonPath("$.groups[0].total").exists())
                .andExpect(jsonPath("$.groups[?(@.level==2)].kind").value(hasItem("netting-set")))
                .andExpect(jsonPath("$.groups[?(@.level==2)].items[*].via").value(hasItem("nettingSet")));
    }

    @Test
    void aNettingSetShowsItsMemberTradesAndTradersSeeHiddenCounts() throws Exception {
        String risk = "Bearer " + tokens.mint("rita", List.of("risk"), 300);
        mvc.perform(get("/api/v1/impact/netting-set/NS-NORTH-01").header("Authorization", risk))
                .andExpect(jsonPath("$.groups[0].items.length()").value(14));
        String ops = "Bearer " + tokens.mint("oli", List.of("ops"), 300);
        mvc.perform(get("/api/v1/impact/port/PORT-NLRTM").header("Authorization", ops))
                .andExpect(jsonPath("$.groups[0].kind").value("shipment"))
                .andExpect(jsonPath("$.groups[0].items[0].ref.id").value("SHP-10042"));
        String trader = "Bearer " + tokens.mint("tina", List.of("trader"), 300);
        mvc.perform(get("/api/v1/impact/curve/USD-SOFR").header("Authorization", trader))
                .andExpect(jsonPath("$.groups[?(@.kind==\'netting-set\')].hidden").value(hasItem(2)));
    }
}
