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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Admin, Packs, Data over HTTP ({@code POST /api/v1/admin/packs/{pack}/data}) on a scratch pack whose kind is routed to a File connector in the
 * test's own folder: a JSON-lines upload is written under its business date, and a bad date, a kind the pack does not load, an unknown pack
 * and a file with no ids are answered with a client error (a file's bad rows are counted, never a 500).
 */
@SpringBootTest(properties = {"drishti.sources.connectors-dir=${drishti.test.dir}/connectors/pack-data-api", "drishti.packs.dir=${drishti.test.dir}/pack-data/packs", "drishti.packs.installed-dir=${drishti.test.dir}/pack-data/installed",
        "drishti.packs.settings-dir=${drishti.test.dir}/pack-data/settings", "drishti.packs.overlay=${drishti.test.dir}/pack-data/added.yaml", "drishti.packs.deploy.history-file=${drishti.test.dir}/pack-data/history.jsonl",
        "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=datapack"})
@AutoConfigureMockMvc
class PackDataApiTest {

    static {
        com.ash.drishti.server.ConnectorFixtures.copy("pack-data-api");
    }

    static final Path ROOT;

    static {
        try {
            ROOT = TestRun.dir("pack-data");
            Path pack = Files.createDirectories(ROOT.resolve("packs/datapack"));
            Files.writeString(pack.resolve("pack.yaml"), "pack: datapack\nversion: 1.0.0\ntitle: Data pack\nkinds: [item]\n"
                    + "mnemonics:\n  ITM: {kind: item, label: Items}\nroutes:\n  item: data-store\ningest:\n  item:\n    key: id\n    snapshot: true\n");
            Files.createDirectories(ROOT.resolve("files"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired MockMvc mvc;

    private org.springframework.test.web.servlet.ResultActions load(String pack, String query, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/packs/" + pack + "/data" + query).contentType(MediaType.APPLICATION_OCTET_STREAM).content(body));
    }

    @Test
    void aJsonLinesUploadLandsUnderItsBusinessDate() throws Exception {
        load("datapack", "?kind=item&date=2026-10-05", "{\"id\":\"A\",\"n\":1}\n{\"id\":\"B\",\"n\":2}\n{\"n\":3}\n")
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("item")).andExpect(jsonPath("$.rows").value(2))
                .andExpect(jsonPath("$.rejected").value(1));
        try (var s = Files.walk(ROOT.resolve("files"))) {
            assertThat(s.filter(Files::isRegularFile).map(p -> p.toString()).toList()).anyMatch(p -> p.contains("2026-10-05"));
        }
    }

    @Test
    void malformedRequestsAreClientErrors() throws Exception {
        load("datapack", "?kind=item&date=yesterday", "{\"id\":\"A\"}\n").andExpect(status().isBadRequest());
        load("datapack", "?kind=nope&date=2026-10-05", "{\"id\":\"A\"}\n").andExpect(status().isBadRequest());
        load("no-such-pack", "?kind=item", "{\"id\":\"A\"}\n").andExpect(status().is4xxClientError());
    }
}
