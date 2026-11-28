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
package com.ash.drishti.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.registry.AccessRegistry;
import com.ash.drishti.server.engines.EngineRegistry;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The external-data module off (a content module: the world-economy, world-development, national-statistics, oecd, central-banks, funds
 * and us-equities packs): its packs and their connectors do not load and its functions and lenses are off, while the news-and-events and
 * market-data modules (NEWS, CRV, FX, EQV stay on), the banking module (its beans, engines and API) and the reference data (GLEIF) are
 * unaffected.
 */
@SpringBootTest(properties = {"drishti.modules.external-data.enabled=false", "drishti.rachana.hot-reload=false",
        "drishti.packs.enabled=core,market-data,gleif,us-equities,funds,world-economy,world-development,central-banks,national-statistics,oecd,news-and-markets,official-news,gdelt-events", "drishti.sources.plugins.demo.enabled=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class ExternalDataModuleOffTest {

    static final List<String> PACKS = List.of("world-economy", "world-development", "central-banks", "national-statistics", "oecd", "funds",
            "us-equities");

    @Autowired AccessRegistry registry;
    @Autowired PackRegistry packs;
    @Autowired SourceRegistry sources;
    @Autowired EngineRegistry engines;
    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    @Test
    void itsPacksAndConnectorsAreGone() throws Exception {
        assertThat(registry.moduleOn("external-data")).isFalse();
        List<String> loaded = packs.packs().stream().map(Pack::name).toList();
        assertThat(loaded).contains("core", "market-data", "gleif", "news-and-markets", "official-news", "gdelt-events").doesNotContainAnyElementsOf(PACKS);
        List<String> theirs = new ArrayList<>();
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        for (String p : PACKS) {
            JsonNode pack = yaml.readTree(com.ash.drishti.api.resolution.ConfigResolution.repository(Path.of(System.getProperty("drishti.packs.dir", "../../../../config/packs")).toAbsolutePath().normalize().getParent().getParent()).packDir(p).resolve("pack.yaml").toFile());
            pack.path("connectors").fieldNames().forEachRemaining(theirs::add);
        }
        assertThat(theirs).isNotEmpty();
        assertThat(sources.connectorStatuses().keySet()).doesNotContainAnyElementsOf(theirs);
    }

    @Test
    void itsFunctionsAreOffAndTheOthersOn() {
        for (String w : List.of("USEQ", "EDGAR", "FUND", "HELD", "ECON", "WDI", "WDIS", "CBANK", "CBSER", "NSTAT", "OECD", "OECDS")) {
            assertThat(registry.functionOn(w)).as(w).isFalse();
        }
        for (String w : List.of("VAR", "EXP", "TRD", "LEI", "NEWS", "PRESS", "GDELT", "BI", "EQV", "CRV", "FX")) {
            assertThat(registry.functionOn(w)).as(w).isTrue();
        }
        assertThat(registry.lensOffered("markets")).isFalse();
        assertThat(registry.lensOffered("world")).isFalse();
        assertThat(registry.lensOffered("risk")).isTrue();
    }

    @Test
    void bankingIsUnaffected() throws Exception {
        assertThat(registry.moduleOn("banking")).isTrue();
        assertThat(engines.state("var")).isNotEqualTo(EngineRegistry.State.NOT_INSTALLED);
        String auth = "Bearer " + tokens.mint("rita", List.of("admin"), 300);
        assertThat(mvc.perform(get("/api/v1/risk/simm/versions").header("Authorization", auth)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
