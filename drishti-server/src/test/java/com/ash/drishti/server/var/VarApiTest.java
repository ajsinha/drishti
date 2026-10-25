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
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The VaR engine's API over a tiny lake whose answers are known by hand (VarLakeFixture: 20 scenarios, so VaR 99% and ES 97.5% are
 * the worst scenario's loss): the bank, desks, a position netting a long and a short, single trades, RULE and BASKET portfolios and a
 * union of overlapping ones, contributions adding up to the VaR, the official history with its exception, entitlements by node
 * (positions or aggregate level), saved baskets, and captures that read back exactly after the data changes.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=position",
        "drishti.sources.connectors.risk-lake.kinds[2]=scenario-set", "drishti.sources.connectors.risk-lake.kinds[3]=risk-hierarchy",
        "drishti.sources.connectors.risk-lake.kinds[4]=var-node", "drishti.sources.connectors.risk-lake.kinds[5]=portfolio",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,POSITION_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,CURRENCY,COUNTERPARTY,DIRECTION,INSTRUMENT_ID,QUANTITY,PV",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.risk-lake.settings.layout.var-node.columns=NODE_ID,VAR_99,ES_975,HYPO_PNL,LIMIT",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=risk-lake",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.var-node=risk-lake", "drishti.sources.routes.portfolio=risk-lake",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*",
        "drishti.security.roles.desk-y.kinds[0]=*", "drishti.var.access.desk-y[MGMT~DESK-Y]=positions",
        "drishti.security.roles.division-head.kinds[0]=*", "drishti.var.access.division-head[MGMT~DESK-X]=aggregate",
        "drishti.security.roles.curves.kinds[0]=curve",
        "drishti.var.threads=2",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VarApiTest {

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode getJson(String url, String scope, String auth) throws Exception {
        String body = mvc.perform(get(url).param("scope", scope).header("Authorization", auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private double var(String scope) throws Exception {
        return getJson("/api/v1/var/scope", scope, as("rita", "risk")).get("var").asDouble();
    }

    @Test
    @Order(1)
    void nodesPositionsAndTradesHaveTheirHandComputedVar() throws Exception {
        JsonNode bank = getJson("/api/v1/var/scope", "BANK", as("rita", "risk"));
        assertThat(bank.get("var").asDouble()).isEqualTo(540);                 // 40U + 60V: worst -540 on scenario 12
        assertThat(bank.get("es").asDouble()).isEqualTo(540);
        assertThat(bank.get("children").asInt()).isEqualTo(2);
        assertThat(bank.get("sumStandalone").asDouble()).isEqualTo(390 + 450);
        assertThat(bank.get("diversification").asDouble()).isEqualTo(300);
        assertThat(bank.get("limit").asDouble()).isEqualTo(5000);
        assertThat(bank.at("/provenance/trades").asInt()).isEqualTo(5);
        assertThat(bank.at("/provenance/positions").asInt()).isEqualTo(4);
        assertThat(var("DESK:DESK-X")).isEqualTo(390);
        assertThat(var("DESK:X")).isEqualTo(390);                             // DESK:X is DESK-X
        assertThat(var("LE:LE-B")).isEqualTo(450);
        assertThat(var("BOOK:BOOK-1")).isEqualTo(590);
        // a position netting a long of 100 and a short of 40: 60 units, VaR 600; its trades alone 1000 and 240
        assertThat(var("POS:POS-A")).isEqualTo(600);
        assertThat(var("TRD:T1")).isEqualTo(1000);
        assertThat(var("TRD:T2")).isEqualTo(240);
        assertThat(var("TRD:T1,T2")).isEqualTo(600);
        JsonNode pos = getJson("/api/v1/var/scope", "POS:POS-A", as("rita", "risk"));
        assertThat(pos.get("kind").asText()).isEqualTo("position");
        assertThat(pos.get("ancestors").get(pos.get("ancestors").size() - 1).get("scope").asText()).isEqualTo("NODE:MGMT~BOOK-1");
    }

    @Test
    @Order(2)
    void portfoliosOfEveryTypeAndOverlapsCountOnce() throws Exception {
        assertThat(var("PORT:PF-EUR")).isEqualTo(120);                        // the rule CURRENCY == 'EUR': POS-B and POS-C
        assertThat(var("PORT:PF-MIX")).isEqualTo(540);                        // POS-B, T4, T5
        assertThat(var("PORT:PF-EUR + PORT:PF-MIX")).isEqualTo(540);          // the overlap (POS-B, T4) once
        assertThat(var("WHERE:CURRENCY == 'EUR'")).isEqualTo(120);
        assertThat(var("BANK - DESK:DESK-Y")).isEqualTo(390);                 // a difference
        JsonNode c = getJson("/api/v1/var/scope/contributions", "BANK", as("rita", "risk"));
        double sum = 0;
        for (JsonNode r : c.get("rows")) {
            sum += r.get("componentVar").asDouble();
        }
        assertThat(sum).isCloseTo(540, within(1.0));                            // components add up to the VaR (rounded each)
        assertThat(c.get("rows").get(0).get("key").asText()).isEqualTo("MGMT~DESK-X");     // 347 before DESK-Y's 193
        assertThat(c.get("rows").get(0).get("componentVar").asDouble()).isEqualTo(347);
        JsonNode byTrade = getJson("/api/v1/var/scope/contributions?by=trade", "BOOK:BOOK-1", as("rita", "risk"));
        assertThat(byTrade.get("rows")).hasSize(3);
        JsonNode drill = getJson("/api/v1/var/scope/contributions?scenario=" + VarLakeFixture.scenarioDates().get(12), "BANK", as("rita", "risk"));
        assertThat(drill.get("scenarioPnl").asDouble()).isEqualTo(-540);
        assertThat(drill.get("scenarioRank").asInt()).isEqualTo(1);
        assertThat(drill.get("rows").get(0).get("scenarioPnl").asDouble()).isEqualTo(-450);   // DESK-Y lost most that day
    }

    @Test
    @Order(3)
    void pathHistoryAndTree() throws Exception {
        JsonNode p = getJson("/api/v1/var/scope/path?page=0&size=5", "BANK", as("rita", "risk"));
        assertThat(p.get("series")).hasSize(20);
        assertThat(p.get("worst").get(0).get("pnl").asDouble()).isEqualTo(-540);
        assertThat(p.get("rows")).hasSize(5);
        assertThat(p.get("rows").get(0).get("from").asText()).isEqualTo("DESK-Y");
        int binned = 0;
        for (JsonNode n : p.at("/histogram/counts")) {
            binned += n.asInt();
        }
        assertThat(binned).isEqualTo(20);
        JsonNode h = getJson("/api/v1/var/scope/history", "DESK:DESK-X", as("rita", "risk"));
        assertThat(h.get("mode").asText()).isEqualTo("official");
        assertThat(h.at("/backtest/exceptions").asInt()).isEqualTo(1);
        assertThat(h.get("rows").get(2).get("exception").asBoolean()).isTrue();
        assertThat(getJson("/api/v1/var/scope/history", "TRD:T1", as("rita", "risk")).get("mode").asText()).isEqualTo("replay");
        JsonNode t = getJson("/api/v1/var/tree", "", as("rita", "risk"));
        assertThat(t.get("nodes")).hasSize(6);
        assertThat(t.get("portfolios")).hasSize(2);
    }

    @Test
    @Order(4)
    void entitlementsByNode() throws Exception {
        // positions level on DESK-Y only: the bank is what they see of it, the rest counted as hidden
        JsonNode bank = getJson("/api/v1/var/scope", "BANK", as("yan", "desk-y"));
        assertThat(bank.get("var").asDouble()).isEqualTo(450);
        assertThat(bank.at("/provenance/hidden").asInt()).isEqualTo(4);
        mvc.perform(get("/api/v1/var/scope").param("scope", "TRD:T1").header("Authorization", as("yan", "desk-y")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(containsString("position by position")));
        // aggregate level on DESK-X: its numbers, never a position
        assertThat(getJson("/api/v1/var/scope", "DESK:DESK-X", as("dora", "division-head")).get("var").asDouble()).isEqualTo(390);
        mvc.perform(get("/api/v1/var/scope/contributions").param("scope", "DESK:DESK-X").param("by", "position")
                .header("Authorization", as("dora", "division-head"))).andExpect(status().isForbidden());
        JsonNode below = getJson("/api/v1/var/scope/contributions", "BOOK:BOOK-1", as("dora", "division-head"));
        assertThat(below.get("rows")).isEmpty();
        String all = mvc.perform(get("/api/v1/var/tree").header("Authorization", as("dora", "division-head"))).andReturn().getResponse()
                .getContentAsString();
        assertThat(all).doesNotContain("POS-").doesNotContain("\"T1\"");
        // a role that may not open the vectors sees nothing
        mvc.perform(get("/api/v1/var/scope").param("scope", "BANK").header("Authorization", as("cara", "curves")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/var/scope").param("scope", "TEAM:X").header("Authorization", as("rita", "risk")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("not a scope word")));
    }

    @Test
    @Order(5)
    void basketsAndCapturesReadBackExactlyAfterTheDataChanges() throws Exception {
        String rita = as("rita", "risk");
        mvc.perform(post("/api/v1/var/baskets").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"netted\",\"title\":\"Netted swaps\",\"scope\":\"TRD:T1,T2 + POS:POS-B\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("rita/netted")).andExpect(jsonPath("$.type").value("BASKET"));
        double basketVar = var("BASKET:rita/netted");
        JsonNode cap = json.readTree(mvc.perform(post("/api/v1/var/captures").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"BASKET:rita/netted\",\"note\":\"before\"}")).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
        String id = cap.get("id").asText();
        assertThat(id).startsWith("CAP-20260930-");
        assertThat(cap.get("var").asDouble()).isEqualTo(basketVar);
        // someone else cannot read Rita's basket or capture
        mvc.perform(get("/api/v1/var/captures/" + id).header("Authorization", as("ola", "risk"))).andExpect(status().isNotFound());

        // the batch is restated: every vector doubles; the capture keeps its numbers, now moves
        VarLakeFixture.write(lake, 2.0);
        Thread.sleep(1500);
        mvc.perform(post("/api/v1/var/reload").header("Authorization", as("ada", "admin"))).andExpect(status().isOk());
        assertThat(var("BASKET:rita/netted")).isEqualTo(2 * basketVar);
        JsonNode again = json.readTree(mvc.perform(get("/api/v1/var/captures/" + id).header("Authorization", rita)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(again.get("var").asDouble()).isEqualTo(basketVar);
        assertThat(again.get("vector")).hasSize(20);
        assertThat(again.get("members")).hasSize(3);
        assertThat(again.get("note").asText()).isEqualTo("before");
        JsonNode cmp = json.readTree(mvc.perform(get("/api/v1/var/compare").param("a", id).param("b", "now").header("Authorization", rita))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cmp.get("varChange").asDouble()).isEqualTo(basketVar);
        assertThat(cmp.get("membersAdded")).isEmpty();
        JsonNode list = json.readTree(mvc.perform(get("/api/v1/var/captures").param("scope", "BASKET:rita/netted").header("Authorization", rita))
                .andReturn().getResponse().getContentAsString());
        assertThat(list).hasSize(1);
        String csv = mvc.perform(get("/api/v1/var/captures/" + id + "/csv").header("Authorization", rita)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("scenario_date,pnl").contains("# note: before");
    }
}
