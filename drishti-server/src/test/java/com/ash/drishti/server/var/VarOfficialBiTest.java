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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.PinnedBusinessDate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * VaR in BI (2.9.0): the shipped sample model market-risk-var and report market-risk-var read the PUBLISHED official results
 * (risk-results/var-official, written here by the server's own {@link VarPublisher}) through the market-risk pack's read-only Delta
 * connector var-results. A restated date keeps only its latest result version, VaR is never summed across nodes, and the bank's line
 * over a window of dates has a point per date.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=core,market-data,trading,market-risk",
        "drishti.sources.plugins.demo.enabled=false", "drishti.var.official.enabled=false", "drishti.var.official.on-start=false",
        "drishti.bi.dir=${drishti.test.dir}/var-bi-folder", "drishti.bi.folder-refresh=1s",
        "drishti.bi.query.store-dir=${drishti.test.dir}/var-bi-store/${random.uuid}",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/var-bi-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarOfficialBiTest {

    static final String REPORT = "file:report:market-risk-var";
    static final LocalDate D1 = LocalDate.parse("2026-09-29");
    static final LocalDate D2 = LocalDate.parse("2026-09-30");
    /** node, hierarchy, level, parent, name, VaR on D1, VaR on D2 (version 1), VaR on D2 (version 2, the restatement), limit */
    static final Object[][] NODES = {
        {"MGMT~BANK", "MGMT", "bank", null, "Drishti Bank", 100.0, 110.0, 120.0, 200.0},
        {"MGMT~DESK-RATES", "MGMT", "desk", "MGMT~DIV-MARKETS", "Rates", 70.0, 75.0, 80.0, 100.0},
        {"MGMT~DESK-FX", "MGMT", "desk", "MGMT~DIV-MARKETS", "FX", 50.0, 55.0, 52.0, 50.0},   // restated DOWN: a max over versions would say 55
        {"LEGAL~LE-NY", "LEGAL", "legal-entity", "LEGAL~BANK", "LE-NY", 90.0, 95.0, 99.0, 150.0},
        {"LEGAL~LE-LDN", "LEGAL", "legal-entity", "LEGAL~BANK", "LE-LDN", 40.0, 45.0, 49.0, 60.0},
    };

    static {
        try {
            Path to = com.ash.drishti.server.TestRun.root().resolve("var-bi-folder");
            for (String f : List.of("datasets/market-risk-var-official.dataset.yaml", "datasets/market-risk-var-latest.dataset.yaml",
                    "models/market-risk-var.model.yaml", "reports/market-risk-var.report.yaml")) {
                Files.createDirectories(to.resolve(f).getParent());
                Files.copy(Path.of("../config/bi", f), to.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static List<Map<String, Object>> rows(int column, long version) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] n : NODES) {
            Map<String, Object> r = new LinkedHashMap<>();
            double var = (Double) n[column];
            r.put("HIERARCHY", n[1]);
            r.put("NODE_ID", n[0]);
            r.put("NODE_NAME", n[4]);
            r.put("PARENT_ID", n[3]);
            r.put("LEVEL", n[2]);
            r.put("CAPTURE_ID", "CAP-" + column + "-" + n[0]);
            r.put("RESULT_VERSION", version);
            r.put("INPUT_VERSIONS", "{}");
            r.put("COMPUTED_AT", "2026-10-01T06:00:00Z");
            r.put("CONTRACT_VERSION", VarPublisher.CONTRACT_VERSION);
            r.put("VAR", var);
            r.put("ES", var + 1);
            r.put("CONFIDENCE", 0.99);
            r.put("ES_CONFIDENCE", 0.975);
            r.put("SCENARIO_SET_ID", "HS-509");
            r.put("STANDALONE_SUM", var * 1.5);
            r.put("DIVERSIFICATION", var * 0.5);
            r.put("HYPO_PNL", -1.0);
            r.put("LIMIT", n[8]);
            r.put("LIMIT_USE", var / (Double) n[8]);
            r.put("TRADE_COUNT", 10L);
            r.put("POSITION_COUNT", 8L);
            r.put("EXCEPTIONS_250", 0L);
            r.put("TRAFFIC_LIGHT", "green");
            out.add(r);
        }
        return out;
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        Path lake = com.ash.drishti.server.TestRun.temp("var-bi-lake");
        VarPublisher publisher = new VarPublisher(lake, 7);
        publisher.publish(D1, rows(5, 1));
        publisher.publish(D2, rows(6, 1));
        publisher.publish(D2, rows(7, 2));                  // the restatement: the latest version is the date's figure
        r.add("DRISHTI_DELTA_ROOT", lake::toString);                // the pack's own connector (and its layout), over this lake
    }

    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    JsonNode visual(String page, String visual, String from, String to) throws Exception {
        String body = "{\"page\":\"" + page + "\",\"visual\":\"" + visual + "\",\"state\":{\"dates\":{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}}}";
        String answer = mvc.perform(post(java.net.URI.create("/api/v1/bi/reports/" + REPORT + "/query")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        JsonNode n = json.readTree(answer);
        assertThat(n.has("rows")).as(answer).isTrue();
        return n;
    }

    static Map<String, Double> byFirst(JsonNode answer, int col) {
        Map<String, Double> out = new TreeMap<>();
        for (JsonNode row : answer.path("rows")) {
            out.put(row.get(0).asText(), row.get(col).asDouble());
        }
        return out;
    }

    @Test
    void theReportReadsTheLatestVersionOfThePublishedResultsAndNeverSumsVar() throws Exception {
        String d = D2.toString();
        String loaded = "";
        for (int i = 0; i < 100 && !loaded.contains("market-risk-var"); i++) {       // the folder is read on its own thread
            loaded = mvc.perform(get("/api/v1/bi/reports/" + REPORT)).andReturn().getResponse().getContentAsString();
            if (!loaded.contains("market-risk-var")) {
                Thread.sleep(200);
            }
        }
        assertThat(loaded).contains("Market risk: VaR");

        assertThat(visual("overview", "bank-var", d, d).path("rows").get(0).get(0).asDouble()).as("the restated bank VaR (version 2)").isEqualTo(120.0);
        assertThat(visual("overview", "bank-es", d, d).path("rows").get(0).get(0).asDouble()).isEqualTo(121.0);
        assertThat(byFirst(visual("overview", "by-desk", d, d), 1)).containsExactlyInAnyOrderEntriesOf(Map.of("Rates", 80.0, "FX", 52.0));
        assertThat(byFirst(visual("overview", "by-le", d, d), 1)).containsExactlyInAnyOrderEntriesOf(Map.of("LE-NY", 99.0, "LE-LDN", 49.0));
        assertThat(visual("overview", "nodes", d, d).path("rows")).as("one row per node: the old version is not repeated").hasSize(NODES.length);

        assertThat(visual("overview", "bank-var", D1.toString(), D1.toString()).path("rows").get(0).get(0).asDouble()).isEqualTo(100.0);
        JsonNode line = visual("overview", "bank-over-time", D1.toString(), d);
        assertThat(line.path("rows")).as(line.toString()).hasSize(2);
        assertThat(line.path("rows").get(0).get(1).asDouble()).isEqualTo(100.0);
        assertThat(line.path("rows").get(1).get(1).asDouble()).as("the bank over two dates is each date's own VaR, not a sum").isEqualTo(120.0);
    }
}
