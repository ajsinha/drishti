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
 * A hierarchy whose level column the trades' table does not promote (VAR_GUIDE.md section 15.14): the date still loads, MGMT and LEGAL as
 * before (DESK-X's VaR is VarApiTest's 390), and every row sits under LL's (Blank) letter: the hierarchy is built, never skipped, its totals
 * complete. The warning (DRS-5211: the hierarchy, the column, the kind and the fix) is in the provenance, in Admin → Health and in the
 * log, never a reason to answer another date: the official capture goes on. {@link VarLakeFixture}'s lake, which has no
 * {@code LIMIT_LETTER} (an upgraded server whose data predates the letters). A level column no source promotes and no loaded pack's layout
 * names is a typo: refused (DRS-5213).
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=var-limit",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.scenario-set=risk-lake",
        "drishti.sources.routes.var-limit=risk-lake",
        "drishti.var.hierarchies.MGMT.title=Management", "drishti.var.hierarchies.MGMT.levels=DESK > TT_BOOK",
        "drishti.var.hierarchies.MGMT.level-names=desk > book", "drishti.var.hierarchies.MGMT.ids=leaf",
        "drishti.var.hierarchies.MGMT.limits=var-limit", "drishti.var.hierarchies.LEGAL.title=Legal entity",
        "drishti.var.hierarchies.LEGAL.levels=LEGAL_ENTITY", "drishti.var.hierarchies.LEGAL.ids=leaf",
        "drishti.var.hierarchies.LEGAL.limits=var-limit",
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
    void aLevelColumnTheTradesDoNotPromotePutsEveryRowUnderBlankWithAWarning() throws Exception {
        JsonNode desk = ask("/api/v1/risk/var/scope", "DESK:DESK-X", 200);
        assertThat(desk.get("var").asDouble()).isEqualTo(390);                    // MGMT as before
        String warnings = desk.at("/provenance/warnings").toString();
        assertThat(warnings).contains("DRS-5211").contains("hierarchy LL").contains("LIMIT_LETTER").contains("pnl-vector")
                .contains("layout.pnl-vector.columns").contains("(Blank)");
        // the warning's counts: every trade under LL's (Blank) letter
        assertThat(warnings).contains("(Blank) LL~~ (LL, level limit-letter): 6 trades with no LIMIT_LETTER value (pnl-vector 6, scenario-pv 0)");
        JsonNode tree = ask("/api/v1/risk/var/tree", null, 200);
        assertThat(tree.get("hierarchies").toString()).contains("LL");
        var req = get("/api/v1/risk/var/tree").param("hierarchy", "LL").param("asOf", VarLakeFixture.DAY.toString())
                .header("Authorization", "Bearer " + tokens.mint("rita", List.of("risk"), 300));
        JsonNode ll = new ObjectMapper().readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode blank = null;
        int bank = -1;
        for (JsonNode n : ll.get("nodes")) {
            if (n.get("id").asText().equals("LL~~")) {
                blank = n;
            }
            if (n.get("id").asText().equals("LL~BANK")) {
                bank = n.get("trades").asInt();
            }
        }
        assertThat(blank).as(ll.toString()).isNotNull();
        assertThat(blank.get("name").asText()).isEqualTo("(Blank)");
        assertThat(blank.get("blank").asBoolean()).isTrue();
        assertThat(blank.get("blankNote").asText()).contains("rows with no LIMIT_LETTER value: 6 trades").contains("fix:");
        assertThat(blank.get("trades").asInt()).isEqualTo(bank).isEqualTo(6);           // totals complete: nothing dropped
        assertThat(blank.get("limit").isNull()).isTrue();                               // no var-limit row names it: none
        assertThat(ask("/api/v1/risk/var/scope", "NODE:LL~~", 200).get("var").asDouble()).isEqualTo(ask("/api/v1/risk/var/scope", "BANK", 200)
                .get("var").asDouble());
        // the official capture is not blocked by the warning
        JsonNode cap = new ObjectMapper().readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/risk/var/official")
                .param("asOf", VarLakeFixture.DAY.toString()).header("Authorization", "Bearer " + tokens.mint("ada", List.of("admin"), 300)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cap.path("status").asText()).as(cap.toString()).isIn("captured", "unchanged");
        assertThat(com.ash.drishti.banking.var.Hierarchies.warnings()).containsKey("var LL LIMIT_LETTER pnl-vector");
    }

    @Test
    void aLevelColumnNoSourcePromotesAndNoPackNamesIsATypoRefused() {
        Hierarchies hs = Hierarchies.parse(java.util.Map.of("LL", java.util.Map.of("levels", "LIMIT_LETER > DESK")), "test")
                .withKnown(java.util.Set.of("LIMIT_LETTER", "DESK"));
        java.util.Map<String, java.util.Set<String>> kept = java.util.Map.of("pnl-vector", java.util.Set.of("DESK", "LIMIT_LETTER"),
                "scenario-pv", java.util.Set.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> hs.unpromoted(kept, true)).hasMessageContaining("DRS-5213")
                .hasMessageContaining("LIMIT_LETER").hasMessageContaining("which no source promotes").hasMessageContaining("layout.pnl-vector.columns");
        assertThat(hs.unpromoted(kept, false)).containsKey("LL LIMIT_LETER pnl-vector");      // exposure: a warning only
        // a column a pack's layout names but this connector does not promote yet (written before the column was added): a warning
        Hierarchies upgraded = Hierarchies.parse(java.util.Map.of("LL", java.util.Map.of("levels", "LIMIT_LETTER > DESK")), "test")
                .withKnown(java.util.Set.of("LIMIT_LETTER", "DESK"));
        assertThat(upgraded.unpromoted(java.util.Map.of("pnl-vector", java.util.Set.of("DESK")), true)).containsKey("LL LIMIT_LETTER pnl-vector");
    }
}
