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
 * VaR from scenario PVs, in any reporting currency (VAR_GUIDE.md sections 15.9 and 15.10), over {@link VarLakeFixture}'s P&amp;L vectors
 * and {@link VarPvFixture}'s scenario PVs on the same date: both forms load side by side; a EUR trade's USD P&amp;L comes from its PVs
 * and the supplied scenario FX, a CAD trade's from FX derived from the history; a failed scenario, a currency without FX and a
 * valuation date of another day reject their trades with their codes; every answer takes {@code ccy} and says its currency; the same
 * trade has a different VaR per currency (the currency move is part of the risk); captures, compare, hints, the tree, the history,
 * the computed kind {@code var-result} and the official record take it too; a currency without FX is refused in words.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.kinds[4]=scenario-pv", "drishti.sources.connectors.risk-lake.kinds[5]=fx-scenario",
        "drishti.sources.connectors.risk-lake.kinds[6]=fx-spot",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.risk-lake.settings.layout.scenario-pv.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,BASE_PV,VALUATION_DATE,BASE_VALUATION_DATE,TRADE_STATUS,"
                + "MATURITY_DATE,STATUS,SCENARIO_SET_ID,BATCH_ID",
        "drishti.sources.connectors.risk-lake.settings.layout.scenario-pv.vectors=SCENARIO_PV",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.scenario-pv=risk-lake",
        "drishti.sources.routes.fx-scenario=risk-lake", "drishti.sources.routes.fx-spot=risk-lake",
        "drishti.sources.routes.var-result=var-engine",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.official.on-start=false", "drishti.var.official.currencies[0]=EUR",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VarCurrencyApiTest {

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-pv-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake, VarLakeFixture.DAY.minusDays(1), 1.0, false, false);
        VarPvFixture.write(lake, VarLakeFixture.DAY.minusDays(1));
        VarLakeFixture.write(lake);
        VarPvFixture.write(lake, VarLakeFixture.DAY);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarOfficial official;
    @Autowired com.ash.drishti.packs.ConnectorFiles connectors;
    @Autowired com.ash.drishti.server.connectors.ConnectorManager manager;
    final ObjectMapper json = new ObjectMapper();

    private String rita() {
        return "Bearer " + tokens.mint("rita", List.of("risk"), 300);
    }

    private JsonNode ask(String url, String scope, String ccy) throws Exception {
        var req = get(url).param("scope", scope).param("asOf", VarLakeFixture.DAY.toString()).header("Authorization", rita());
        if (ccy != null) {
            req = req.param("ccy", ccy);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    @Order(1)
    void scenarioPvsLoadBesideVectorsAndConvertToUsdAtLoad() throws Exception {
        JsonNode bank = ask("/api/v1/var/scope", "BANK", null);
        assertThat(bank.get("currency").asText()).isEqualTo("USD");
        // T1-T5 and T9 from the vectors, P1 and P2 from the scenario PVs; P3, P4 and P5 rejected with their codes
        assertThat(bank.at("/provenance/trades").asInt()).isEqualTo(8);
        assertThat(bank.at("/provenance/scenarioPvTrades").asInt()).isEqualTo(2);
        assertThat(bank.at("/provenance/rejected").asInt()).isEqualTo(5);
        String why = bank.at("/provenance/rejections").toString();
        assertThat(why).contains("DRS-5206 P3: 1 scenario failed to price: 4 (" + VarLakeFixture.scenarioDates().get(4) + ")")
                .contains("DRS-5207 P4: its PVs are in CHF").contains("DRS-5201 P5: the scenario PVs are valued on 2026-09-29");
        assertThat(bank.at("/provenance/fx/rates/EUR/source").asText()).isEqualTo("supplied");
        assertThat(bank.at("/provenance/fx/rates/CAD/source").asText()).isEqualTo("derived");
        assertThat(bank.at("/provenance/fx/crosses").asText()).isEqualTo("through USD");
        // P1: (1000 + 10 U(s)) x FX(s) - 1200, worst on scenario 7 where the euro falls to 1.00: -300
        assertThat(ask("/api/v1/var/scope", "TRD:P1", null).get("var").asDouble()).isEqualTo(300);
        // P2: CAD rates derived from the history: 1990 x 0.64 - 1600 = -326.4 on scenario 6
        JsonNode p2 = ask("/api/v1/var/scope/path?size=3", "TRD:P2", null);
        assertThat(p2.get("var").asDouble()).isEqualTo(326);
        assertThat(p2.at("/worst/0/index").asInt()).isEqualTo(6);
        assertThat(p2.at("/worst/0/pnl").asDouble()).isEqualTo(-326);
        // a scenario-PV trade's position carries its USD value today: 1,000 EUR at 1.20
        JsonNode parts = ask("/api/v1/var/scope/contributions?by=trade", "BOOK:BOOK-3", null);
        boolean seen = false;
        for (JsonNode r : parts.get("rows")) {
            if (r.get("key").asText().equals("P1")) {
                assertThat(r.get("pv").asDouble()).isCloseTo(1200, within(1e-6));
                seen = true;
            }
        }
        assertThat(seen).isTrue();
    }

    @Test
    @Order(2)
    void everyAnswerTakesAReportingCurrencyAndTheCurrencyMoveIsPartOfTheRisk() throws Exception {
        JsonNode eur = ask("/api/v1/var/scope", "TRD:P1", "eur");
        assertThat(eur.get("currency").asText()).isEqualTo("EUR");
        assertThat(eur.get("var").asDouble()).isEqualTo(100);                   // its own P&L in EUR: 10 U(s), worst -100
        assertThat(eur.at("/provenance/currency").asText()).isEqualTo("EUR");
        assertThat(eur.at("/provenance/fx/rates/EUR/reporting").asBoolean()).isTrue();
        // a USD trade in EUR: (1000 + 100 U(s)) / FX(s) - 1000 / 1.20; worst on scenario 7: 0 / 1.00 - 833.33
        assertThat(ask("/api/v1/var/scope", "TRD:T1", "EUR").get("var").asDouble()).isEqualTo(833);
        // a currency whose rate never moves only rescales: T1's USD VaR 1,000 is 800 GBP
        assertThat(ask("/api/v1/var/scope", "TRD:T1", "GBP").get("var").asDouble()).isEqualTo(800);
        // CAD is derived from the history, so it is a reporting currency too: P2 in CAD is its own P&L, 5 V(s), worst -45
        assertThat(ask("/api/v1/var/scope", "TRD:P2", "CAD").get("var").asDouble()).isEqualTo(45);
        // the node limits convert at today's rate: DESK-Y's 600 USD is 500 EUR
        JsonNode desk = ask("/api/v1/var/scope", "DESK:DESK-Y", "EUR");
        assertThat(desk.get("limit").asDouble()).isEqualTo(500);
        for (String url : List.of("/api/v1/var/scope/path", "/api/v1/var/scope/contributions", "/api/v1/var/scope/history")) {
            assertThat(ask(url, "DESK:DESK-Y", "EUR").get("currency").asText()).as(url).isEqualTo("EUR");
        }
        JsonNode tree = json.readTree(mvc.perform(get("/api/v1/var/tree").param("ccy", "EUR").param("asOf", VarLakeFixture.DAY.toString())
                .header("Authorization", rita())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(tree.get("currency").asText()).isEqualTo("EUR");
        JsonNode hints = json.readTree(mvc.perform(get("/api/v1/var/hints").param("q", "TRD:P1").param("ccy", "EUR")
                .param("asOf", VarLakeFixture.DAY.toString()).header("Authorization", rita())).andReturn().getResponse().getContentAsString());
        assertThat(hints.get(0).get("currency").asText()).isEqualTo("EUR");
        // the same trades, the same members, in every currency
        assertThat(ask("/api/v1/var/scope", "BANK", "EUR").at("/provenance/trades").asInt()).isEqualTo(8);
        // a currency without FX on the date is refused in words; a code that is not one is a bad request
        mvc.perform(get("/api/v1/var/scope").param("scope", "BANK").param("ccy", "CHF").header("Authorization", rita()))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("DRS-5207"));
        mvc.perform(get("/api/v1/var/scope").param("scope", "BANK").param("ccy", "EURO").header("Authorization", rita()))
                .andExpect(status().isBadRequest());
        JsonNode status = json.readTree(mvc.perform(get("/api/v1/var/status").header("Authorization", rita())).andReturn().getResponse()
                .getContentAsString());
        assertThat(status.get("loaded").toString()).contains("\"currency\":\"EUR\"");
    }

    @Test
    @Order(3)
    void capturesAndCompareKeepTheirCurrency() throws Exception {
        String body = "{\"scope\": \"TRD:P1\", \"note\": \"in euros\", \"ccy\": \"EUR\"}";
        JsonNode cap = json.readTree(mvc.perform(post("/api/v1/var/captures").param("asOf", VarLakeFixture.DAY.toString())
                .header("Authorization", rita()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(cap.get("currency").asText()).isEqualTo("EUR");
        assertThat(cap.get("var").asDouble()).isEqualTo(100);
        String id = cap.get("id").asText();
        JsonNode cmp = json.readTree(mvc.perform(get("/api/v1/var/compare").param("a", id).header("Authorization", rita()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cmp.get("currency").asText()).isEqualTo("EUR");
        assertThat(cmp.get("varChange").asDouble()).isEqualTo(0);                // now, in the capture's currency: unchanged
        JsonNode usd = json.readTree(mvc.perform(post("/api/v1/var/captures").param("asOf", VarLakeFixture.DAY.toString())
                .header("Authorization", rita()).contentType(MediaType.APPLICATION_JSON).content("{\"scope\": \"TRD:P1\"}"))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/v1/var/compare").param("a", id).param("b", usd.get("id").asText()).header("Authorization", rita()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(4)
    void theOfficialRecordIsCapturedInEachConfiguredCurrency() throws Exception {
        official.capture(VarLakeFixture.DAY.minusDays(1));
        java.util.Map<String, Object> r = official.capture(VarLakeFixture.DAY);
        assertThat(r.get("status")).isEqualTo("captured");
        assertThat(r.get("currencies").toString()).contains("EUR={businessDate=2026-09-30, currency=EUR, status=captured");
        JsonNode eur = json.readTree(mvc.perform(get("/api/v1/var/official").param("ccy", "EUR").param("asOf", VarLakeFixture.DAY.toString())
                .header("Authorization", rita())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(eur.get("currency").asText()).isEqualTo("EUR");
        assertThat(eur.at("/nodes/MGMT~DESK-Y/var").asDouble()).isEqualTo(ask("/api/v1/var/scope", "NODE:MGMT~DESK-Y", "EUR").get("var").asDouble());
        JsonNode usd = json.readTree(mvc.perform(get("/api/v1/var/official").param("asOf", VarLakeFixture.DAY.toString())
                .header("Authorization", rita())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(usd.get("currency").asText()).isEqualTo("USD");
        assertThat(usd.at("/nodes/MGMT~DESK-Y/var").asDouble()).isNotEqualTo(eur.at("/nodes/MGMT~DESK-Y/var").asDouble());
        mvc.perform(get("/api/v1/var/official").param("ccy", "GBP").param("asOf", VarLakeFixture.DAY.toString()).header("Authorization", rita()))
                .andExpect(status().isNotFound());
        // a node's history in EUR reads the EUR official records
        assertThat(ask("/api/v1/var/scope/history", "NODE:MGMT~DESK-Y", "EUR").get("mode").asText()).isEqualTo("official");
    }

    @Test
    @Order(5)
    void varResultTakesTheCurrencyAsAParameter() throws Exception {
        connectors.write("var-engine", """
                plugin: reactive
                mode: reactive
                kinds:
                  var-result:
                    compute: var-result
                    params: { ccy: { type: text, default: USD } }
                    result: { required: [VAR, ES, PNL], types: { VAR: number, ES: number } }
                engine: { type: java, class: com.ash.drishti.server.var.VarResultEngine, version: "1.0" }
                limits: { wait: 5s }
                """);
        manager.scan();
        try {
            String body = "{\"id\": \"TRD:P1\", \"params\": {\"ccy\": \"EUR\"}, \"asOf\": \"" + VarLakeFixture.DAY + "\", \"wait\": 10000}";
            JsonNode a = json.readTree(mvc.perform(post("/api/v1/compute/var-engine/var-result").header("Authorization", rita())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(a.get("state").asText()).as(a.toString()).isEqualTo("ready");
            assertThat(a.at("/result/CURRENCY").asText()).isEqualTo("EUR");
            assertThat(a.at("/result/VAR").asDouble()).isEqualTo(100);
            JsonNode u = json.readTree(mvc.perform(post("/api/v1/compute/var-engine/var-result").header("Authorization", rita())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"id\": \"TRD:P1\", \"asOf\": \"" + VarLakeFixture.DAY + "\", \"wait\": 10000}"))
                    .andReturn().getResponse().getContentAsString());
            assertThat(u.at("/result/CURRENCY").asText()).isEqualTo("USD");
            assertThat(u.at("/result/VAR").asDouble()).isEqualTo(300);
        } finally {
            connectors.delete("var-engine");
            manager.scan();
        }
    }
}
