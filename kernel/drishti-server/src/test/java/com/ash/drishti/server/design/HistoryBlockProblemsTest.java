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
package com.ash.drishti.server.design;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ash.drishti.server.PackReloader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * QA2-06: a bad {@code history:} block in a pack on disk shows on Admin &rarr; Packs and Health from the start, and again after a reload, not
 * only at the first history request; fixing it on disk and reloading clears it. The pack itself keeps running (its kinds keep the default
 * history).
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=histpack"})
@AutoConfigureMockMvc
class HistoryBlockProblemsTest {

    static Path root;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BAD = "history:\n  item:\n    chart: [PRICE]\n    headline: PRICE2\n";
    private static final String[] SYSTEM_PROPERTIES = {"drishti.packs.dir", "drishti.packs.installed-dir", "drishti.packs.overlay", "drishti.packs.settings-dir",
        "drishti.sources.connectors-dir"};
    private static final Map<String, String> BEFORE = new HashMap<>();

    static String packYaml(String history) {
        return "pack: histpack\nversion: 1.0.0\ntitle: History pack\nkinds: [item]\n"
                + "connectors:\n  item-store:\n    plugin: file\n    kinds: [item]\n    settings:\n      root: " + root.resolve("files") + "\n"
                + "routes:\n  item: item-store\n" + history;
    }

    static {
        for (String k : SYSTEM_PROPERTIES) {
            BEFORE.put(k, System.getProperty(k));
        }
        try {
            root = com.ash.drishti.server.TestRun.temp("drishti-histblock");
            Path pack = Files.createDirectories(root.resolve("packs/histpack"));
            Files.createDirectories(pack.resolve("sutras"));
            Files.createDirectories(root.resolve("files/2026-10-02"));
            Files.writeString(root.resolve("files/2026-10-02/item.jsonl"), "{\"id\":\"I-1\",\"PRICE\":1.5}\n");
            Files.writeString(pack.resolve("pack.yaml"), packYaml(BAD));
            System.setProperty("drishti.packs.dir", root.resolve("packs").toString());
            System.setProperty("drishti.packs.installed-dir", root.resolve("installed").toString());
            System.setProperty("drishti.packs.overlay", root.resolve("added.yaml").toString());
            System.setProperty("drishti.packs.settings-dir", root.resolve("settings").toString());
            System.setProperty("drishti.sources.connectors-dir", root.resolve("connectors").toString());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void forgetTheFolders() throws Exception {
        for (String k : SYSTEM_PROPERTIES) {
            if (BEFORE.get(k) == null) {
                System.clearProperty(k);
            } else {
                System.setProperty(k, BEFORE.get(k));
            }
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Autowired MockMvc mvc;
    @Autowired PackReloader reloader;

    JsonNode read(String path) throws Exception {
        var r = mvc.perform(get(path)).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return JSON.readTree(r.getContentAsString());
    }

    JsonNode packRow(JsonNode rows) {
        for (JsonNode r : rows) {
            if ("histpack".equals(r.path("name").asText())) {
                return r;
            }
        }
        throw new AssertionError("no histpack row in " + rows);
    }

    void seenOnBothPages() throws Exception {
        JsonNode admin = packRow(read("/api/v1/admin/packs"));
        assertThat(admin.path("problem").asText()).contains("history block").contains("PRICE2 is not one of chart");
        assertThat(admin.path("historyProblems")).hasSize(1);
        JsonNode health = read("/api/v1/admin/health");
        assertThat(health.path("summary").path("packsWithProblems").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(packRow(health.path("packs")).path("status").asText()).isEqualTo("DEGRADED");
        assertThat(packRow(health.path("packs")).path("problem").asText()).contains("PRICE2");
    }

    @Test
    void aBadHistoryBlockShowsAtStartAndAfterAReloadAndAFixClearsIt() throws Exception {
        seenOnBothPages();                                                     // at start, before any history request
        Path manifest = root.resolve("packs/histpack/pack.yaml");
        Files.writeString(manifest, packYaml("history:\n  item:\n    chart: [PRICE]\n    headline: PRICE\n"));
        reloader.reconcile("test", "fixed on disk", Set.of("histpack"));
        JsonNode fixed = packRow(read("/api/v1/admin/packs"));
        assertThat(fixed.path("problem").isNull()).as(fixed.toString()).isTrue();
        assertThat(packRow(read("/api/v1/admin/health").path("packs")).path("problem").isNull()).isTrue();
        Files.writeString(manifest, packYaml(BAD));                           // broken again: shows after the reload
        reloader.reconcile("test", "broken on disk", Set.of("histpack"));
        seenOnBothPages();
    }
}
