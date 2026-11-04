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
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.dist.XlsxFormulas;
import com.ash.drishti.server.playground.LibreOfficeRecalc;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The Exposure Playground's API (docs/guides/EXPOSURE_GUIDE.md section 29) over the exposure test lake (ExposureLakeFixture: NS-X, trades
 * A and B on 4 paths, a CSA with thresholds of 1m; EPE 0.95m, EEPE 1.4m, PFE 75% 1m, 2m, 1m): the playground's figures equal the
 * explorer's, the contributions add up to EE, the paths at a date carry the collateral steps, the export's estimates, the workbook (its
 * formulas scanned, recalculated by LibreOffice when it is installed: ALL CHECKS PASS), case X1 and its template, and the refusals in
 * words: a counterparty scope, a set the run does not cover, a bad choice, a role that sees the set only in aggregate.
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
        "drishti.sources.routes.exposure-result=exposure-engine",
        "drishti.ccr.exposure.official.on-start=false", "drishti.ccr.exposure.close-grace-seconds=0", "drishti.var.official.on-start=false",
        "drishti.ccr.exposure.playground.export-choices=1,All", "drishti.ccr.exposure.playground.export-default=All",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.security.roles.agg.kinds[0]=*", "drishti.security.roles.agg.raw=true", "drishti.ccr.exposure.access.agg[CP-1]=aggregate",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class ExposurePlaygroundApiTest {

    static Path lake;
    static final double M = ExposureLakeFixture.M;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "exposure-playground-lake-" + System.nanoTime())).toAbsolutePath();
        ExposureLakeFixture.write(lake);
        r.add("drishti.sources.connectors.ccr-lake.settings.root", lake::toString);
        r.add("drishti.ccr.exposure.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path work;

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private MockHttpServletRequestBuilder req(String url, String auth, String... params) {
        MockHttpServletRequestBuilder r = get(url).header("Authorization", auth).param("asOf", ExposureLakeFixture.DAY.toString());
        for (int i = 0; i < params.length; i += 2) {
            r.param(params[i], params[i + 1]);
        }
        return r;
    }

    private JsonNode ok(String url, String... params) throws Exception {
        return json.readTree(mvc.perform(req(url, as("rita", "risk"), params)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void theFiguresEqualTheExplorersAndTheContributionsAddUp() throws Exception {
        JsonNode x = ok("/api/v1/exposure/playground", "nettingSet", "NS-X", "q", "0.75");
        JsonNode s = ok("/api/v1/exposure/scope", "scope", "NSET:NS-X", "q", "0.75");
        assertThat(x.get("nettingSet").asText()).isEqualTo("NS-X");
        assertThat(x.get("paths").asInt()).isEqualTo(4);
        assertThat(x.get("setTrades").asInt()).isEqualTo(2);
        JsonNode f = x.get("figures");
        assertThat(f.get("epe").asDouble()).isCloseTo(0.95 * M, within(0.01));
        assertThat(f.get("eepe").asDouble()).isCloseTo(1.4 * M, within(0.01));
        assertThat(f.get("epe").asDouble()).isCloseTo(s.get("epe").asDouble(), within(1.0));
        assertThat(f.get("peakPfe").get(0).get("peakPfe").asDouble()).isCloseTo(2 * M, within(0.01));
        JsonNode prof = x.get("profile");
        assertThat(prof).hasSize(3);
        assertThat(List.of(prof.get(0).get("ee").asDouble(), prof.get(1).get("ee").asDouble(), prof.get(2).get("ee").asDouble()))
                .containsExactly(1.0 * M, 1.5 * M, 0.75 * M);
        JsonNode c = x.get("contributions");
        assertThat(c.get("sum").asDouble()).isCloseTo(c.get("ee").asDouble(), within(0.01));
        assertThat(c.get("rows")).hasSize(2);
        JsonNode pt = x.get("pathTable");
        assertThat(pt.get("rows")).hasSize(4);
        assertThat(pt.get("rows").get(0).has("target")).isTrue();
        assertThat(x.get("formulas").get("pfe").asText()).contains("SMALL", "ROUNDUP");
        assertThat(x.get("explainSteps").get(0).get("title").asText()).startsWith("In plain words");
        assertThat(x.get("export").get("estimates")).hasSize(2);
        JsonNode est = ok("/api/v1/exposure/playground/estimate", "nettingSet", "NS-X");
        assertThat(est.get("setTrades").asInt()).isEqualTo(2);
        assertThat(est.get("estimates").get(0).get("refused").isNull()).isTrue();
        JsonNode two = ok("/api/v1/exposure/playground", "nettingSet", "NS-X", "paths", "2", "collateral", "none", "date", "1Y");
        assertThat(two.get("paths").asInt()).isEqualTo(2);
        assertThat(two.get("runFigures").size()).isPositive();                  // the run's own figures over its 4 paths, beside
        assertThat(two.get("choices").get("contributionDate").asText()).isEqualTo("1Y");
    }

    @Test
    void theWorkbookAndTheTemplate() throws Exception {
        byte[] xlsx = mvc.perform(req("/api/v1/exposure/playground.xlsx", as("rita", "risk"), "nettingSet", "NS-X", "trades", "1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(xlsx, 0, 2, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PK");
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        Map<String, String> parts = ExposurePlaygroundWorkbookTest.parts(xlsx);
        assertThat(parts.get("xl/worksheets/sheet3.xml")).contains(ExposurePlayground.REST);                // one trade shown, the other in Rest of set
        if (LibreOfficeRecalc.available()) {
            Path saved = LibreOfficeRecalc.recalc(LibreOfficeRecalc.write(work, "ns-x.xlsx", xlsx), work);
            assertThat(LibreOfficeRecalc.cells(saved, "Main").get("A3")).isEqualTo(ExposurePlaygroundBook.ALL_PASS);
        }
        byte[] t = mvc.perform(get("/api/v1/exposure/playground/template").header("Authorization", as("rita", "risk"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(ExposurePlaygroundWorkbookTest.parts(t).get("xl/worksheets/sheet1.xml")).contains("EXAMPLE-X1");
        JsonNode x1 = ok("/api/v1/exposure/playground", "nettingSet", "EXAMPLE");
        assertThat(x1.get("figures").get("epe").asDouble()).isCloseTo(1.2, within(1e-9));
    }

    @Test
    void refusalsInWords() throws Exception {
        mvc.perform(req("/api/v1/exposure/playground", as("rita", "risk"), "nettingSet", "CPTY:CP-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DRS-5440"));
        mvc.perform(req("/api/v1/exposure/playground", as("rita", "risk"), "nettingSet", "NS-Z")).andExpect(status().isNotFound());
        mvc.perform(req("/api/v1/exposure/playground", as("rita", "risk"), "nettingSet", "NS-X", "q", "1.5")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DRS-5440"));
        mvc.perform(req("/api/v1/exposure/playground", as("rita", "risk"), "nettingSet", "NS-X", "date", "7Y")).andExpect(status().isBadRequest());
        mvc.perform(req("/api/v1/exposure/playground", as("rita", "risk"), "nettingSet", "NS-X", "paths", "9999")).andExpect(status().isBadRequest());
        mvc.perform(req("/api/v1/exposure/playground", as("ann", "agg"), "nettingSet", "NS-X")).andExpect(status().isForbidden());
        mvc.perform(req("/api/v1/exposure/playground", as("rita", "risk"))).andExpect(status().isBadRequest());
    }
}
