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

import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.registry.AccessRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The economy module on (as shipped) with the public sector's packs: the six load with their connectors (each reading worldlake or
 * newslake, which their feeds fill), their functions are on, the Public sector lens is offered with them, and the World lens carries the
 * free news (PRESS, GDELT).
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.enabled=false",
        "drishti.packs.enabled=core,world-economy,world-development,central-banks,national-statistics,oecd,official-news,gdelt-events"})
class PublicSectorLensTest {

    @Autowired AccessRegistry registry;
    @Autowired PackRegistry packs;
    @Autowired SourceRegistry sources;

    @Test
    void thePacksConnectorsFunctionsAndLensAreThere() {
        assertThat(registry.moduleOn("economy")).isTrue();
        assertThat(packs.packs().stream().map(Pack::name).toList()).containsAll(EconomyModuleOffTest.PUBLIC_SECTOR);
        assertThat(sources.connectorStatuses().keySet()).contains("development-store", "centralbank-store", "statistics-store", "oecd-store",
                "officialnews-store", "gdelt-store");
        for (String w : List.of("WDI", "WDIS", "CBANK", "CBSER", "NSTAT", "OECD", "OECDS", "PRESS", "PREL", "GDELT")) {
            assertThat(registry.functionOn(w)).as(w).isTrue();
        }
        assertThat(registry.lensOffered("publicsector")).isTrue();
        assertThat(registry.lensOffered("world")).isTrue();
    }
}
