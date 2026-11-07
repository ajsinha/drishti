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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The VaR vector provider through the pivot engine's SPI (ARCHITECTURE.md sections 23.6.3 and 23.20, ported from the paused MR cube's
 * {@code VarCubeApiTest}), on the tiny lake of {@link VarLakeFixture}: every cell, every row and column total and the grand total of a pivot of
 * {@code var()} is the VaR engine's own answer for the cell's scope, to the cent ({@code /api/v1/var/scope} and the engine's unrounded
 * figure); the totals are the union's VaR, never sums (the desks' VaRs add up to more than the bank's 540, the gap shown as diversification);
 * PV and the trade count add up.
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
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.official.on-start=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarPivotApiTest {

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-pivot-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private double scopeVar(String auth, String scope) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/var/scope").param("scope", scope).header("Authorization", auth)).andReturn();
        assertThat(r.getResponse().getStatus()).as(scope + ": " + r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString()).get("var").asDouble();
    }

    @Test
    void everyCellAndTotalIsTheEnginesVarOfItsScopeToTheCentAndTotalsAreNeverSums() throws Exception {
        String rita = as("rita", "risk");
        Map<String, Object> view = Map.of("source", "var", "rows", List.of("DESK"), "columns", List.of("CURRENCY"), "values",
                List.of(Map.of("measure", "var()", "as", "VaR"), Map.of("measure", "es()", "as", "ES"), Map.of("measure", "pv()", "as", "PV"),
                        Map.of("measure", "trades()", "as", "Trades")));
        MvcResult r = mvc.perform(post("/api/v1/pivot/query").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(view))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode a = json.readTree(r.getResponse().getContentAsString());
        JsonNode cells = a.get("cells");
        assertThat(cells.size()).isGreaterThanOrEqualTo(6);
        int checked = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = cells.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            String scope = e.getValue().get("scope").asText();
            double cell = e.getValue().get("values").get("VaR").asDouble();
            double engine = svc.scoped(new Principal("rita", List.of("risk")), scope, null).measures().var();
            assertThat(cell).as(e.getKey() + " " + scope).isCloseTo(engine, Offset.offset(0.005));
            assertThat(Math.round(cell * 100)).as(scope + " against /var/scope").isEqualTo(Math.round(scopeVar(rita, scope) * 100));
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(6);
        JsonNode total = cells.get("*|*");
        assertThat(total.get("scope").asText()).isEqualTo("BANK");
        assertThat(total.get("values").get("VaR").asDouble()).isEqualTo(540.0);            // VarLakeFixture: the bank's VaR is 540 by hand
        double desks = 0;
        double pv = 0;
        double trades = 0;
        for (JsonNode row : a.get("rows")) {
            if ("node".equals(row.get("kind").asText())) {
                JsonNode c = cells.get(row.get("key").asText() + "|*");
                desks += c.get("values").get("VaR").asDouble();
                pv += c.get("values").get("PV").asDouble();
                trades += c.get("values").get("Trades").asDouble();
            }
        }
        assertThat(desks).as("the desks' VaRs sum to more than the bank's: the total is the union's VaR, never a sum").isGreaterThan(540.0);
        JsonNode grand = a.get("rows").get(a.get("rows").size() - 1);
        assertThat(grand.get("kind").asText()).isEqualTo("grand");
        assertThat(grand.at("/diversification/VaR").asDouble()).isCloseTo(desks - 540.0, Offset.offset(0.01));
        assertThat(pv).isCloseTo(total.get("values").get("PV").asDouble(), Offset.offset(0.01));
        assertThat(trades).isEqualTo(total.get("values").get("Trades").asDouble());
        assertThat(a.at("/measures/0/kind").asText()).isEqualTo("holistic");
    }
}
