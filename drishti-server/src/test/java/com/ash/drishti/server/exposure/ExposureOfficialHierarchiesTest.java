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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/**
 * Exposure on the configured hierarchies (2.9.5): the limit letters (LL, {@code LIMIT_LETTER > DESK}, {@code drishti.var.hierarchies})
 * on {@link ExposureHierarchyFixture}'s paths. The official record holds every letter's node; each node's EE, PFE and EPE are those of
 * its trades (the same as the scope {@code LL:<letter>}, as {@code WHERE:} on the letter, and LL-B's desk node as the desk B is alone on);
 * the root is the bank (with no risk-hierarchy rows here, LL's are the only nodes).
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.ccr-lake.plugin=delta",
        "drishti.sources.connectors.ccr-lake.kinds[0]=simulation-run", "drishti.sources.connectors.ccr-lake.kinds[1]=exposure-path",
        "drishti.sources.connectors.ccr-lake.kinds[2]=netting-set", "drishti.sources.connectors.ccr-lake.kinds[3]=csa",
        "drishti.sources.connectors.ccr-lake.kinds[4]=collateral-balance", "drishti.sources.connectors.ccr-lake.kinds[5]=counterparty",
        "drishti.sources.connectors.ccr-lake.kinds[6]=counterparty-group",
        "drishti.sources.connectors.ccr-lake.settings.domain=risk",
        "drishti.sources.connectors.ccr-lake.settings.layout.exposure-path.columns=TRADE_ID,NETTING_SET,COUNTERPARTY,LEGAL_ENTITY,DESK,TT_BOOK,"
                + "LIMIT_LETTER,ASSET_CLASS,PRODUCT,SOURCE_SYSTEM,MATURITY_DATE,NOTIONAL,GRID_INDEX,GRID_DATE,RUN_ID,BATCH_ID,STATUS",
        "drishti.sources.connectors.ccr-lake.settings.layout.exposure-path.vectors=MTM",
        "drishti.sources.routes.simulation-run=ccr-lake", "drishti.sources.routes.exposure-path=ccr-lake",
        "drishti.sources.routes.netting-set=ccr-lake", "drishti.sources.routes.csa=ccr-lake", "drishti.sources.routes.collateral-balance=ccr-lake",
        "drishti.sources.routes.counterparty=ccr-lake", "drishti.sources.routes.counterparty-group=ccr-lake",
        "drishti.var.hierarchies.LL.title=Limit letters", "drishti.var.hierarchies.LL.levels=LIMIT_LETTER > DESK",
        "drishti.var.hierarchies.LL.word=LL",
        "drishti.ccr.exposure.official.on-start=false", "drishti.ccr.exposure.close-grace-seconds=0", "drishti.var.official.on-start=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class ExposureOfficialHierarchiesTest {

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "exposure-hier-lake-" + System.nanoTime())).toAbsolutePath();
        ExposureHierarchyFixture.write(lake);
        r.add("drishti.sources.connectors.ccr-lake.settings.root", lake::toString);
        r.add("drishti.ccr.exposure.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode get(String url, String auth, String... params) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Authorization", auth)
                .param("asOf", ExposureHierarchyFixture.DAY.toString());
        for (int i = 0; i < params.length; i += 2) {
            req.param(params[i], params[i + 1]);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void everyLetterNodeIsInTheOfficialRecordWithTheFiguresOfItsTrades() throws Exception {
        String ada = "Bearer " + tokens.mint("ada", List.of("admin"), 300);
        String rita = "Bearer " + tokens.mint("rita", List.of("risk"), 300);
        JsonNode run = json.readTree(mvc.perform(post("/api/v1/exposure/official").header("Authorization", ada).param("asOf",
                ExposureHierarchyFixture.DAY.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(run.get("status").asText()).as(run.toString()).isEqualTo("captured");
        JsonNode rec = get("/api/v1/exposure/official", ada);
        JsonNode s = rec.get("scopes");
        for (String n : List.of("LL~BANK", "LL~LL-A", "LL~LL-A/DESK-A", "LL~LL-B", "LL~LL-B/DESK-B")) {
            assertThat(s.has("LL:" + n)).as(n + " in " + s.fieldNames()).isTrue();
        }
        JsonNode letterA = get("/api/v1/exposure/scope", rita, "scope", "LL:LL-A", "q", "0.95");
        JsonNode whereA = get("/api/v1/exposure/scope", rita, "scope", "WHERE:LIMIT_LETTER == 'LL-A'", "q", "0.95");
        for (String[] f : new String[][] {{"epe", "epe"}, {"peakEe", "peakEe"}, {"peakPfe95", "peakPfe"}}) {
            assertThat(s.path("LL:LL~LL-A").path(f[0]).asLong()).as(f[0]).isEqualTo(letterA.path(f[1]).asLong());
            assertThat(whereA.path(f[1]).asLong()).as(f[0]).isEqualTo(letterA.path(f[1]).asLong());
        }
        assertThat(s.path("LL:LL~LL-A").path("epe").asLong()).as(String.join(",", (Iterable<String>) s::fieldNames)).isEqualTo(s.path("LL:LL~LL-A/DESK-A").path("epe").asLong());   // LL-A is all DESK-A
        assertThat(s.path("LL:LL~LL-B/DESK-B").path("epe").asLong()).isEqualTo(Math.round(get("/api/v1/exposure/scope", rita, "scope", "DESK:DESK-B")
                .path("epe").asDouble(Double.NaN)));
        assertThat(s.path("LL:LL~BANK").path("peakEe").asLong()).isEqualTo(get("/api/v1/exposure/scope", rita, "scope", "BANK").path("peakEe").asLong());
    }
}
