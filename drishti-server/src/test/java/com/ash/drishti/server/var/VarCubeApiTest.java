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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.cube.CubeEngine;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * The MR cube on the tiny lake of {@link VarLakeFixture} (MR_CUBE_GUIDE.md): every cell's and total's VaR equals the VaR engine's own
 * answer for the cell's scope to the cent ({@code /api/v1/var/scope} and the engine's unrounded figure), the totals are the union's VaR and
 * never a sum (the desks' VaRs sum to more than the bank's), the components add up to the total, the drill opens the books, the cell
 * budget refuses a view too large with its count, the second ask comes from the cell cache, saved views open only for their owner or
 * those they are shared with, the exports, the provenance, and a role that sees a desk only in aggregate may pivot by hierarchy levels only.
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
        "drishti.security.roles.division-head.kinds[0]=*", "drishti.security.roles.division-head.raw=true",
        "drishti.var.access.division-head[MGMT~DESK-X]=aggregate", "drishti.var.access.division-head[MGMT~DESK-Y]=positions",
        "drishti.cube.max-cells=20", "drishti.cube.mr.risk-classes.IR[0]=Rates", "drishti.cube.mr.risk-classes.CR[0]=Fixed income",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarCubeApiTest {

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-cube-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    @Autowired CubeEngine engine;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode query(String auth, Map<String, Object> view, int expect) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/cube/mr/query").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(view))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expect);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private double scopeVar(String auth, String scope) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/var/scope").param("scope", scope).header("Authorization", auth)).andReturn();
        assertThat(r.getResponse().getStatus()).as(scope + ": " + r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString()).get("var").asDouble();
    }

    /** The engine's own unrounded VaR of a scope, as the person. */
    private double engineVar(String user, String role, String scope) {
        return svc.scoped(new Principal(user, List.of(role)), scope, null).measures().var();
    }

    private void sameAsTheEngine(String auth, JsonNode e) throws Exception {
        String scope = e.get("scope").asText();
        double cube = e.get("values").get("VAR").asDouble();
        assertThat(cube).as(scope).isCloseTo(engineVar("rita", "risk", scope), org.assertj.core.data.Offset.offset(0.005));
        assertThat(Math.round(cube)).as(scope + " against /var/scope").isEqualTo(Math.round(scopeVar(auth, scope)));
    }

    @Test
    void everyCellAndTotalIsTheEnginesVarOfItsScopeToTheCentAndTotalsAreNeverSums() throws Exception {
        String rita = as("rita", "risk");
        JsonNode a = query(rita, Map.of("scope", "BANK", "rows", List.of("DESK"), "columns", List.of("CURRENCY"),
                "measures", List.of("VAR", "ES", "COMP_VAR", "PV", "TRADES")), 200);
        assertThat(a.get("rows")).hasSize(2);
        assertThat(a.get("columns")).hasSize(2);
        assertThat(a.get("cells").size()).isBetween(3, 4);
        for (JsonNode c : a.get("cells")) {
            sameAsTheEngine(rita, c);
        }
        for (JsonNode r : a.get("rows")) {
            sameAsTheEngine(rita, r);
        }
        for (JsonNode c : a.get("columns")) {
            sameAsTheEngine(rita, c);
        }
        JsonNode total = a.get("total");
        assertThat(total.get("scope").asText()).isEqualTo("BANK");
        assertThat(total.get("values").get("VAR").asDouble()).isEqualTo(540.0);    // VarLakeFixture: the bank's VaR is 540 by hand
        double desks = 0;
        double comp = 0;
        double pv = 0;
        long trades = 0;
        for (JsonNode r : a.get("rows")) {
            desks += r.get("values").get("VAR").asDouble();
            comp += r.get("values").get("COMP_VAR").asDouble();
            pv += r.get("values").get("PV").asDouble();
            trades += r.get("values").get("TRADES").asLong();
        }
        assertThat(desks).as("the desks' VaRs sum to more than the bank's: the total is the union's VaR, not a sum").isGreaterThan(540.0);
        assertThat(a.at("/diversification/VAR/totalAcrossRows").asDouble()).isCloseTo(desks - 540.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(comp).as("component VaR adds up to the total").isCloseTo(540.0, org.assertj.core.data.Offset.offset(0.02));
        assertThat(pv).isEqualTo(total.get("values").get("PV").asDouble());
        assertThat(trades).isEqualTo(total.get("values").get("TRADES").asLong());
        assertThat(a.at("/headline/var").asDouble()).isEqualTo(540.0);
        // the DESK row of a whole desk carries its limit: DESK-X's 1,000
        JsonNode deskX = null;
        for (JsonNode r : a.get("rows")) {
            if (r.get("key").asText().equals("DESK-X")) {
                deskX = r;
            }
        }
        assertThat(deskX).isNotNull();
        assertThat(deskX.at("/values/limit").asDouble()).isEqualTo(1000.0);
        assertThat(deskX.at("/values/limitUse").asDouble()).isCloseTo(deskX.at("/values/VAR").asDouble() / 1000.0, org.assertj.core.data.Offset.offset(1e-6));
        // asked again: every set from the cell cache
        JsonNode again = query(rita, Map.of("scope", "BANK", "rows", List.of("DESK"), "columns", List.of("CURRENCY"),
                "measures", List.of("VAR")), 200);
        assertThat(again.get("fromCache").asInt()).isEqualTo(again.get("cellCount").asInt());
        // the provenance of a cell
        JsonNode prov = json.readTree(mvc.perform(get("/api/v1/cube/provenance/" + again.get("provenance").asText()).param("cell", "0|0")
                .header("Authorization", rita)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(prov.get("businessDate").asText()).isEqualTo(VarLakeFixture.DAY.toString());
        assertThat(prov.get("scenarios").asInt()).isEqualTo(VarLakeFixture.N);
        assertThat(prov.at("/cell/members").asInt()).isPositive();
    }

    @Test
    void hierarchyLevelsAndTheRiskClassArePivotsWhoseCellsAreScopesOfTheEngine() throws Exception {
        String rita = as("rita", "risk");
        JsonNode a = query(rita, Map.of("text", "MCUBE BANK BY MGMT.BOOK,RISK_CLASS MEASURE VAR,ES SORT desc"), 200);
        assertThat(a.get("rows")).hasSize(3);
        assertThat(a.at("/rows/0/values/VAR").asDouble()).isGreaterThanOrEqualTo(a.at("/rows/1/values/VAR").asDouble());
        for (JsonNode c : a.get("cells")) {
            assertThat(c.get("scope").asText()).contains("NODE:MGMT~BOOK").contains("WHERE:PRODUCT_GROUP in (");
            sameAsTheEngine(rita, c);
        }
        for (JsonNode c : a.get("columns")) {
            sameAsTheEngine(rita, c);
        }
    }

    @Test
    void aCellDrillsToItsBooksAndItsDetailHasTheTail() throws Exception {
        String rita = as("rita", "risk");
        Map<String, Object> view = Map.of("scope", "BANK", "rows", List.of("DESK"), "measures", List.of("VAR"));
        JsonNode cell = json.readTree(mvc.perform(post("/api/v1/cube/mr/cell").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("view", view, "row", "DESK-X")))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        assertThat(cell.at("/drill/dimension").asText()).isEqualTo("TT_BOOK");
        int members = 0;
        for (JsonNode r : cell.at("/drill/rows")) {
            members += r.get("members").asInt();
            sameAsTheEngine(rita, r);
        }
        assertThat(members).isEqualTo(cell.at("/cell/members").asInt());
        assertThat(cell.at("/detail/scenarios").asInt()).isEqualTo(VarLakeFixture.N);
        assertThat(cell.at("/detail/tail").size()).isPositive();
        assertThat(cell.at("/detail/counts").size()).isEqualTo(34);
        // the GET form of the spec, with the view as command text
        mvc.perform(get("/api/v1/cube/mr/cell").param("view", "MCUBE BANK BY DESK MEASURE VAR").param("row", "DESK-Y")
                .header("Authorization", rita)).andExpect(status().isOk());
    }

    @Test
    void aViewOverTheCellBudgetIsRefusedWithItsCountNeverCutShort() throws Exception {
        JsonNode r = query(as("rita", "risk"), Map.of("scope", "BANK", "rows", List.of("TRADE"), "columns", List.of("CURRENCY")), 422);
        assertThat(r.get("code").asText()).isEqualTo("DRS-5602");
        assertThat(r.get("detail").asText()).contains("more than the budget of 20");
        JsonNode bad = query(as("rita", "risk"), Map.of("scope", "BANK", "rows", List.of("NOPE")), 400);
        assertThat(bad.get("code").asText()).isEqualTo("DRS-5601");
        JsonNode measure = query(as("rita", "risk"), Map.of("scope", "BANK", "rows", List.of("DESK"), "measures", List.of("WHAT")), 400);
        assertThat(measure.get("detail").asText()).contains("WHAT is not a measure");
    }

    @Test
    void aRoleThatSeesADeskInAggregateMayPivotByHierarchyLevelsOnly() throws Exception {
        String head = as("dora", "division-head");
        JsonNode ok = query(head, Map.of("scope", "BANK", "rows", List.of("MGMT.DESK")), 200);
        assertThat(ok.get("rows")).hasSize(2);
        JsonNode refused = query(head, Map.of("scope", "BANK", "rows", List.of("CURRENCY")), 403);
        assertThat(refused.get("detail").asText()).contains("in aggregate only");
    }

    @Test
    void savedViewsAreTheOwnersOrSharedAndOpenAsTheReader() throws Exception {
        String rita = as("rita", "risk");
        Map<String, Object> body = Map.of("name", "morning", "view", Map.of("scope", "BANK", "rows", List.of("DESK"), "measures", List.of("VAR")),
                "sharedWith", List.of("role:division-head"));
        mvc.perform(post("/api/v1/cube/views").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isOk());
        JsonNode list = json.readTree(mvc.perform(get("/api/v1/cube/views").header("Authorization", rita)).andReturn().getResponse().getContentAsString());
        assertThat(list.findValuesAsText("id")).contains("rita/morning");
        mvc.perform(get("/api/v1/cube/views/rita/morning").header("Authorization", as("dora", "division-head"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/cube/views/rita/morning").header("Authorization", as("otto", "risk"))).andExpect(status().isNotFound());
        String csv = mvc.perform(get("/api/v1/cube/views/rita/morning/data").param("format", "csv").header("Authorization", rita))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("KIND,DESK,MEMBERS,SCOPE,VAR").contains("total,,");
        mvc.perform(delete("/api/v1/cube/views/morning").header("Authorization", rita)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/cube/views/rita/morning").header("Authorization", rita)).andExpect(status().isNotFound());
    }

    @Test
    void theViewExportsAsCsvArrowAndAWorkbook() throws Exception {
        String rita = as("rita", "risk");
        String view = json.writeValueAsString(Map.of("scope", "BANK", "rows", List.of("DESK"), "columns", List.of("CURRENCY"),
                "measures", List.of("VAR", "PV")));
        MvcResult arrow = mvc.perform(post("/api/v1/cube/mr/export").param("format", "arrow").header("Authorization", rita)
                .contentType(MediaType.APPLICATION_JSON).content(view)).andExpect(status().isOk()).andReturn();
        assertThat(arrow.getResponse().getContentType()).startsWith("application/vnd.apache.arrow.stream");
        assertThat(arrow.getResponse().getContentAsByteArray().length).isGreaterThan(200);
        byte[] xlsx = mvc.perform(post("/api/v1/cube/mr/export").param("format", "xlsx").header("Authorization", rita)
                .contentType(MediaType.APPLICATION_JSON).content(view)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(xlsx, 0, 2, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        String sheets = VarCubeApiTest.unzip(xlsx);
        assertThat(sheets).contains("SUM(B3:C3)").contains("re-aggregated").contains("Provenance");
    }

    /** The workbook's sheets' XML, joined. */
    static String unzip(byte[] xlsx) throws Exception {
        StringBuilder b = new StringBuilder();
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx))) {
            for (java.util.zip.ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (e.getName().startsWith("xl/worksheets/") || e.getName().equals("xl/workbook.xml")) {
                    b.append(new String(z.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        return b.toString();
    }

    @Test
    void theSliderStopsAndExplainOnACellScope() throws Exception {
        String rita = as("rita", "risk");
        query(rita, Map.of("scope", "BANK", "rows", List.of("DESK")), 200);           // opens the date
        JsonNode v = json.readTree(mvc.perform(get("/api/v1/cube/mr/versions").header("Authorization", rita)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(v.findValuesAsText("date")).contains(VarLakeFixture.DAY.toString());
        assertThat(engine.cubes()).containsExactly("mr");
    }
}
