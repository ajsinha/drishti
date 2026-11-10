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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The MR cube's stressed VaR on a fixed set of a view's cells (MR_CUBE_GUIDE.md section 5; {@code drishti.cube.mr.stressed-max-cells}, 4
 * here), on {@link VarStressApiTest}'s lake (a normal and a stress set; books BOOK-1, BOOK-2 under DESK-X, BOOK-3 under DESK-Y): the view
 * TT_BOOK × DESK gets its stressed VaR on its first four cells in the grid's order, the same four whether it is asked on a cold cache,
 * after other views filled the cache, or beside them in parallel; each figure is the VaR engine's own stressed VaR of the cell's scope, bit
 * for bit; a total whose cells are not all computed has no diversification.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=var-limit",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=VALUE_TYPE,GRAIN,TRADE_ID,POSITION_ID,CURRENCY,SOURCE_SYSTEM,"
                + "LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,COUNTERPARTY,DIRECTION,QUANTITY,PV,"
                + "TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,SCENARIO_SET_ID,BATCH_ID,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.var-limit=risk-lake",
        "drishti.var.hierarchies.MGMT.title=Management", "drishti.var.hierarchies.MGMT.levels=DESK > TT_BOOK",
        "drishti.var.hierarchies.MGMT.level-names=desk > book", "drishti.var.hierarchies.MGMT.ids=leaf",
        "drishti.var.hierarchies.MGMT.limits=var-limit", "drishti.var.hierarchies.LEGAL.title=Legal entity",
        "drishti.var.hierarchies.LEGAL.levels=LEGAL_ENTITY", "drishti.var.hierarchies.LEGAL.ids=leaf",
        "drishti.var.hierarchies.LEGAL.limits=var-limit",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=USD",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.engines.mr-cube.enabled=true", "drishti.cube.mr.stressed-max-cells=4",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarMrCubeCapTest {

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        Path lake = Files.createDirectories(Path.of("target", "var-mr-cap-lake-" + System.nanoTime())).toAbsolutePath();
        VarStressApiTest.write(lake, VarStressApiTest.PLAIN, false);
        VarStressApiTest.write(lake, VarStressApiTest.DAY, true);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.var.tier.dir", () -> lake.resolve("hot").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    @Autowired PivotCore core;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode ask(List<String> cols) throws Exception {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("source", "var");
        view.put("date", VarStressApiTest.DAY.toString());
        view.put("rows", List.of("TT_BOOK"));
        view.put("columns", cols);
        view.put("values", List.of(Map.of("measure", "var()", "as", "VaR"), Map.of("measure", "svar()", "as", "Stressed VaR")));
        String body = json.writeValueAsString(Map.of("view", view));
        String out = mvc.perform(post("/api/v1/pivot/query").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + tokens.mint("rita", List.of("risk"), 300))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        return json.readTree(out);
    }

    /** The cells that got a stressed VaR, with the figure (what the person sees of the measure). */
    private static Map<String, Double> stressed(JsonNode a) {
        Map<String, Double> out = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = a.get("cells").fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> c = it.next();
            JsonNode v = c.getValue().path("values").path("Stressed VaR");
            if (v.isNumber()) {
                out.put(c.getKey(), v.asDouble());
            }
        }
        return out;
    }

    @Test
    void theFirstFourCellsInTheGridsOrderGetTheEnginesOwnStressedVar() throws Exception {
        core.cache().clear();
        JsonNode a = ask(List.of("DESK"));
        // rows BOOK-1, BOOK-2, BOOK-3, then the total; each row's columns DESK-X, DESK-Y, then its total (a cell without trades is not there)
        assertThat(stressed(a).keySet()).containsExactlyInAnyOrder("TT_BOOK=BOOK-1|DESK=DESK-X", "TT_BOOK=BOOK-1|*",
                "TT_BOOK=BOOK-2|DESK=DESK-X", "TT_BOOK=BOOK-2|*");
        Principal rita = new Principal("rita", List.of("risk"));
        for (Map.Entry<String, Double> e : stressed(a).entrySet()) {
            String scope = a.get("cells").get(e.getKey()).get("scope").asText();
            double engine = svc.scoped(rita, scope, VarStressApiTest.DAY, null, svc.props().defaultLens().withStressed(true)).measures().var();
            assertThat(e.getValue()).as(e.getKey()).isEqualTo(engine);
        }
        assertThat(a.get("cells").get("*|*").get("notes").get("Stressed VaR").asText())
                .contains("stressed VaR is computed for the first 4 cells of a view, row by row as the grid shows them");
        assertThat(a.get("cells").get("*|*").get("values").get("VaR").isNumber()).isTrue();
    }

    @Test
    void theSameViewGetsTheSameCellsAfterOtherViewsAndInParallel() throws Exception {
        core.cache().clear();
        JsonNode cold = ask(List.of("DESK"));
        core.cache().clear();
        ask(List.of());
        JsonNode after = ask(List.of("DESK"));
        assertThat(after.get("cells")).isEqualTo(cold.get("cells"));
        JsonNode again = ask(List.of("DESK"));                     // every cell cached now: still the same four
        assertThat(again.get("cells")).isEqualTo(cold.get("cells"));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 3; round++) {
                core.cache().clear();
                List<Future<JsonNode>> fs = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    List<String> cols = i % 2 == 0 ? List.of("DESK") : List.of();
                    fs.add(pool.submit(() -> ask(cols)));
                }
                for (int i = 0; i < fs.size(); i += 2) {
                    assertThat(fs.get(i).get().get("cells")).as("round " + round + ", ask " + i).isEqualTo(cold.get("cells"));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTotalWhoseCellsAreNotAllComputedHasNoDiversification() throws Exception {
        core.cache().clear();
        JsonNode a = ask(List.of("DESK"));
        JsonNode grand = null;
        for (JsonNode r : a.get("rows")) {
            if ("grand".equals(r.get("kind").asText())) {
                grand = r;
            }
        }
        assertThat(grand).isNotNull();
        assertThat(grand.get("diversificationByColumn").get("DESK=DESK-X").get("Stressed VaR").isNull()).isTrue();   // parts computed, total not
        for (Iterator<Map.Entry<String, JsonNode>> it = grand.get("diversificationByColumn").fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> col = it.next();
            JsonNode gap = col.getValue().get("Stressed VaR");                    // the total row is past the cap: null, or none at all
            assertThat(gap == null || gap.isNull()).as(col.getKey() + ": " + gap).isTrue();
            assertThat(col.getValue().get("VaR").isNumber()).as(col.getKey()).isTrue();
        }
    }
}
