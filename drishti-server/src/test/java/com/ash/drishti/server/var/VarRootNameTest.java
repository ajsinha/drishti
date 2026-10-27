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

import com.ash.drishti.compute.var.VarBook;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The top of the VaR tree named by the settings (VAR_GUIDE.md section 20): {@code drishti.var.root-name} names every hierarchy's root,
 * {@code drishti.var.root-names} one hierarchy's root over it; the nodes below keep the data's names; the tree, the headline and a
 * capture show the configured name; a blank setting keeps the data's {@code NAME}; the hot file's digest changes with the names.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=USD",
        "drishti.var.root-name=Acme Group", "drishti.var.root-names.legal=Acme Group (legal entities)",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarRootNameTest {

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-root-name-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.var.tier.dir", () -> lake.resolve("hot").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode get200(String url) throws Exception {
        return json.readTree(mvc.perform(get(url).header("Authorization", "Bearer " + tokens.mint("rita", List.of("risk"), 300)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void theSettingNamesTheRootsOfBothTreesAndLeavesTheNodesBelow() throws Exception {
        Map<String, VarBook.Node> nodes = svc.date(VarLakeFixture.DAY).book().nodes();
        assertThat(nodes.get("MGMT~BANK").name()).isEqualTo("Acme Group");                       // root-name
        assertThat(nodes.get("LEGAL~BANK").name()).isEqualTo("Acme Group (legal entities)");      // root-names wins for LEGAL
        assertThat(nodes.get("MGMT~DESK-X").name()).isEqualTo("DESK-X");                         // below the root: the data's
        assertThat(nodes.get("LEGAL~LE-A").name()).isEqualTo("LE-A");
        JsonNode tree = get200("/api/v1/var/tree?hierarchy=MGMT&asOf=" + VarLakeFixture.DAY);
        assertThat(tree.at("/nodes/0/name").asText()).isEqualTo("Acme Group");
        JsonNode legal = get200("/api/v1/var/tree?hierarchy=LEGAL&asOf=" + VarLakeFixture.DAY);
        assertThat(legal.at("/nodes/0/name").asText()).isEqualTo("Acme Group (legal entities)");
        assertThat(get200("/api/v1/var/scope?scope=BANK&asOf=" + VarLakeFixture.DAY).toString()).contains("Acme Group");
    }

    @Test
    void blankKeepsTheDataNameAndTheNamesAreInTheHotFilesDigest() {
        VarProperties none = new VarProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, " ", null, null, null, null);
        assertThat(none.rootName("MGMT", null, "Drishti Bank")).isEqualTo("Drishti Bank");
        assertThat(none.rootName("LEGAL", "", "Drishti Bank")).isEqualTo("Drishti Bank");
        assertThat(none.rootNamesKey()).isEmpty();                                               // the files of earlier releases still match
        VarProperties set = new VarProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "", Map.of("mgmt", "Group"), null, null, null);
        assertThat(set.rootName("MGMT", null, "Drishti Bank")).isEqualTo("Group");
        assertThat(set.rootName("LEGAL", null, "Drishti Bank")).isEqualTo("Drishti Bank");      // blank root-name: the data's
        assertThat(set.rootName("MGMT", "MGMT~BANK", "Rates")).isEqualTo("Rates");               // not a root
        assertThat(set.rootNamesKey()).isNotEmpty().isNotEqualTo(svc.props().rootNamesKey());
    }
}
