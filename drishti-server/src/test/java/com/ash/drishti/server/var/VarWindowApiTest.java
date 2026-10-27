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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.compute.var.VarCalc;
import com.ash.drishti.compute.var.VarMath;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * The scenario window and the confidences (VAR_GUIDE.md section 18.5). With {@code drishti.var.scenario-window: 25} a date whose set holds
 * 40 scenarios is computed on its newest 25: equal, for the bank and every node, to a date whose set holds only those 25 (its rows cut to
 * them at load); the provenance says the window; a window or a confidence asked per request recomputes the headline, labelled and never
 * official; at 95% the rank is ceil(N x 0.05); a window below 20 or a confidence outside (0.5, 1) is refused at start.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=VALUE_TYPE,GRAIN,TRADE_ID,POSITION_ID,CURRENCY,SOURCE_SYSTEM,"
                + "LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,COUNTERPARTY,DIRECTION,QUANTITY,PV,"
                + "TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,SCENARIO_SET_ID,BATCH_ID,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake",
        "drishti.var.scenario-window=25",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=USD",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarWindowApiTest {

    /** A set of 40 scenarios. */
    static final LocalDate LONG = VarLakeFixture.DAY;
    /** A set of only the newest 25 of them, the rows cut to them. */
    static final LocalDate SHORT = LONG.plusDays(1);
    static final int M = 40;
    static final int W = 25;
    static Path lake;

    static List<String> dates() {
        List<String> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 7, 1);
        while (out.size() < M) {
            if (d.getDayOfWeek().getValue() < 6) {
                out.add(d.toString());
            }
            d = d.plusDays(1);
        }
        return out;
    }

    static double[] vector(String id, int n) {
        java.util.SplittableRandom r = new java.util.SplittableRandom(id.hashCode());
        double[] v = new double[M];
        for (int j = 0; j < M; j++) {
            v[j] = Math.round(r.nextGaussian() * 1000);
        }
        return java.util.Arrays.copyOfRange(v, M - n, M);
    }

    static void write(LocalDate day, int n, String set) throws Exception {
        VarLakeFixture.write(lake, day, 1.0, false, false);              // the hierarchy, the portfolios
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String[] t : new String[][] {{"T1", "BOOK-1", "DESK-X", "LE-A"}, {"T3", "BOOK-1", "DESK-X", "LE-A"}, {"T4", "BOOK-2", "DESK-X",
                "LE-A"}, {"T5", "BOOK-3", "DESK-Y", "LE-B"}}) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("VALUE_TYPE", "PNL");
            d.put("GRAIN", "TRADE");
            d.put("TRADE_ID", t[0]);
            d.put("CURRENCY", "USD");
            d.put("SOURCE_SYSTEM", "MUREX");
            d.put("VECTOR_ID", "PV-" + t[0]);
            d.put("BUSINESS_DATE", day.toString());
            d.put("SCENARIO_SET_ID", set);
            d.put("BATCH_ID", "B-" + day + "-01");
            d.put("LEGAL_ENTITY", t[3]);
            d.put("DIVISION", "DIV-M");
            d.put("DESK", t[2]);
            d.put("TT_BOOK", t[1]);
            d.put("PORTFOLIO", t[1]);
            d.put("PRODUCT_GROUP", "Rates");
            d.put("PRODUCT_TYPE", "IRS");
            d.put("COUNTERPARTY", "CP-1");
            d.put("DIRECTION", "LONG");
            d.put("QUANTITY", 1.0);
            d.put("PV", 1000.0);
            d.put("TRADE_STATUS", "VALIDATED");
            d.put("MATURITY_DATE", "2030-01-15");
            d.put("STATUS", "ok");
            d.put("REPORTING_CCY", "USD");
            d.put("PNL", vector(t[0], n));
            rows.add(d);
        }
        Path work = Files.createDirectories(lake.resolve("_work"));
        VarLakeFixture.table(lake, work, "pnl-vector", "VECTOR_ID", rows, List.of("TRADE_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK",
                "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV", "TRADE_STATUS", "MATURITY_DATE",
                "STATUS", "REPORTING_CCY", "SCENARIO_SET_ID", "BATCH_ID"), "PNL");
        List<String> all = dates();
        VarLakeFixture.table(lake, work, "scenario-set", "SCENARIO_SET_ID", List.of(Map.of("SCENARIO_SET_ID", set, "BUSINESS_DATE",
                day.toString(), "SCENARIO_DATES", all.subList(M - n, M))), List.of(), null);
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-window-lake-" + System.nanoTime())).toAbsolutePath();
        write(LONG, M, "HS-40");
        write(SHORT, W, "HS-25");
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.var.tier.dir", () -> lake.resolve("hot").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode ask(String url, String scope, LocalDate day) throws Exception {
        var req = get(url).param("scope", scope).param("asOf", day.toString()).header("Authorization", "Bearer " + tokens.mint("rita",
                List.of("risk"), 300));
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void theNewestNScenariosOfALongerSetEqualASetOfOnlyThose() throws Exception {
        assertThat(svc.date(LONG).book().width()).isEqualTo(W);
        for (String node : svc.date(SHORT).book().nodes().keySet()) {
            JsonNode a = ask("/api/v1/var/scope/path?size=1", "NODE:" + node, LONG);
            JsonNode e = ask("/api/v1/var/scope/path?size=1", "NODE:" + node, SHORT);
            assertThat(a.get("var").asDouble()).as(node).isEqualTo(e.get("var").asDouble());
            assertThat(a.get("es").asDouble()).as(node).isEqualTo(e.get("es").asDouble());
            assertThat(a.get("series").toString()).as(node).isEqualTo(e.get("series").toString());
        }
        JsonNode bank = ask("/api/v1/var/scope", "BANK", LONG);
        assertThat(bank.get("scenarios").asInt()).isEqualTo(W);
        assertThat(bank.get("k").asInt()).isEqualTo(VarMath.rank(W, 0.99));
        assertThat(bank.at("/provenance/scenarioWindow/note").asText()).isEqualTo("newest 25 of 40 scenarios (" + dates().get(M - W) + " … "
                + dates().get(M - 1) + ")");
        assertThat(ask("/api/v1/var/scope", "BANK", SHORT).at("/provenance/scenarioWindow/note").asText()).startsWith("all 25 scenarios");
    }

    @Test
    void aWindowOrAConfidenceAskedPerRequestIsLabelledAndNeverOfficial() throws Exception {
        JsonNode b = ask("/api/v1/var/scope?window=20&confidence=0.95", "BANK", LONG);
        double[] v = svc.scoped(null, "NODE:MGMT~BANK", LONG, null).vector();
        VarCalc.Measures m = new VarCalc(0.95, 0.975, svc.calc().rule(), svc.calc().window(), svc.calc().scaleToVar())
                .measures(java.util.Arrays.copyOfRange(v, v.length - 20, v.length));
        assertThat(b.get("var").asDouble()).isCloseTo(m.var(), within(0.5));
        assertThat(b.get("k").asInt()).isEqualTo(1);                                    // ceil(20 x 0.05)
        assertThat(b.get("confidence").asDouble()).isEqualTo(0.95);
        assertThat(b.at("/override/official").asBoolean()).isFalse();
        assertThat(b.at("/override/note").asText()).contains("newest 20 of 25").contains("95.0%");
        assertThat(ask("/api/v1/var/scope?window=60", "BANK", LONG).at("/override/warning").asText()).contains("all of them used");
        var bad = get("/api/v1/var/scope").param("scope", "BANK").param("confidence", "1.2").header("Authorization", "Bearer "
                + tokens.mint("rita", List.of("risk"), 300));
        assertThat(json.readTree(mvc.perform(bad).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString())
                .get("detail").asText()).contains("not in (0.5, 1)");
    }

    @Test
    void theRankFollowsTheConfidenceAndBadSettingsAreRefusedAtStart() {
        assertThat(VarMath.rank(509, 0.95)).isEqualTo(26);                              // ceil(509 x 0.05): the demo set at 95%
        assertThat(VarMath.rank(250, 0.99)).isEqualTo(3);                               // the 3rd worst of 250
        assertThat(VarMath.rank(250, 0.975)).isEqualTo(7);                              // ES: the mean of the 7 worst
        assertThatThrownBy(() -> VarProperties.checkWindow("10", "drishti.var.scenario-window")).hasMessageContaining("below 20");
        assertThatThrownBy(() -> VarProperties.checkWindow("two years", "drishti.var.scenario-window")).hasMessageContaining("neither all");
        assertThatThrownBy(() -> VarProperties.checkConfidence(1.0, "drishti.var.confidence")).hasMessageContaining("not in (0.5, 1)");
        assertThatThrownBy(() -> VarProperties.checkConfidence(0.4, "drishti.var.es-confidence")).hasMessageContaining("not in (0.5, 1)");
        assertThat(VarProperties.checkWindow(" ALL ", "x")).isEqualTo("all");
        // the hot file's digest has the window in it: a change is never masked by a file built before
        LocalDate d = LONG;
        assertThat(VarTier.digest(d, "v", "h", "HS-40|window=25", "r")).isNotEqualTo(VarTier.digest(d, "v", "h", "HS-40", "r"));
    }
}
