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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.deploy.TestArchives;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BI content inside a pack archive: {@code config/bi/} in the pack folder is checked in the upload preview as a dry run of the bundle import, loaded by the
 * deploy through the same import (published items with the pack as their audience, kept so the import can be rolled back), and held, not forced, when it
 * would break a report somebody else published.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=lakepack",
        "drishti.bi.dir=${drishti.test.dir}/pack-bi-folder", "drishti.bi.query.store-dir=${drishti.test.dir}/pack-bi-store", "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/pack-bi-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PackBiDeployTest {

    static Path root;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUTRA = """
            rachana: 1
            sutra: item-view
            version: 1
            description: An item.
            match: { kind: item, priority: 10 }
            title: { pill: "Item", id: $.id }
            strip:
              - { label: Id, bind: $.id }
            panels:
              - { id: built, kind: provenance, title: How this view was built }
            """;
    private static final String DATASET = """
            rachana: 1
            dataset: lake-items
            title: Lake items
            description: The items of the lake pack.
            tables:
              items:
                source: { connector: item-store, kind: item }
                columns: { code: string, shape: string }
            measures:
              Items: { expr: "count(items)" }
            """;
    private static final String REPORT = """
            rachana: 1
            report: lake-items-report
            title: Lake items report
            dataset: lake-items
            pages:
              - id: main
                title: Main
                visuals:
                  - { id: n, type: card, title: Items, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Items] } }
            """;

    private static final String[] SYSTEM_PROPERTIES = {"drishti.packs.dir", "drishti.packs.installed-dir", "drishti.packs.overlay", "drishti.packs.settings-dir",
            "drishti.packs.deploy.history-file", "drishti.sources.connectors-dir", "drishti.bi.governance.imports-dir"};
    private static final Map<String, String> BEFORE = new java.util.HashMap<>();

    static {
        for (String k : SYSTEM_PROPERTIES) {
            BEFORE.put(k, System.getProperty(k));
        }
        try {
            root = TestRun.temp("drishti-pack-bi");
            Path pack = Files.createDirectories(root.resolve("packs/lakepack"));
            Files.createDirectories(pack.resolve("sutras"));
            Files.writeString(pack.resolve("pack.yaml"), packYaml("1.0.0"));
            Files.writeString(pack.resolve("sutras/item.v1.sutra.yaml"), SUTRA);
            Path day = Files.createDirectories(root.resolve("files-a/2026-10-05"));
            Files.writeString(day.resolve("item.jsonl"), "{\"id\":\"I-1\"}\n");
            System.setProperty("drishti.packs.dir", root.resolve("packs").toString());
            System.setProperty("drishti.packs.installed-dir", root.resolve("installed").toString());
            System.setProperty("drishti.packs.overlay", root.resolve("added.yaml").toString());
            System.setProperty("drishti.packs.settings-dir", root.resolve("settings").toString());
            System.setProperty("drishti.sources.connectors-dir", root.resolve("connectors").toString());
            System.setProperty("drishti.packs.deploy.history-file", root.resolve("history.jsonl").toString());
            System.setProperty("drishti.bi.governance.imports-dir", root.resolve("imports").toString());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String packYaml(String version) {
        return "pack: lakepack\nversion: " + version + "\ntitle: Lake pack\nkinds: [item]\nmnemonics:\n  ITM: {kind: item, label: Items}\n"
                + "connectors:\n  item-store:\n    plugin: file\n    kinds: [item]\n    settings:\n      root: " + root.resolve("files-a") + "\nroutes:\n  item: item-store\n";
    }

    @org.junit.jupiter.api.AfterAll
    static void forgetTheFolders() {
        for (String k : SYSTEM_PROPERTIES) {
            if (BEFORE.get(k) == null) {
                System.clearProperty(k);
            } else {
                System.setProperty(k, BEFORE.get(k));
            }
        }
    }

    @Autowired MockMvc mvc;

    private static Map<String, String> files(String version, String dataset) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("pack.yaml", packYaml(version));
        f.put("sutras/item.v1.sutra.yaml", SUTRA);
        f.put("config/bi/datasets/lake-items.dataset.yaml", dataset);
        f.put("config/bi/reports/lake-items-report.report.yaml", REPORT);
        return f;
    }

    private JsonNode upload(byte[] data) throws Exception {
        return JSON.readTree(mvc.perform(post("/api/v1/admin/packs/deploy").contentType(MediaType.APPLICATION_OCTET_STREAM).content(data).header("X-Drishti-Filename", "lakepack.tar.gz"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode confirm(String id) throws Exception {
        return JSON.readTree(mvc.perform(post("/api/v1/admin/packs/deploy/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode read(String path) throws Exception {
        return JSON.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test @Order(1)
    void theUploadPreviewDryRunsTheBiContentAndTheDeployLoadsItAsPublishedItemsForThePack() throws Exception {
        JsonNode up = upload(TestArchives.bundle("lakepack", "1.0.1", files("1.0.1", DATASET)));
        assertThat(up.path("ok").asBoolean()).as(up.toString()).isTrue();
        assertThat(up.path("bi").path("items").asInt()).isEqualTo(2);
        assertThat(up.path("bi").path("kinds").toString()).contains("\"dataset\":1").contains("\"report\":1");
        assertThat(mvc.perform(get("/api/v1/bi/items/pk-lakepack-dataset-lake-items")).andReturn().getResponse().getStatus()).as("a preview loads nothing").isEqualTo(404);

        JsonNode done = confirm(up.path("uploadId").asText());
        assertThat(done.path("bi").path("loaded").asBoolean()).as(done.toString()).isTrue();
        String importId = done.path("bi").path("importId").asText();
        assertThat(importId).isNotBlank();
        JsonNode ds = read("/api/v1/bi/items/pk-lakepack-dataset-lake-items");
        assertThat(ds.path("status").asText()).isEqualTo("published");
        assertThat(ds.path("publishedVersion").asInt()).isEqualTo(1);
        assertThat(ds.path("workspace").asText()).isEqualTo("packs");
        assertThat(ds.path("audience").path("packs").toString()).contains("lakepack");
        assertThat(ds.path("tags").toString()).contains("pack:lakepack");
        assertThat(ds.path("endorsement").asText()).isEqualTo("none");
        assertThat(read("/api/v1/bi/items/pk-lakepack-report-lake-items-report").path("title").asText()).isEqualTo("Lake items report");
        assertThat(read("/api/v1/bi/imports/" + importId).path("source").asText()).isEqualTo("pack lakepack 1.0.1");
    }

    @Test @Order(2)
    void aLaterVersionUpdatesTheItemsAsNewPublishedVersionsAndAnUnchangedOneStaysUnchanged() throws Exception {
        String more = DATASET.replace("columns: { code: string, shape: string }", "columns: { code: string, shape: string, colour: string }");
        JsonNode up = upload(TestArchives.bundle("lakepack", "1.0.2", files("1.0.2", more)));
        assertThat(up.path("bi").path("counts").path("update").asInt()).isEqualTo(1);              // the dataset changed
        assertThat(up.path("bi").path("counts").path("unchanged").asInt()).isEqualTo(1);           // the report did not
        JsonNode done = confirm(up.path("uploadId").asText());
        assertThat(done.path("bi").path("loaded").asBoolean()).as(done.toString()).isTrue();
        assertThat(read("/api/v1/bi/items/pk-lakepack-dataset-lake-items").path("publishedVersion").asInt()).isEqualTo(2);
        assertThat(read("/api/v1/bi/items/pk-lakepack-report-lake-items-report").path("publishedVersion").asInt()).isEqualTo(1);
        // roll that import back: the dataset is version 1 again
        mvc.perform(post("/api/v1/bi/imports/" + done.path("bi").path("importId").asText() + "/rollback").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        JsonNode back = read("/api/v1/bi/items/pk-lakepack-dataset-lake-items");
        assertThat(back.path("publishedVersion").asInt()).isEqualTo(1);
        assertThat(back.path("body").asText()).doesNotContain("colour");
    }

    @Test @Order(3)
    void aVersionThatWouldBreakSomeoneElsesPublishedReportIsHeldNotForced() throws Exception {
        // a report of somebody else, in another workspace, reads the pack's dataset by the field the next version drops
        mvc.perform(post("/api/v1/bi/workspaces").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Other Desk\"}")).andExpect(status().is2xxSuccessful());
        String other = "report: other-rep\ndataset: pk-lakepack-dataset-lake-items\npages:\n  - id: main\n    title: Main\n    visuals:\n"
                + "      - { id: bar, type: bar, title: By shape, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [items.shape], values: [Items] } }\n";
        JsonNode rep = JSON.readTree(mvc.perform(post("/api/v1/bi/workspaces/other-desk/items").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("kind", "report", "title", "Other report", "body", other)))).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/bi/items/" + rep.path("id").asText() + "/publish").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().is2xxSuccessful());

        String dropped = DATASET.replace("columns: { code: string, shape: string }", "columns: { code: string }");
        JsonNode up = upload(TestArchives.bundle("lakepack", "1.0.3", files("1.0.3", dropped)));
        assertThat(up.path("bi").toString()).contains("Other report");                              // the preview already says whom it breaks
        JsonNode done = confirm(up.path("uploadId").asText());
        assertThat(done.path("deployed").asText()).isEqualTo("1.0.3");                              // the pack itself is deployed
        assertThat(done.path("bi").path("loaded").asBoolean()).isFalse();
        assertThat(done.path("bi").path("problem").asText()).contains("Other report");
        assertThat(read("/api/v1/bi/items/pk-lakepack-dataset-lake-items").path("body").asText()).contains("shape");   // the dataset readers have is untouched
    }
}
