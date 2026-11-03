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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VaR by a trade column on the server ({@code GET /api/v1/var/scope/contributions?by=attribute:<COLUMN>}, VAR_GUIDE.md section 11):
 * the tiny lake of {@link VarLakeFixture} with trade valuations naming each trade's trader (TRDR-A: T1 and T2, 60U; TRDR-B: T3, T4 and
 * T9, 10V - 20U; TRDR-C: T5, 50V), and a contributions page of two parts ({@code max-parts=2}), fewer than the bank's six trades. The
 * bank is 40U + 60V, VaR 540 on scenario 12; its components average the three worst scenarios (12, 3, 7: -540, -380, -340) scaled by
 * 540/420, so by hand TRDR-A 462.86, TRDR-B -115.71 and TRDR-C 192.86, adding up to 540. TRADER_ID is masked for a role without raw.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.kinds[4]=trade-valuation",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.risk-lake.settings.layout.trade-valuation.columns=TRADE_ID,TRADER_ID",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.trade-valuation=risk-lake",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.official.on-start=false", "drishti.var.max-parts=2",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact[0]=TRADER_ID",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarByAttributeApiTest {

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-attr-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake);
        Map<String, String> traders = Map.of("T1", "TRDR-A", "T2", "TRDR-A", "T3", "TRDR-B", "T4", "TRDR-B", "T9", "TRDR-B", "T5", "TRDR-C");
        List<Map<String, Object>> rows = new ArrayList<>();
        traders.forEach((t, who) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("VALUATION_ID", "TVAL-" + t);
            d.put("TRADE_ID", t);
            d.put("TRADER_ID", who);
            d.put("BUSINESS_DATE", VarLakeFixture.DAY.toString());
            rows.add(d);
        });
        VarLakeFixture.table(lake, Files.createDirectories(lake.resolve("_work")), "trade-valuation", "VALUATION_ID", rows,
                List.of("TRADE_ID", "TRADER_ID"), null);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode contributions(String scope, String by, String auth) throws Exception {
        var r = mvc.perform(get("/api/v1/var/scope/contributions").param("scope", scope).param("by", by).header("Authorization", auth))
                .andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return json.readTree(r.getContentAsString());
    }

    @Test
    void theTradersAddUpToTheBanksVarOverEveryTradeNotOnlyTheLargest() throws Exception {
        String rita = as("rita", "risk");
        JsonNode trades = contributions("BANK", "trade", rita);
        assertThat(trades.get("truncated").asBoolean()).isTrue();                 // six trades, a page of two
        assertThat(trades.get("rows")).hasSize(2);

        JsonNode a = contributions("BANK", "attribute:TRADER_ID", rita);
        assertThat(a.get("by").asText()).isEqualTo("attribute");
        assertThat(a.get("attribute").asText()).isEqualTo("TRADER_ID");
        assertThat(a.get("masked").asBoolean()).isFalse();
        assertThat(a.get("truncated").asBoolean()).isFalse();
        assertThat(a.get("var").asLong()).isEqualTo(540);
        Map<String, Long> comp = new LinkedHashMap<>();
        Map<String, Integer> count = new LinkedHashMap<>();
        long sum = 0;
        for (JsonNode r : a.get("rows")) {
            comp.put(r.get("key").asText(), r.get("componentVar").asLong());
            count.put(r.get("key").asText(), r.get("trades").asInt());
            sum += r.get("componentVar").asLong();
        }
        assertThat(comp).containsExactly(Map.entry("TRDR-A", 463L), Map.entry("TRDR-C", 193L), Map.entry("TRDR-B", -116L));
        assertThat(count).containsEntry("TRDR-A", 2).containsEntry("TRDR-B", 3).containsEntry("TRDR-C", 1);
        assertThat(sum).isEqualTo(540);
        assertThat(a.get("sumComponent").asLong()).isEqualTo(a.get("var").asLong());   // summed in Java before rounding: to the cent
        assertThat(a.get("sumStandalone").asLong()).isEqualTo(600 + 450 + 120);          // alone: 60U 600, 50V 450, 10V - 20U 120
    }

    @Test
    void aColumnOfTheVectorsGroupsWithoutAJoinAndADeskCountsAllItsTrades() throws Exception {
        JsonNode desk = contributions("DESK:DESK-X", "attribute:counterparty", as("rita", "risk"));
        assertThat(desk.get("attribute").asText()).isEqualTo("COUNTERPARTY");
        assertThat(desk.get("rows")).hasSize(1);                                  // DESK-X's five trades are all CP-1's
        assertThat(desk.at("/rows/0/trades").asInt()).isEqualTo(5);
        assertThat(desk.at("/rows/0/componentVar").asLong()).isEqualTo(desk.get("var").asLong());
    }

    @Test
    void aRoleThatSeesTheColumnMaskedGetsNoGroupsAndIsToldWhy() throws Exception {
        JsonNode m = contributions("BANK", "attribute:TRADER_ID", as("mo", "masked"));
        assertThat(m.get("masked").asBoolean()).isTrue();
        assertThat(m.get("rows")).isEmpty();
        assertThat(m.get("var").asLong()).isEqualTo(540);
        assertThat(m.get("note").asText()).contains("TRADER_ID is masked for your role");
    }

    @Test
    void anUnknownColumnIsRefusedInWords() throws Exception {
        mvc.perform(get("/api/v1/var/scope/contributions").param("scope", "BANK").param("by", "attribute:NOPE")
                .header("Authorization", as("rita", "risk"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("NOPE is not a column of the VaR book's trades")));
    }
}
