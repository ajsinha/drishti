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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.server.PinnedBusinessDate;
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
 * The MR cube switched off ({@code drishti.engines.mr-cube.enabled: false}, ADMIN_GUIDE.md): its API ({@code /api/v1/pivot/mr}) answers
 * {@code 404 DRS-1020} from the engines' gate; the pivot engine's VaR source stays, with VaR, ES, PV and trades by the positions' columns
 * only: no hierarchy level, no MR measure, no option, each refused in words; the same cells' VaR as with the cube on.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=var-limit", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.var-limit=risk-lake",
        "drishti.var.hierarchies.MGMT.title=Management", "drishti.var.hierarchies.MGMT.levels=DESK > TT_BOOK",
        "drishti.var.hierarchies.MGMT.level-names=desk > book", "drishti.var.hierarchies.MGMT.ids=leaf",
        "drishti.var.hierarchies.MGMT.limits=var-limit", "drishti.var.hierarchies.LEGAL.title=Legal entity",
        "drishti.var.hierarchies.LEGAL.levels=LEGAL_ENTITY", "drishti.var.hierarchies.LEGAL.ids=leaf",
        "drishti.var.hierarchies.LEGAL.limits=var-limit",
        "drishti.sources.routes.portfolio=risk-lake",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true", "drishti.engines.mr-cube.enabled=false",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class MrCubeOffTest {

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        Path lake = Files.createDirectories(Path.of("target", "mr-cube-off-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode answer(MvcResult r, int expect) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expect);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private MvcResult query(String auth, Map<String, Object> view) throws Exception {
        return mvc.perform(post("/api/v1/pivot/query").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("view", view)))).andReturn();
    }

    @Test
    void theMrLensIsGoneAndTheVarSourceKeepsItsBasicMeasures() throws Exception {
        String auth = "Bearer " + tokens.mint("rita", List.of("risk"), 300);
        JsonNode off = answer(mvc.perform(get("/api/v1/risk/mr/versions").header("Authorization", auth)).andReturn(), 404);
        assertThat(off.get("code").asText()).isEqualTo("DRS-1020");
        answer(mvc.perform(post("/api/v1/risk/mr/headline").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"view\":{\"source\":\"var\",\"values\":[\"var()\"]}}")).andReturn(), 404);
        JsonNode cat = answer(mvc.perform(get("/api/v1/pivot/catalogue").param("source", "var").header("Authorization", auth)).andReturn(), 200);
        assertThat(cat.path("fields").findValuesAsText("name")).contains("DESK").doesNotContain("MGMT.DESK", "RISK_CLASS", "TRADE");
        assertThat(cat.path("measures").findValuesAsText("name")).containsExactly("var", "es", "pv", "trades", "pvar");   // pvar(): parametric VaR, on as shipped
        JsonNode ok = answer(query(auth, Map.of("source", "var", "rows", List.of("DESK"), "values", List.of(Map.of("measure", "var()", "as", "VaR")))), 200);
        assertThat(ok.get("cellCount").asInt()).isGreaterThanOrEqualTo(3);
        JsonNode comp = answer(query(auth, Map.of("source", "var", "rows", List.of("DESK"), "values", List.of(Map.of("measure", "comp_var()")))), 400);
        assertThat(comp.get("detail").asText()).contains("mr-cube");
        JsonNode opt = answer(query(auth, Map.of("source", "var", "rows", List.of("DESK"), "values", List.of(Map.of("measure", "var()")),
                "options", Map.of("horizon", "10d"))), 400);
        assertThat(opt.get("detail").asText()).contains("mr-cube");
        answer(query(auth, Map.of("source", "var", "rows", List.of("MGMT.DESK"), "values", List.of(Map.of("measure", "var()")))), 400);
    }
}
