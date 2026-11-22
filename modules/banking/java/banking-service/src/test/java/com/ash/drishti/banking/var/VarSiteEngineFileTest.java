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

import com.ash.lake.tck.TestRoots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.banking.compute.var.VarMath;
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
 * Gate 4 F22: the VaR settings a site writes in its own {@code config/engines/var.yaml} are the ones VaR is computed with. The site's file
 * (DRISHTI_ENGINES_DIR, imported after the jar's copy) sets {@code drishti.module.banking.var.window: 400} and {@code confidence: 0.95};
 * over a date of 420 scenarios the bank's VaR is the 20th worst of the newest 400 (ceil(400 x 0.05)), not the 3rd worst of the newest 250
 * that the shipped defaults (250, 0.99) give: a value that changes. (An old key in the same file stops the start: ModuleSettingsTest.)
 */
@SpringBootTest(properties = {"drishti.sources.connectors-dir=${drishti.test.dir}/connectors/var-mr-cube-cap", "drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "DRISHTI_ENGINES_DIR=${drishti.test.dir}/site-engines/var-site",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.var-limit=risk-lake",
        "drishti.var.hierarchies.MGMT.title=Management", "drishti.var.hierarchies.MGMT.levels=DESK > TT_BOOK",
        "drishti.var.hierarchies.MGMT.level-names=desk > book", "drishti.var.hierarchies.MGMT.ids=leaf",
        "drishti.var.hierarchies.MGMT.limits=var-limit",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=USD",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.module.banking.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarSiteEngineFileTest {

    static final int M = 420;
    static final LocalDate DAY = VarLakeFixture.DAY;
    static Path lake;

    static {
        com.ash.drishti.server.ConnectorFixtures.copy("var-mr-cube-cap");
        // the site's engine file, as an installation keeps it: the new keys only
        Path dir = com.ash.drishti.server.TestRun.dir("site-engines/var-site");
        try {
            Files.writeString(dir.resolve("var.yaml"), "drishti:\n  module:\n    banking:\n      var:\n        window: 400\n        confidence: 0.95\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static List<String> dates() {
        List<String> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 1, 1);
        while (out.size() < M) {
            if (d.getDayOfWeek().getValue() < 6) {
                out.add(d.toString());
            }
            d = d.plusDays(1);
        }
        return out;
    }

    static double[] vector(String id) {
        java.util.SplittableRandom r = new java.util.SplittableRandom(id.hashCode());
        double[] v = new double[M];
        for (int j = 0; j < M; j++) {
            v[j] = Math.round(r.nextGaussian() * 1000);
        }
        return v;
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of(TestRoots.module(), "target", "var-site-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake, DAY, 1.0, false, false);              // the hierarchy, the portfolios
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
            d.put("BUSINESS_DATE", DAY.toString());
            d.put("SCENARIO_SET_ID", "HS-420");
            d.put("BATCH_ID", "B-" + DAY + "-01");
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
            d.put("PNL", vector(t[0]));
            rows.add(d);
        }
        Path work = Files.createDirectories(lake.resolve("_work"));
        VarLakeFixture.table(lake, work, "pnl-vector", "VECTOR_ID", rows, List.of("TRADE_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK",
                "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV", "TRADE_STATUS", "MATURITY_DATE",
                "STATUS", "REPORTING_CCY", "SCENARIO_SET_ID", "BATCH_ID"), "PNL");
        VarLakeFixture.table(lake, work, "scenario-set", "SCENARIO_SET_ID", List.of(Map.of("SCENARIO_SET_ID", "HS-420", "BUSINESS_DATE",
                DAY.toString(), "SCENARIO_DATES", dates())), List.of(), null);
        r.add("test-connectors.risk-lake.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.var.tier.dir", () -> lake.resolve("hot").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    @Autowired org.springframework.core.env.Environment env;
    final ObjectMapper json = new ObjectMapper();

    @Test
    void theSitesEngineFileSetsTheWindowAndTheConfidenceVaRIsComputedWith() throws Exception {
        assertThat(env.getProperty("drishti.module.banking.var.window")).as("the site's file wins over the jar's copy").isEqualTo("400");
        assertThat(env.getProperty("drishti.module.banking.var.confidence")).isEqualTo("0.95");
        assertThat(svc.date(DAY).book().width()).as("the newest 400 of 420 scenarios").isEqualTo(400);

        var req = get("/api/v1/risk/var/scope").param("scope", "BANK").param("asOf", DAY.toString())
                .header("Authorization", "Bearer " + tokens.mint("rita", List.of("risk"), 300));
        JsonNode bank = json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(bank.get("scenarios").asInt()).isEqualTo(400);
        assertThat(bank.get("confidence").asDouble()).isEqualTo(0.95);
        assertThat(bank.get("k").asInt()).isEqualTo(VarMath.rank(400, 0.95)).isEqualTo(20);

        // the bank's vector over every scenario: its VaR at the site's settings, and at the shipped defaults, worked out here
        double[] all = new double[M];
        for (String t : List.of("T1", "T3", "T4", "T5")) {
            double[] v = vector(t);
            for (int j = 0; j < M; j++) {
                all[j] += v[j];
            }
        }
        double site = new VarCalc(0.95, svc.calc().esConfidence(), svc.calc().rule(), svc.calc().window(), svc.calc().scaleToVar())
                .measures(java.util.Arrays.copyOfRange(all, M - 400, M)).var();
        double shipped = new VarCalc(0.99, svc.calc().esConfidence(), svc.calc().rule(), svc.calc().window(), svc.calc().scaleToVar())
                .measures(java.util.Arrays.copyOfRange(all, M - 250, M)).var();
        assertThat(bank.get("var").asDouble()).isCloseTo(site, within(0.5));
        assertThat(Math.abs(site - shipped)).as("the site's settings change the figure").isGreaterThan(1.0);
    }
}
