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

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Parametric VaR on the VaR API (docs/architecture/PARAMETRIC_VAR.md section 6; VAR_GUIDE.md section 18.15) over {@link VarLakeFixture}'s
 * tiny lake: the {@code parametric} block on {@code /scope}, {@code /tree}, {@code /scope/path} (the curve, the expected counts, the two
 * markers) and {@code /scope/contributions} (Euler components adding up to the parametric VaR, the matrix); {@code parametric=false}
 * leaves it out and every historical field byte for byte the same; {@code /status} lists the settings; never official.
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
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarParametricApiTest {

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-param-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode ask(String path, String... params) throws Exception {
        var req = get(path).header("Authorization", "Bearer " + tokens.mint("rita", List.of("risk"), 300));
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        var r = mvc.perform(req).andReturn().getResponse();
        assertThat(r.getStatus()).as(path + " " + r.getContentAsString()).isEqualTo(200);
        return json.readTree(r.getContentAsString());
    }

    @Test
    void theHeadlineCarriesTheCrossCheckAndHistoricalIsUntouched() throws Exception {
        JsonNode h = ask("/api/v1/risk/var/scope", "scope", "BANK");
        JsonNode p = h.get("parametric");
        assertThat(p).isNotNull();
        assertThat(p.get("official").asBoolean()).isFalse();
        assertThat(p.get("z").asDouble()).isBetween(2.3263, 2.3264);
        double expect = p.get("z").asDouble() * p.get("sigma").asDouble() - p.get("mu").asDouble();
        assertThat((double) p.get("var").asLong()).isCloseTo(expect, org.assertj.core.data.Offset.offset(0.5));
        assertThat(p.get("gap").asLong()).isEqualTo(h.get("var").asLong() - p.get("var").asLong());
        assertThat(p.get("describe").toString()).contains("z·σ − μ").contains("not the official VaR").contains("never in the browser");
        JsonNode lean = ask("/api/v1/risk/var/scope", "scope", "BANK", "parametric", "false");
        assertThat(lean.has("parametric")).isFalse();
        ObjectNode without = (ObjectNode) h.deepCopy();
        without.remove("parametric");
        without.remove("provenance");                                    // its timing differs from one call to the next
        ((ObjectNode) lean).remove("provenance");
        assertThat(without).isEqualTo(lean);                             // every historical field the same
    }

    @Test
    void theTreeThePathAndTheStatus() throws Exception {
        JsonNode t = ask("/api/v1/risk/var/tree");
        for (JsonNode n : t.get("nodes")) {
            assertThat(n.get("parametric").get("var").isNumber()).as(n.get("id").asText()).isTrue();
            assertThat(n.get("parametric").get("describe").size()).isGreaterThan(2);
        }
        assertThat(ask("/api/v1/risk/var/tree", "parametric", "false").get("nodes").get(0).has("parametric")).isFalse();
        JsonNode path = ask("/api/v1/risk/var/scope/path", "scope", "BANK").get("parametric");
        assertThat(path.get("curve").get("x")).hasSize(121);
        assertThat(path.get("curve").get("y")).hasSize(121);
        assertThat(path.get("markers").get("historical").asDouble()).isLessThanOrEqualTo(0);
        assertThat(path.get("curveDescribe").toString()).contains("Fitted normal");
        JsonNode st = ask("/api/v1/risk/var/status").get("parametric");
        assertThat(st.get("enabled").asBoolean()).isTrue();
        assertThat(st.get("weighting").asText()).isEqualTo("equal");
        assertThat(st.get("official").asBoolean()).isFalse();
    }

    @Test
    void componentsAddUpToTheParametricVarAndTheMatrix() throws Exception {
        JsonNode c = ask("/api/v1/risk/var/scope/contributions", "scope", "BANK", "by", "trade", "matrix", "true");
        JsonNode tot = c.get("parametric");
        long sum = 0;
        int rows = 0;
        for (JsonNode r : c.get("rows")) {
            sum += r.get("pComponentVar").asLong();
            rows++;
            assertThat(r.get("describe").toString()).contains("z·cov(Pᵢ, P)/σ_P − μᵢ");
        }
        if (!c.get("truncated").asBoolean()) {
            assertThat(Math.abs(sum - tot.get("var").asLong())).isLessThanOrEqualTo(rows);   // each row rounded to the unit
            assertThat(tot.get("addsUp").asBoolean()).isTrue();
        }
        JsonNode m = tot.get("matrix");
        assertThat(m.get("correlation").get(0).get(0).asDouble()).isEqualTo(1.0);
        assertThat(m.get("engine").asText()).isIn("java", "openblas");
    }
}
