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
 * the worst scenario's loss): positions derived by the rules (a security's trades in one book net; a swap is its own position), rows
 * that do not count (cancelled, rejected), the bank, desks, a legal entity by its trades, a counterparty, single trades in any case,
 * RULE and BASKET portfolios and a union of overlapping ones, contributions adding up to the VaR, entitlements by node and field masks,
 * saved baskets, captures that read back exactly after the data changes, the official end-of-day captures with their history and
 * published table, a restated date, a date that is not one run and a date with a duplicate trade.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.var-result=var-engine",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.official.on-start=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact[0]=COUNTERPARTY",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.desk-y.kinds[0]=*", "drishti.security.roles.desk-y.raw=true", "drishti.var.access.desk-y[MGMT~DESK-Y]=positions",
        "drishti.security.roles.division-head.kinds[0]=*", "drishti.security.roles.division-head.raw=true",
        "drishti.var.access.division-head[MGMT~DESK-X]=aggregate",
        "drishti.security.roles.curves.kinds[0]=curve",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VarApiTest {

    static Path lake;
    static final java.time.LocalDate PREV = VarLakeFixture.DAY.minusDays(1);

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake, PREV, 1.0, false, false);
        VarLakeFixture.write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarOfficial official;
    @Autowired VarFigures figures;
    @Autowired VarService service;
    @Autowired com.ash.drishti.packs.ConnectorFiles connectors;
    @Autowired com.ash.drishti.server.connectors.ConnectorManager manager;
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
        // T1-T5 and T9 count; the cancelled T6 is left out, T7 (19 values) and T8 (a null in it) are rejected
        assertThat(bank.at("/provenance/trades").asInt()).isEqualTo(6);
        assertThat(bank.at("/provenance/positions").asInt()).isEqualTo(5);
        assertThat(bank.at("/provenance/excluded").asInt()).isEqualTo(1);
        assertThat(bank.at("/provenance/rejected").asInt()).isEqualTo(2);
        assertThat(bank.at("/provenance/rejections").toString()).contains("DRS-5201 T7: the vector has 19 values").contains("T8: the vector holds");
        assertThat(bank.at("/provenance/inputs/batchIds").toString()).contains("B-2026-09-30-01");
        assertThat(bank.at("/provenance/inputs/pnl-vector").asText()).isNotBlank();
        assertThat(var("DESK:DESK-X")).isEqualTo(390);
        assertThat(var("DESK:X")).isEqualTo(390);                             // DESK:X is DESK-X
        assertThat(var("BOOK:BOOK-1")).isEqualTo(590);
        // a security's long of 100 and short of 40 in one book: one position of 60 units, VaR 600; its trades alone 1000 and 240
        assertThat(var("POS:" + VarLakeFixture.POS_A)).isEqualTo(600);
        assertThat(var("TRD:T1")).isEqualTo(1000);
        assertThat(var("TRD:t1")).isEqualTo(1000);                            // an id in another case
        assertThat(var("TRD:T2")).isEqualTo(240);
        assertThat(var("TRD:T1,T2")).isEqualTo(600);
        assertThat(var("POS:" + VarLakeFixture.POS_C)).isEqualTo(var("TRD:T4"));   // a swap is a position of its own
        JsonNode pos = getJson("/api/v1/var/scope", "POS:" + VarLakeFixture.POS_A, as("rita", "risk"));
        assertThat(pos.get("kind").asText()).isEqualTo("position");
        assertThat(pos.get("ancestors").get(pos.get("ancestors").size() - 1).get("scope").asText()).isEqualTo("NODE:MGMT~BOOK-1");
        JsonNode trades = getJson("/api/v1/var/scope/contributions?by=trade", "POS:" + VarLakeFixture.POS_A, as("rita", "risk"));
        assertThat(trades.get("rows")).hasSize(2);
        assertThat(trades.get("rows").get(0).get("position").asText()).isEqualTo(VarLakeFixture.POS_A);
    }

    @Test
    @Order(2)
    void aLegalEntityIsItsTradesAndACounterpartyItsTrades() throws Exception {
        // LE-B books T5 (BOOK-3) and T9 (BOOK-2, of no risk): the trades' entity is the answer, the LEGAL tree's difference a warning
        JsonNode le = getJson("/api/v1/var/scope", "LE:LE-B", as("rita", "risk"));
        assertThat(le.get("var").asDouble()).isEqualTo(450);
        assertThat(le.at("/provenance/trades").asInt()).isEqualTo(2);
        assertThat(le.at("/provenance/warnings").toString()).contains("1 trades booked by LE-B sit outside LEGAL~LE-B");
        assertThat(getJson("/api/v1/var/scope", "LE:LE-A", as("rita", "risk")).at("/provenance/warnings").toString())
                .contains("LEGAL~LE-A holds 1 trades booked by another legal entity");
        assertThat(var("CPTY:CP-2")).isEqualTo(450);
        assertThat(var("CPTY:cp-1")).isEqualTo(var("DESK:DESK-X"));
    }

    @Test
    @Order(3)
    void portfoliosOfEveryTypeAndOverlapsCountOnce() throws Exception {
        assertThat(var("PORT:PF-EUR")).isEqualTo(120);                        // the rule CURRENCY == 'EUR': POS_B and POS_C
        assertThat(var("PORT:PF-MIX")).isEqualTo(540);                        // POS_B, T4, T5
        assertThat(var("PORT:PF-EUR + PORT:PF-MIX")).isEqualTo(540);          // the overlap (POS_B, T4) once
        assertThat(var("WHERE:CURRENCY == 'EUR'")).isEqualTo(120);
        assertThat(var("WHERE:POSITION_RULE == 'securities' and SECURITY_ID == 'SEC-A'")).isEqualTo(600);
        assertThat(var("BANK - DESK:DESK-Y")).isEqualTo(390);                 // a difference
        JsonNode c = getJson("/api/v1/var/scope/contributions", "BANK", as("rita", "risk"));
        double sum = 0;
        for (JsonNode r : c.get("rows")) {
            sum += r.get("componentVar").asDouble();
        }
        assertThat(sum).isCloseTo(540, within(1.0));                            // components add up to the VaR (rounded each)
        assertThat(c.get("rows").get(0).get("key").asText()).isEqualTo("MGMT~DESK-X");     // 347 before DESK-Y's 193
        assertThat(c.get("rows").get(0).get("componentVar").asDouble()).isEqualTo(347);
        assertThat(getJson("/api/v1/var/scope/contributions?by=node", "BANK", as("rita", "risk")).get("rows")).hasSize(2);
        mvc.perform(get("/api/v1/var/scope/contributions").param("scope", "TRD:T1").param("by", "node").header("Authorization", as("rita", "risk")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("is not a node")));
        JsonNode byTrade = getJson("/api/v1/var/scope/contributions?by=trade", "BOOK:BOOK-1", as("rita", "risk"));
        assertThat(byTrade.get("rows")).hasSize(3);
        assertThat(byTrade.get("truncated").asBoolean()).isFalse();
        JsonNode drill = getJson("/api/v1/var/scope/contributions?scenario=" + VarLakeFixture.scenarioDates().get(12), "BANK", as("rita", "risk"));
        assertThat(drill.get("scenarioPnl").asDouble()).isEqualTo(-540);
        assertThat(drill.get("scenarioRank").asInt()).isEqualTo(1);
        assertThat(drill.get("rows").get(0).get("scenarioPnl").asDouble()).isEqualTo(-450);   // DESK-Y lost most that day
    }

    @Test
    @Order(4)
    void pathReplayHistoryAndTree() throws Exception {
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
        // no official record yet: every scope replays today's members
        assertThat(getJson("/api/v1/var/scope/history", "DESK:DESK-X", as("rita", "risk")).get("mode").asText()).isEqualTo("replay");
        JsonNode t = getJson("/api/v1/var/tree", "", as("rita", "risk"));
        assertThat(t.get("nodes")).hasSize(6);
        assertThat(t.get("portfolios")).hasSize(2);
    }

    @Test
    @Order(5)
    void entitlementsByNodeAndFieldMasks() throws Exception {
        // positions level on DESK-Y only: the bank is what they see of it, the rest counted as hidden
        JsonNode bank = getJson("/api/v1/var/scope", "BANK", as("yan", "desk-y"));
        assertThat(bank.get("var").asDouble()).isEqualTo(450);
        assertThat(bank.at("/provenance/hidden").asInt()).isEqualTo(5);
        mvc.perform(get("/api/v1/var/scope").param("scope", "TRD:T1").header("Authorization", as("yan", "desk-y")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(containsString("position by position")));
        // aggregate level on DESK-X: its numbers, never a position, a trade or a basket portfolio of them
        assertThat(getJson("/api/v1/var/scope", "DESK:DESK-X", as("dora", "division-head")).get("var").asDouble()).isEqualTo(390);
        mvc.perform(get("/api/v1/var/scope/contributions").param("scope", "DESK:DESK-X").param("by", "position")
                .header("Authorization", as("dora", "division-head"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/var/scope").param("scope", "PORT:PF-MIX").header("Authorization", as("dora", "division-head")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/var/scope").param("scope", "CPTY:CP-1").header("Authorization", as("dora", "division-head")))
                .andExpect(status().isForbidden());
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
        // a masked counterparty: masked in the trade rows, and no scope may select by it
        JsonNode masked = getJson("/api/v1/var/scope/contributions?by=trade", "BOOK:BOOK-1", as("mo", "masked"));
        assertThat(masked.get("rows").get(0).get("counterparty").asText()).isEqualTo("•••");
        assertThat(getJson("/api/v1/var/scope/contributions?by=trade", "BOOK:BOOK-1", as("rita", "risk")).get("rows").get(0)
                .get("counterparty").asText()).isEqualTo("CP-1");
        mvc.perform(get("/api/v1/var/scope").param("scope", "CPTY:CP-1").header("Authorization", as("mo", "masked")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(containsString("masked")));
    }

    @Test
    @Order(6)
    void basketsAndCapturesReadBackExactlyAfterTheDataChanges() throws Exception {
        String rita = as("rita", "risk");
        mvc.perform(post("/api/v1/var/baskets").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"netted\",\"title\":\"Netted swaps\",\"scope\":\"TRD:T1,T2 + POS:" + VarLakeFixture.POS_B + "\"}"))
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

        // the official end-of-day records of both dates: every node of both hierarchies, published for other systems
        JsonNode prev = json.readTree(mvc.perform(post("/api/v1/var/official").param("asOf", PREV.toString()).header("Authorization", as("ada", "admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(prev.get("status").asText()).isEqualTo("captured");
        assertThat(prev.get("nodes").asInt()).isEqualTo(9);
        assertThat(prev.get("published").asBoolean()).isTrue();
        assertThat(official.capture(VarLakeFixture.DAY).get("status")).isEqualTo("captured");
        assertThat(official.capture(VarLakeFixture.DAY).get("status")).isEqualTo("unchanged");        // the same inputs: not again
        JsonNode h = getJson("/api/v1/var/scope/history", "DESK:DESK-X", rita);
        assertThat(h.get("mode").asText()).isEqualTo("official");
        assertThat(h.get("rows")).hasSize(2);
        assertThat(h.get("rows").get(1).get("var").asDouble()).isEqualTo(390);
        JsonNode record = getJson("/api/v1/var/official", "", rita);
        String deskCapture = record.at("/nodes/MGMT~DESK-X/captureId").asText();
        assertThat(var("CAPTURE:" + deskCapture)).isEqualTo(390);                          // an official capture's members, as a scope
        mvc.perform(get("/api/v1/var/official").header("Authorization", as("yan", "desk-y"))).andExpect(status().isForbidden());
        assertThat(published()).isEqualTo(18);

        // the batch is restated: every vector doubles; the data-load signal reloads the date and captures it again (result version 2)
        VarLakeFixture.write(lake, VarLakeFixture.DAY, 2.0, false, false);
        Thread.sleep(1500);
        official.onLoad("pnl-vector", VarLakeFixture.DAY);
        for (int i = 0; i < 100 && getJson("/api/v1/var/official", "", rita).get("resultVersion").asInt() < 2; i++) {
            Thread.sleep(100);
        }
        assertThat(getJson("/api/v1/var/official", "", rita).get("resultVersion").asInt()).isEqualTo(2);
        assertThat(published()).isEqualTo(27);                                              // appended, never overwritten
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
        JsonNode deskList = json.readTree(mvc.perform(get("/api/v1/var/captures").param("scope", "DESK:DESK-X").header("Authorization", rita))
                .andReturn().getResponse().getContentAsString());
        assertThat(deskList).hasSize(2);                                // the official ones: a date's latest result version each
        assertThat(deskList.get(0).get("note").asText()).contains("result version 2");
        String csv = mvc.perform(get("/api/v1/var/captures/" + id + "/csv").header("Authorization", rita)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("scenario_date,pnl").contains("# note: before");
    }

    @Test
    @Order(7)
    void positionsAreServedAsTheirKindAndTheirPagesShowTheEnginesFigures() throws Exception {
        JsonNode doc = json.readTree(mvc.perform(get("/api/v1/entities/position/" + VarLakeFixture.POS_A + "/raw")
                .header("Authorization", as("rita", "risk"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String text = doc.toString();
        assertThat(text).contains("\"TRADE_IDS\":[\"T1\",\"T2\"]").contains("\"POSITION_RULE\":\"securities\"").contains("\"QUANTITY\":60.0")
                .doesNotContain("VAR_99").doesNotContain("VAR_ENGINE");
        mvc.perform(get("/api/v1/views/position/" + VarLakeFixture.POS_A).header("Authorization", as("rita", "risk"))).andExpect(status().isOk());
        // the page's figures: computed as the viewer when the page is read (twice the hand-computed VaR now the batch is restated)
        com.ash.drishti.api.EntityDocument stored = new com.ash.drishti.api.EntityDocument(com.ash.drishti.api.EntityRef.of("position",
                VarLakeFixture.POS_A), com.ash.drishti.api.DataNode.of(java.util.Map.of("POSITION_ID", VarLakeFixture.POS_A)),
                new com.ash.drishti.api.Provenance("var-positions", 1, java.time.Instant.now(), false, VarLakeFixture.DAY));
        com.ash.drishti.api.EntityDocument[] seen = new com.ash.drishti.api.EntityDocument[1];
        com.ash.drishti.server.net.NetCaller.with(new com.ash.drishti.server.net.NetCaller.Caller(
                new com.ash.drishti.server.security.Principal("rita", List.of("risk")), null, "t"),
                () -> seen[0] = figures.augment(stored, com.ash.drishti.api.AsOf.of(VarLakeFixture.DAY)));
        JsonNode f = json.readTree(json.writeValueAsString(seen[0].data().unwrap())).get(VarFigures.FIELD);
        assertThat(f.get("VAR").asDouble()).isEqualTo(1200);
        assertThat(f.get("TRADES")).hasSize(2);
        assertThat(f.get("BOOK_VAR").asDouble()).isEqualTo(1180);
        assertThat(f.get("PNL")).hasSize(20);
        assertThat(figures.augment(stored, com.ash.drishti.api.AsOf.of(VarLakeFixture.DAY)).data().get(VarFigures.FIELD).get("STATUS").asText())
                .isEqualTo("unavailable");                                                  // outside a request: no viewer, no figures
        com.ash.drishti.server.net.NetCaller.with(new com.ash.drishti.server.net.NetCaller.Caller(
                new com.ash.drishti.server.security.Principal("rita", List.of("risk")), null, "t"),
                () -> seen[0] = figures.augment(new com.ash.drishti.api.EntityDocument(com.ash.drishti.api.EntityRef.of("portfolio", "PF-MIX"),
                        com.ash.drishti.api.DataNode.of(java.util.Map.of("PORTFOLIO_ID", "PF-MIX")), stored.provenance()), null));
        JsonNode pf = json.readTree(json.writeValueAsString(seen[0].data().unwrap())).get(VarFigures.FIELD);
        assertThat(pf.get("VAR").asDouble()).isEqualTo(1080);
        assertThat(pf.get("POSITIONS")).hasSize(3);
        assertThat(pf.get("BY_CURRENCY")).isNotEmpty();
        // a legal entity stores no VaR (2.9.2): its own and each of its books' are the engine's, beside the record's book rows
        com.ash.drishti.api.EntityDocument le = new com.ash.drishti.api.EntityDocument(com.ash.drishti.api.EntityRef.of("legal-entity", "LE-A"),
                com.ash.drishti.api.DataNode.of(java.util.Map.of("ENTITY_ID", "LE-A", "BOOKS", List.of(
                        java.util.Map.of("BOOK", "BOOK-1", "PNL", 7, "TRADES", 3), java.util.Map.of("BOOK", "BOOK-2", "PNL", -2, "TRADES", 4)))),
                stored.provenance());
        com.ash.drishti.server.net.NetCaller.with(new com.ash.drishti.server.net.NetCaller.Caller(
                new com.ash.drishti.server.security.Principal("rita", List.of("risk")), null, "t"),
                () -> seen[0] = figures.augment(le, com.ash.drishti.api.AsOf.of(VarLakeFixture.DAY)));
        JsonNode lf = json.readTree(json.writeValueAsString(seen[0].data().unwrap())).get(VarFigures.FIELD);
        assertThat(figures.augments("legal-entity")).isTrue();
        assertThat(lf.get("VAR").asDouble()).isEqualTo(var("LE:LE-A"));
        assertThat(lf.get("BOOKS")).hasSize(2);
        assertThat(lf.get("BOOKS").get(0).get("VAR").asDouble()).isEqualTo(var("BOOK:BOOK-1"));
        assertThat(lf.get("BOOKS").get(1).get("PNL").asInt()).isEqualTo(-2);
    }

    @Test
    @Order(8)
    void aDateThatIsNotOneRunIsAnsweredByTheDateBeforeAndADuplicateTradeIsRefused() throws Exception {
        java.time.LocalDate next = VarLakeFixture.DAY.plusDays(1);
        VarLakeFixture.write(lake, next, 1.0, true, false);
        Thread.sleep(1500);
        JsonNode bank = json.readTree(mvc.perform(get("/api/v1/var/scope").param("scope", "BANK").param("asOf", next.toString())
                .header("Authorization", as("rita", "risk"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(bank.get("businessDate").asText()).isEqualTo(VarLakeFixture.DAY.toString());
        assertThat(bank.at("/provenance/warnings").toString()).contains("not loaded").contains("2 runs").contains("answered from 2026-09-30");
        java.time.LocalDate after = next.plusDays(1);
        VarLakeFixture.write(lake, after, 1.0, false, true);
        Thread.sleep(1500);
        mvc.perform(get("/api/v1/var/scope").param("scope", "BANK").param("asOf", after.toString()).header("Authorization", as("rita", "risk")))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("DRS-5202"));
    }

    @Test
    @Order(9)
    void eachDateIsAMappedHotFileThatOpensAgainWithoutTheLake() throws Exception {
        String ada = as("ada", "admin");
        double before = onDay("DESK:DESK-X - TRD:T1").get("var").asDouble();
        JsonNode st = json.readTree(mvc.perform(get("/api/v1/var/status").header("Authorization", ada)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(st.at("/tier/mode").asText()).isEqualTo("mapped");
        assertThat(st.at("/tier/mapping").asText()).contains("unmapped on close");
        JsonNode day = null;
        for (JsonNode l : st.get("loaded")) {
            if (VarLakeFixture.DAY.toString().equals(l.path("businessDate").asText())) {
                day = l;
            }
        }
        assertThat(day).isNotNull();
        assertThat(day.get("tier").asText()).isEqualTo("mapped");
        String hot = day.get("hotFile").asText();
        assertThat(hot).startsWith(VarLakeFixture.DAY + "/inputs-").endsWith(".vhot");
        Path file = service.loader().tier().root().resolve(hot);
        assertThat(file).exists();
        assertThat(file.toString()).startsWith(Path.of(System.getProperty("drishti.data.dir", "data")).toAbsolutePath().normalize().toString());
        long reopened = st.at("/tier/reopened").asLong();
        // an administrator's reload drops the dates; the next request opens the same file again (the inputs did not change)
        mvc.perform(post("/api/v1/var/reload").header("Authorization", ada)).andExpect(status().isOk());
        assertThat(onDay("DESK:DESK-X - TRD:T1").get("var").asDouble()).isEqualTo(before);
        JsonNode after = json.readTree(mvc.perform(get("/api/v1/var/status").header("Authorization", ada)).andReturn().getResponse()
                .getContentAsString());
        assertThat(after.at("/tier/reopened").asLong()).isGreaterThan(reopened);
        JsonNode prov = onDay("DESK:DESK-X - TRD:T1").get("provenance");
        assertThat(prov.get("tier").asText()).contains("hot file");
        assertThat(onDay("DESK:DESK-X").at("/provenance/tier").asText()).isEqualTo("node aggregate (float64)");
        try (var files = Files.walk(service.loader().tier().root())) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("."))).as("no hidden files").isEmpty();
        }
    }

    @Test
    @Order(10)
    void varResultIsAnOrdinaryComputedKindReadAsTheViewer() throws Exception {
        connectors.write("var-engine", """
                plugin: reactive
                mode: reactive
                kinds:
                  var-result:
                    compute: var-result
                    result: { required: [VAR, ES, PNL], types: { VAR: number, ES: number } }
                engine: { type: java, class: com.ash.drishti.server.var.VarResultEngine, version: "1.0" }
                triggers:
                  - { on: data-version, kinds: [pnl-vector], affects: all }
                limits: { wait: 5s }
                """);
        manager.scan();
        try {
            String body = "{\"id\": \"DESK:DESK-X - TRD:T1\", \"asOf\": \"" + VarLakeFixture.DAY + "\", \"wait\": 10000}";
            JsonNode a = json.readTree(mvc.perform(post("/api/v1/compute/var-engine/var-result").header("Authorization", as("rita", "risk"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(a.get("state").asText()).as(a.toString()).isEqualTo("ready");
            JsonNode r = a.get("result");
            JsonNode head = onDay("DESK:DESK-X - TRD:T1");
            assertThat(r.get("VAR").asDouble()).isEqualTo(head.get("var").asDouble());       // the explorer's figure, to the unit
            assertThat(r.get("ES").asDouble()).isEqualTo(head.get("es").asDouble());
            assertThat(r.get("SCOPE_KIND").asText()).isEqualTo("adhoc");
            assertThat(r.get("PNL")).hasSize(20);
            assertThat(r.get("CHILDREN")).isNotEmpty();
            assertThat(r.get("WORST")).isNotEmpty();
            assertThat(r.at("/BACKTEST/LIGHT").isMissingNode()).isFalse();
            assertThat(r.at("/PROVENANCE/MEMBERSHIP_HASH").asText()).isNotEmpty();
            // the same kind through a view, as a person who may see one desk only: computed over what they see, never more
            JsonNode y = json.readTree(mvc.perform(post("/api/v1/compute/var-engine/var-result").header("Authorization", as("yan", "desk-y"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"id\": \"BANK\", \"asOf\": \"" + VarLakeFixture.DAY + "\", \"wait\": 10000}"))
                    .andReturn().getResponse().getContentAsString());
            assertThat(y.at("/result/PROVENANCE/HIDDEN").asInt()).isPositive();
            // a role that may not open the kind is refused in words; a scope that does not parse is the engine's refusal
            var c = mvc.perform(post("/api/v1/compute/var-engine/var-result").header("Authorization", as("cy", "curves"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
            assertThat(c.getStatus() + " " + c.getContentAsString()).contains("var-result").doesNotContain("\"VAR\"");
            JsonNode bad = json.readTree(mvc.perform(post("/api/v1/compute/var-engine/var-result").header("Authorization", as("rita", "risk"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"id\": \"DESK:(\", \"wait\": 10000}")).andReturn().getResponse()
                    .getContentAsString());
            assertThat(bad.get("state").asText()).isEqualTo("failed");
            assertThat(bad.get("message").asText()).isNotBlank();
        } finally {
            connectors.delete("var-engine");
            manager.scan();
        }
    }

    private JsonNode onDay(String scope) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/var/scope").param("scope", scope).param("asOf", VarLakeFixture.DAY.toString())
                .header("Authorization", as("rita", "risk"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** The rows of the published table: the records its commits added (each commit one date). */
    private long published() throws Exception {
        Path log = lake.resolve("risk-results").resolve("var-official").resolve("_delta_log");
        long rows = 0;
        boolean appendOnly = false;
        try (var files = Files.list(log)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".json")).toList()) {
                for (String line : Files.readAllLines(f)) {
                    JsonNode a = json.readTree(line);
                    if (a.has("add")) {
                        rows += json.readTree(a.at("/add/stats").asText()).path("numRecords").asLong();
                    }
                    appendOnly |= "true".equals(a.at("/metaData/configuration/delta.appendOnly").asText());
                }
            }
        }
        assertThat(appendOnly).isTrue();
        return rows;
    }
}
