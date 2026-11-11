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
 * The economy module off (a content module: the world-economy and news-and-markets packs and the public sector's six packs, their
 * functions, and the World and Public sector lenses; docs/guides/PUBLIC_SECTOR_GUIDE.md): every one of its packs and their connectors
 * is gone, its functions are off and neither lens is offered, while the banking module (its beans, engines and API), capital markets
 * and the core reference data (GLEIF) are unaffected.
 */
@SpringBootTest(properties = {"drishti.modules.economy.enabled=false", "drishti.rachana.hot-reload=false",
        "drishti.packs.enabled=core,market-data,gleif,us-equities,world-economy,news-and-markets,world-development,central-banks,"
                + "national-statistics,oecd,official-news,gdelt-events",
        "drishti.sources.plugins.demo.enabled=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class EconomyModuleOffTest {

    static final List<String> PUBLIC_SECTOR = List.of("world-development", "central-banks", "national-statistics", "oecd", "official-news",
            "gdelt-events");
    static final List<String> PACKS = new ArrayList<>(List.of("world-economy", "news-and-markets"));

    static {
        PACKS.addAll(PUBLIC_SECTOR);
    }

    @Autowired AccessRegistry registry;
    @Autowired PackRegistry packs;
    @Autowired SourceRegistry sources;
    @Autowired EngineRegistry engines;
    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    @Test
    void everyPackOfItAndItsConnectorsAreGone() throws Exception {
        assertThat(registry.moduleOn("economy")).isFalse();
        List<String> loaded = packs.packs().stream().map(Pack::name).toList();
        assertThat(loaded).contains("core", "market-data", "gleif", "us-equities").doesNotContainAnyElementsOf(PACKS);
        List<String> theirs = new ArrayList<>();
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        for (String p : PUBLIC_SECTOR) {
            JsonNode pack = yaml.readTree(com.ash.drishti.api.PackFolders.of(Path.of(System.getProperty("drishti.packs.dir", "../../../config/packs")), p).resolve("pack.yaml").toFile());
            assertThat(pack.path("domain").asText()).as(p).isEqualTo("economy");
            pack.path("connectors").fieldNames().forEachRemaining(theirs::add);
        }
        assertThat(theirs).hasSize(6);
        assertThat(sources.connectorStatuses().keySet()).doesNotContainAnyElementsOf(theirs);
    }

    @Test
    void itsFunctionsAndBothLensesAreGoneAndTheOthersStay() {
        for (String w : List.of("ECON", "NEWS", "WDI", "WDIS", "CBANK", "CBSER", "NSTAT", "OECD", "OECDS", "PRESS", "PREL", "GDELT")) {
            assertThat(registry.functionOn(w)).as(w).isFalse();
        }
        for (String w : List.of("VAR", "EXP", "TRD", "USEQ", "LEI", "BI")) {
            assertThat(registry.functionOn(w)).as(w).isTrue();
        }
        assertThat(registry.lensOffered("publicsector")).isFalse();
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
