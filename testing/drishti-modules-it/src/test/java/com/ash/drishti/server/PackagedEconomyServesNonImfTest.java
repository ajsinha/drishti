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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What a real install shows of the world-economy pack: the economy module's package leaves the IMF-derived samples out (content.yaml
 * {@code restricted:}), and still serves every other sample by its id (the packer ships the samples' catalogue without the restricted kinds).
 * No internal fixture here: this is the package alone.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=world-economy"})
@AutoConfigureMockMvc
class PackagedEconomyServesNonImfTest {

    @Autowired MockMvc mvc;

    @Test
    void aJurisdictionAndOtherNonImfSamplesAreServedByTheirIds() throws Exception {
        for (String url : new String[] {"jurisdiction/JUR-AUS", "region/REGN-EAS", "social-indicator/" + firstSocial()}) {
            assertThat(mvc.perform(get("/api/v1/views/" + url)).andReturn().getResponse().getStatus()).as(url).isEqualTo(200);
        }
    }

    @Test
    void theImfSamplesAreNotInTheInstall() throws Exception {
        for (String url : new String[] {"economy/ECON-AUS", "macro-indicator/MACRO-AUS-GDP-GROWTH", "fiscal-position/FISC-USA"}) {
            assertThat(mvc.perform(get("/api/v1/views/" + url)).andReturn().getResponse().getStatus()).as(url).isEqualTo(404);
        }
    }

    private static String firstSocial() throws Exception {
        try (var lines = java.nio.file.Files.lines(java.nio.file.Path.of(com.ash.lake.tck.TestRoots.repo(), "modules", "external-data", "packs", "world-economy", "samples", "catalog.jsonl"))) {
            return lines.filter(l -> l.contains("\"kind\":\"social-indicator\"")).findFirst().map(l -> l.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1")).orElseThrow();
        }
    }
}
