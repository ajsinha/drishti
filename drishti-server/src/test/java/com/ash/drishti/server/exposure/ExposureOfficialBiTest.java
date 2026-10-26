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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * Exposure in BI (2.9.0): the shipped sample report counterparty-risk (model trading-risk) reads the computed exposure peaks from the
 * PUBLISHED official results (risk-results/exposure-official-cpty, written here by the server's own {@link ExposurePublisher}) through
 * the counterparty-risk pack's read-only Delta connector exposure-results, never from the retired stored fields of the netting set. A
 * restated date keeps only its latest result version; "Exposure (EE peak)" sums the netting sets' rows; "Exposure (PFE peak)" reads the
 * counterparties' own rows, never a sum of their sets' PFEs, and the group row is never counted.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=core,market-data,trading,market-risk,counterparty-risk",
        "drishti.sources.plugins.demo.enabled=false", "drishti.var.official.enabled=false", "drishti.var.official.on-start=false",
        "drishti.ccr.exposure.official.enabled=false", "drishti.ccr.exposure.official.on-start=false",
        "drishti.bi.dir=${drishti.test.dir}/exposure-bi-folder", "drishti.bi.folder-refresh=1s",
        "drishti.bi.query.store-dir=${drishti.test.dir}/exposure-bi-store/${random.uuid}",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/exposure-bi-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class ExposureOfficialBiTest {

    static final String REPORT = "file:report:counterparty-risk";
    static final LocalDate D1 = LocalDate.parse("2026-09-29");
    static final LocalDate D2 = LocalDate.parse("2026-09-30");
    /** hierarchy, scope, name, parent, level, counterparty; peak EE and peak PFE 95 on D1, on D2 version 1, on D2 version 2 (the restatement) */
    static final Object[][] SCOPES = {
        {"NSET", "NSET:NS-A-1", "NS-A-1", "CP-A", "netting-set", "CP-A", 10.0, 30.0, 11.0, 31.0, 12.0, 32.0},
        {"NSET", "NSET:NS-A-2", "NS-A-2", "CP-A", "netting-set", "CP-A", 20.0, 40.0, 21.0, 41.0, 22.0, 42.0},
        {"NSET", "NSET:NS-B-1", "NS-B-1", "CP-B", "netting-set", "CP-B", 5.0, 9.0, 6.0, 10.0, 7.0, 11.0},
        // a counterparty's PFE is its sets netted path by path: less than the sum of its sets' PFEs (32 + 42 = 74 on D2 v2)
        {"CPTY", "CPTY:CP-A", "Counterparty A", "GROUP:G-1", "counterparty", "CP-A", 28.0, 60.0, 30.0, 62.0, 33.0, 65.0},
        {"CPTY", "CPTY:CP-B", "Counterparty B", "GROUP:G-1", "counterparty", "CP-B", 5.0, 9.0, 6.0, 10.0, 7.0, 11.0},
        {"GROUP", "GROUP:G-1", "G-1", null, "group", null, 33.0, 66.0, 36.0, 70.0, 40.0, 72.0},
    };

    static {
        try {
            Path to = com.ash.drishti.server.TestRun.root().resolve("exposure-bi-folder");
            List<String> files = new ArrayList<>(List.of("datasets/counterparty-exposure-official.dataset.yaml",
                    "datasets/counterparty-exposure-official-netting-sets.dataset.yaml", "datasets/counterparty-exposure-official-counterparties.dataset.yaml",
                    "models/trading-risk.model.yaml", "reports/counterparty-risk.report.yaml"));
            for (String t : List.of("trades", "netting-sets", "counterparties", "desks", "books")) {
                files.add("datasets/trading-risk-" + t + ".dataset.yaml");
            }
            for (String f : files) {
                Files.createDirectories(to.resolve(f).getParent());
                Files.copy(Path.of("../config/bi", f), to.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static List<Map<String, Object>> rows(int column, long version) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] s : SCOPES) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("HIERARCHY", s[0]);
            r.put("NODE_ID", s[1]);
            r.put("NODE_NAME", s[2]);
            r.put("PARENT_ID", s[3]);
            r.put("LEVEL", s[4]);
            r.put("COUNTERPARTY", s[5]);
            r.put("LEGAL_ENTITY", "LE-NY");
            r.put("RESULT_VERSION", version);
            r.put("INPUT_VERSIONS", "{}");
            r.put("COMPUTED_AT", "2026-10-01T06:00:00Z");
            r.put("CONTRACT_VERSION", ExposurePublisher.CONTRACT_VERSION);
            r.put("RUN_ID", "XR-2026-09-30-1");
            r.put("PATHS", 1000L);
            r.put("CE", 1.0);
            r.put("EPE", 2.0);
            r.put("EEPE", 3.0);
            r.put("PEAK_EE", s[column]);
            r.put("PEAK_PFE_95", s[column + 1]);
            r.put("PEAK_PFE_99", (Double) s[column + 1] * 1.5);
            r.put("EAD_IMM", 4.2);
            r.put("PEAK_PFE_DATE", "2027-09-30");
            r.put("PROFILE", "[]");
            r.put("NETTING_SETS", 1L);
            r.put("TRADES", 3L);
            r.put("FAILED_TRADES", 0L);
            r.put("PARTIAL_SETS", 0L);
            out.add(r);
        }
        return out;
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        Path lake = com.ash.drishti.server.TestRun.temp("exposure-bi-lake");
        ExposurePublisher publisher = new ExposurePublisher(lake, 7);
        publisher.publish(ExposurePublisher.CPTY, D1, rows(6, 1));
        publisher.publish(ExposurePublisher.CPTY, D2, rows(8, 1));
        publisher.publish(ExposurePublisher.CPTY, D2, rows(10, 2));     // the restatement: the latest version is the date's figure
        r.add("DRISHTI_DELTA_ROOT", lake::toString);                     // the pack's own connector (and its layout), over this lake
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

    JsonNode mq(String select, String date) throws Exception {
        String body = "{\"mq\":1,\"model\":\"trading-risk@1\",\"select\":" + select + ",\"time\":{\"as-of\":\"" + date + "\"}}";
        String answer = mvc.perform(post("/api/v1/bi/mq").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString();
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
    void theReportShowsTheComputedPeaksFromTheLatestPublishedVersion() throws Exception {
        String d = D2.toString();
        String loaded = "";
        for (int i = 0; i < 100 && !loaded.contains("Counterparty risk"); i++) {      // the folder is read on its own thread
            loaded = mvc.perform(get("/api/v1/bi/reports/" + REPORT)).andReturn().getResponse().getContentAsString();
            if (!loaded.contains("Counterparty risk")) {
                Thread.sleep(200);
            }
        }
        assertThat(loaded).contains("Counterparty risk");

        assertThat(visual("exposure", "ee", d, d).path("rows").get(0).get(0).asDouble())
                .as("EE peak: the netting sets' rows of version 2 (12 + 22 + 7), never the counterparty or group rows").isEqualTo(41.0);
        assertThat(visual("exposure", "pfe", d, d).path("rows").get(0).get(0).asDouble())
                .as("PFE peak: the counterparties' own rows of version 2 (65 + 11), not their sets' PFEs (32 + 42 + 11) nor the group").isEqualTo(76.0);
        assertThat(visual("exposure", "ee", D1.toString(), D1.toString()).path("rows").get(0).get(0).asDouble()).isEqualTo(35.0);

        assertThat(byFirst(mq("{\"fields\":[\"cpty_exposure.COUNTERPARTY\"],\"measures\":[\"Exposure (PFE peak)\"]}", d), 1))
                .as("a counterparty's PFE is its own row").containsExactlyInAnyOrderEntriesOf(Map.of("CP-A", 65.0, "CP-B", 11.0));
        assertThat(byFirst(mq("{\"fields\":[\"set_exposure.COUNTERPARTY\"],\"measures\":[\"Exposure (EE peak)\"]}", d), 1))
                .containsExactlyInAnyOrderEntriesOf(Map.of("CP-A", 34.0, "CP-B", 7.0));
    }
}
