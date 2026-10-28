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
 * A hierarchy built from a column the trades' table does not keep (2.9.5, the product owner's rule of 2026-10-28): the date still loads,
 * MGMT and LEGAL as before (DESK-X's VaR is VarApiTest's 390), and only the hierarchy is skipped, said as DRS-5211 in the provenance's
 * warnings (the hierarchy, the column, the kind and the fix), never as a reason to answer another date: the official capture goes on.
 * Its tree answers DRS-5214 with that reason, and the selector lists it as skipped. {@link VarLakeFixture}'s lake, which has no
 * {@code LIMIT_LETTER} (an upgraded server whose data predates the letters).
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.scenario-set=risk-lake",
        "drishti.sources.routes.risk-hierarchy=risk-lake", "drishti.sources.routes.portfolio=risk-lake",
        "drishti.var.hierarchies.LL.levels=LIMIT_LETTER > DESK", "drishti.var.hierarchies.LL.word=LL",
        "drishti.var.official.on-start=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.mode=heap",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class HierarchyColumnMissingTest {

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        Path lake = Files.createDirectories(Path.of("target", "var-nocol-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private JsonNode ask(String url, String scope, int expect) throws Exception {
        var req = get(url).param("asOf", VarLakeFixture.DAY.toString()).header("Authorization", "Bearer " + tokens.mint("rita", List.of("risk"), 300));
        if (scope != null) {
            req = req.param("scope", scope);
        }
        return new ObjectMapper().readTree(mvc.perform(req).andExpect(status().is(expect)).andReturn().getResponse().getContentAsString());
    }

    @Test
    void aLevelColumnTheTradesDoNotKeepSkipsOnlyThatHierarchyWithAWarning() throws Exception {
        JsonNode desk = ask("/api/v1/var/scope", "DESK:DESK-X", 200);
        assertThat(desk.get("var").asDouble()).isEqualTo(390);                    // MGMT as before
        String warnings = desk.at("/provenance/warnings").toString();
        assertThat(warnings).contains("DRS-5211").contains("hierarchy LL").contains("LIMIT_LETTER").contains("pnl-vector")
                .contains("layout.pnl-vector.columns");
        JsonNode tree = ask("/api/v1/var/tree", null, 200);
        assertThat(tree.get("hierarchy").asText()).isEqualTo("MGMT");
        assertThat(tree.get("hierarchies").toString()).doesNotContain("LL");
        assertThat(tree.get("hierarchyInfo").toString()).contains("\"skipped\":true").contains("LIMIT_LETTER");
        var req = get("/api/v1/var/tree").param("hierarchy", "LL").param("asOf", VarLakeFixture.DAY.toString())
                .header("Authorization", "Bearer " + tokens.mint("rita", List.of("risk"), 300));
        JsonNode ll = new ObjectMapper().readTree(mvc.perform(req).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        assertThat(ll.get("code").asText()).isEqualTo("DRS-5214");
        assertThat(ll.get("detail").asText()).contains("LIMIT_LETTER").contains("not built");
        // the official capture is not blocked by the warning
        JsonNode cap = new ObjectMapper().readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/var/official")
                .param("asOf", VarLakeFixture.DAY.toString()).header("Authorization", "Bearer " + tokens.mint("ada", List.of("admin"), 300)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cap.path("status").asText()).as(cap.toString()).isIn("captured", "unchanged");
        assertThat(com.ash.drishti.server.var.Hierarchies.skipped()).containsKey("var LL");
    }
}
