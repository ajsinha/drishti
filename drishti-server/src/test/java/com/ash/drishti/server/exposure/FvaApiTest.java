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
package com.ash.drishti.server.exposure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * FVA over the exposure engine's tiny lake whose profile is known by hand (ExposureLakeFixture: NS-X's EE 1.0m, 1.5m, 0.75m and ENE
 * −0.25m, −0.5m, −0.75m at t = 0.2, 0.4, 1.0; NS-Y's EE 0.25m, 0.5m, 0.25m and no ENE), a flat 50 bp spread and no discount curve routed
 * (DF = 1 at a 0 % flat rate, flagged FLAT_DISCOUNT): NS-X FCA −0.005 × 0.95m = −4,750, FBA 0.005 × 0.6m = 3,000, FVA −1,750; NS-Y FCA
 * −0.005 × 0.3m = −1,500; CP-1 the sum, −3,250. The run is REAL_WORLD: computed and flagged (require-risk-neutral false). The one answer
 * carries each set's figures, the counterparties' sums and the terms; the playground and its workbook answer; a netting set the caller
 * sees only in aggregate is summed without its sets; the choices change the figures as the formulas say.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.ccr-lake.plugin=delta",
        "drishti.sources.connectors.ccr-lake.kinds[0]=simulation-run", "drishti.sources.connectors.ccr-lake.kinds[1]=exposure-path",
        "drishti.sources.connectors.ccr-lake.kinds[2]=netting-set", "drishti.sources.connectors.ccr-lake.kinds[3]=csa",
        "drishti.sources.connectors.ccr-lake.kinds[4]=collateral-balance", "drishti.sources.connectors.ccr-lake.kinds[5]=counterparty",
        "drishti.sources.connectors.ccr-lake.kinds[6]=counterparty-group",
        "drishti.sources.connectors.ccr-lake.settings.domain=risk",
        "drishti.sources.connectors.ccr-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.ccr-lake.settings.layout.exposure-path.columns=TRADE_ID,NETTING_SET,COUNTERPARTY,LEGAL_ENTITY,DESK,TT_BOOK,"
                + "ASSET_CLASS,PRODUCT,SOURCE_SYSTEM,MATURITY_DATE,NOTIONAL,GRID_INDEX,GRID_DATE,RUN_ID,BATCH_ID,STATUS",
        "drishti.sources.connectors.ccr-lake.settings.layout.exposure-path.vectors=MTM",
        "drishti.sources.routes.simulation-run=ccr-lake", "drishti.sources.routes.exposure-path=ccr-lake",
        "drishti.sources.routes.netting-set=ccr-lake", "drishti.sources.routes.csa=ccr-lake", "drishti.sources.routes.collateral-balance=ccr-lake",
        "drishti.sources.routes.counterparty=ccr-lake", "drishti.sources.routes.counterparty-group=ccr-lake",
        "drishti.ccr.exposure.official.on-start=false", "drishti.ccr.exposure.official.enabled=false", "drishti.ccr.exposure.close-grace-seconds=0",
        "drishti.var.official.on-start=false",
        "drishti.engines.fva.enabled=true", "drishti.ccr.fva.funding-spread=0.005", "drishti.ccr.fva.discount-rate=0",
        "drishti.ccr.fva.require-risk-neutral=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.security.roles.agg.kinds[0]=*", "drishti.security.roles.agg.raw=true", "drishti.ccr.exposure.access.agg[CP-1]=aggregate",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class FvaApiTest {

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "fva-lake-" + System.nanoTime())).toAbsolutePath();
        ExposureLakeFixture.write(lake);
        r.add("drishti.sources.connectors.ccr-lake.settings.root", lake::toString);
        r.add("drishti.ccr.exposure.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private String as(String role) {
        return "Bearer " + tokens.mint("rita", List.of(role), 300);
    }

    private JsonNode get(String url, String role, String... params) throws Exception {
        var req = MockMvcRequestBuilders.get(url).header("Authorization", as(role)).param("asOf", ExposureLakeFixture.DAY.toString());
        for (int i = 0; i < params.length; i += 2) {
            req.param(params[i], params[i + 1]);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private byte[] download(org.springframework.test.web.servlet.RequestBuilder req) throws Exception {
        var started = mvc.perform(req).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        return mvc.perform(MockMvcRequestBuilders.asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private static double num(JsonNode n, String path) {
        return n.at(path).asDouble();
    }

    @Test
    void aNettingSetByHand() throws Exception {
        JsonNode x = get("/api/v1/fva/scope", "risk", "scope", "NSET:NS-X");
        assertThat(num(x, "/figures/fca")).isCloseTo(-4_750.00, org.assertj.core.data.Offset.offset(0.005));
        assertThat(num(x, "/figures/fba")).isCloseTo(3_000.00, org.assertj.core.data.Offset.offset(0.005));
        assertThat(num(x, "/figures/fva")).isCloseTo(-1_750.00, org.assertj.core.data.Offset.offset(0.005));
        assertThat(num(x, "/figures/epeLife")).isCloseTo(950_000, org.assertj.core.data.Offset.offset(0.01));     // the life is 1 year: EPE_life = EPE
        assertThat(num(x, "/epeReading/product")).isCloseTo(4_750.00, org.assertj.core.data.Offset.offset(0.005));
        assertThat(x.get("flags").toString()).contains("REAL_WORLD_RUN").contains("FLAT_DISCOUNT");
        assertThat(x.get("terms")).hasSize(3);
        assertThat(num(x, "/terms/2/dt")).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(num(x, "/terms/2/fca")).isCloseTo(-0.005 * 0.75e6 * 0.6, org.assertj.core.data.Offset.offset(0.005));
        assertThat(x.at("/cva/status").asText()).isEqualTo("planned");
        assertThat(x.get("playground").asText()).isEqualTo("PLAY FVA NSET:NS-X");
    }

    @Test
    void aCounterpartyIsTheSumOfItsSets() throws Exception {
        JsonNode cp = get("/api/v1/fva/scope", "risk", "scope", "CPTY:CP-1");
        assertThat(cp.get("nettingSets")).hasSize(2);
        assertThat(cp.at("/nettingSets/0/nettingSet").asText()).isEqualTo("NS-X");                                 // sorted by FVA, the costliest first
        assertThat(num(cp, "/nettingSets/1/fca")).isCloseTo(-1_500.00, org.assertj.core.data.Offset.offset(0.005));
        assertThat(num(cp, "/figures/fva")).isCloseTo(-3_250.00, org.assertj.core.data.Offset.offset(0.005));
        assertThat(num(cp, "/counterparties/0/fva")).isCloseTo(-3_250.00, org.assertj.core.data.Offset.offset(0.005));
        assertThat(cp.at("/counterparties/0/nettingSets").asInt()).isEqualTo(2);
    }

    @Test
    void theChoicesChangeTheFiguresAsTheFormulasSay() throws Exception {
        JsonNode only = get("/api/v1/fva/scope", "risk", "scope", "NSET:NS-X", "fba", "off");
        assertThat(num(only, "/figures/fba")).isZero();
        assertThat(num(only, "/figures/fva")).isCloseTo(-4_750.00, org.assertj.core.data.Offset.offset(0.005));
        JsonNode wider = get("/api/v1/fva/scope", "risk", "scope", "NSET:NS-X", "spread", "0.006");
        assertThat(num(wider, "/figures/fva")).isCloseTo(-2_100.00, org.assertj.core.data.Offset.offset(0.005));     // 60 bp: −5,700 + 3,600
        JsonNode mid = get("/api/v1/fva/scope", "risk", "scope", "NSET:NS-X", "eePoint", "mid");
        assertThat(mid.at("/settings/eePoint").asText()).isEqualTo("mid");
    }

    @Test
    void anAggregateViewerGetsTheSumWithoutTheSets() throws Exception {
        JsonNode agg = get("/api/v1/fva/scope", "agg", "scope", "CPTY:CP-1");
        assertThat(num(agg, "/figures/fva")).isCloseTo(-3_250.00, org.assertj.core.data.Offset.offset(0.005));
        assertThat(agg.get("nettingSets")).isEmpty();
        assertThat(agg.get("flags").toString()).contains("SETS_AGGREGATE");
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/fva/playground").header("Authorization", as("agg")).param("scope", "CPTY:CP-1")
                .param("asOf", ExposureLakeFixture.DAY.toString())).andExpect(status().isForbidden());
    }

    @Test
    void thePlaygroundAndItsWorkbook() throws Exception {
        JsonNode pg = get("/api/v1/fva/playground", "risk", "scope", "CPTY:CP-1");
        assertThat(pg.at("/choices/eePoint/value").asText()).isEqualTo("end");
        assertThat(pg.get("explainSteps").size()).isGreaterThan(10);
        byte[] xlsx = download(MockMvcRequestBuilders.get("/api/v1/fva/playground.xlsx").header("Authorization", as("risk")).param("scope", "NSET:NS-X")
                .param("asOf", ExposureLakeFixture.DAY.toString()));
        assertThat(new String(xlsx, 0, 2, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        assertThat(com.ash.drishti.server.dist.XlsxFormulas.scan(xlsx)).isEmpty();
        byte[] ex = download(MockMvcRequestBuilders.get("/api/v1/fva/playground/template").header("Authorization", as("risk")));
        assertThat(ex.length).isGreaterThan(1000);
        JsonNode st = get("/api/v1/fva/status", "risk");
        assertThat(st.get("requireRiskNeutral").asBoolean()).isFalse();
    }
}
