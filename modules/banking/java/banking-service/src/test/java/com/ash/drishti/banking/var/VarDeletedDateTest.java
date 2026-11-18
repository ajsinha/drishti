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

import com.ash.drishti.lakeload.LakeRetention;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.ash.drishti.testroots.TestRoots;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
 * A business date deleted from the lake ({@code lake delete}, VAR_GUIDE.md section 15.8): its data-load signal with status {@code deleted}
 * ({@link VarOfficial#onDeleted}) drops the date from the VaR engine at once: a request for it is answered by the newest date before it with the notice "scenario P&amp;L
 * for D not loaded — showing D-1" (never the deleted figures), the default date moves back to the newest remaining one, nothing is captured;
 * the date loaded again (and its ready signal) brings it back.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=var-limit",
        "drishti.sources.connectors.risk-lake.settings.domain=risk", "drishti.sources.connectors.risk-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.var-limit=risk-lake",
        "drishti.var.hierarchies.MGMT.title=Management", "drishti.var.hierarchies.MGMT.levels=DESK > TT_BOOK",
        "drishti.var.hierarchies.MGMT.level-names=desk > book", "drishti.var.hierarchies.MGMT.ids=leaf",
        "drishti.var.hierarchies.MGMT.limits=var-limit",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarDeletedDateTest {

    static final LocalDate D1 = VarLakeFixture.DAY;
    static final LocalDate D2 = D1.plusDays(1);
    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of(TestRoots.module(), "target", "var-deleted-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake, D1, 1.0, false, false);
        VarLakeFixture.write(lake, D2, 2.0, false, false);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarOfficial official;
    @Autowired VarService svc;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode bank(LocalDate asOf) throws Exception {
        var req = get("/api/v1/risk/var/scope").param("scope", "BANK").header("Authorization", "Bearer " + tokens.mint("rita", List.of("risk"), 300));
        if (asOf != null) {
            req = req.param("asOf", asOf.toString());
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /**
     * What the data-load signal does for the engine once the connector reads the lake as it is now (LoadService: the kind's caches dropped,
     * then the deleted or ready listeners; the signal's own steps are LoadServiceDeletedTest's): the VaR official's listener called.
     */
    private void signal(boolean deleted, String before) throws Exception {
        long until = System.currentTimeMillis() + 20_000;
        while (svc.loader().version(D2).equals(before) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        Thread.sleep(1500);                                              // the connector's refresh (1 s) has the new version
        if (deleted) {
            official.onDeleted("pnl-vector", D2);
        } else {
            official.onLoad("pnl-vector", D2);
        }
    }

    @Test
    void aDeletedDateIsDroppedAtOnceAndComesBackWhenLoadedAgain() throws Exception {
        JsonNode d2 = bank(D2);
        assertThat(d2.get("businessDate").asText()).isEqualTo(D2.toString());
        assertThat(bank(null).get("businessDate").asText()).as("the newest date is the default").isEqualTo(D2.toString());
        double d1Var = bank(D1).get("var").asDouble();
        assertThat(d2.get("var").asDouble()).as("D2's own figures (twice D1's)").isNotEqualTo(d1Var);

        String before = svc.loader().version(D2);
        Map<String, Object> done = LakeRetention.dropDates(lake.toString(), "delta", "risk/pnl-vector", LakeRetention.dates(D2.toString()), false);
        assertThat(done.get("dropped")).isNotEqualTo(0L);
        signal(true, before);

        JsonNode gone = bank(D2);
        assertThat(gone.get("businessDate").asText()).as("the deleted date is never answered from its old figures").isEqualTo(D1.toString());
        assertThat(gone.get("asOfNotice").asText()).isEqualTo("scenario P&L for " + D2 + " not loaded — showing " + D1);
        assertThat(gone.get("var").asDouble()).isEqualTo(d1Var);
        assertThat(bank(null).get("businessDate").asText()).as("the default date moves back").isEqualTo(D1.toString());
        assertThat(svc.official().index(D2, VarDate.USD)).as("a deleted date is never captured").isEmpty();

        before = svc.loader().version(D2);
        VarLakeFixture.write(lake, D2, 2.0, false, false);                    // loaded again
        signal(false, before);
        JsonNode back = bank(D2);
        assertThat(back.get("businessDate").asText()).isEqualTo(D2.toString());
        assertThat(back.get("asOfNotice").isNull()).isTrue();
        assertThat(back.get("var").asDouble()).isEqualTo(d2.get("var").asDouble());
        assertThat(bank(null).get("businessDate").asText()).isEqualTo(D2.toString());
    }
}
