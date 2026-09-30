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

import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.api.EntityRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The generated domain packs load with the packs they require, and every example command they advertise opens a
 * view built by the pack's Sutra with every panel filled.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics"})
class DomainPacksTest {

    @Autowired ViewPipeline pipeline;
    @Autowired com.ash.drishti.engine.command.CommandParser commands;

    @Test
    void everyAdvertisedExampleOpensAFullView() throws Exception {
        for (String pack : new String[] {"liquidity-risk", "climate-risk", "operational-risk", "retail-banking", "genomics", "politics-society", "economics"}) {
            JsonNode manifest = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(
                    Files.readString(Path.of("../packs", pack, "pack.yaml")));
            for (JsonNode ex : manifest.path("console").path("examples")) {
                EntityRef ref = commands.require(ex.get(0).asText());
                ViewModel v = pipeline.view(ref);
                assertThat(v.provenance().layout()).as(ref.toString()).startsWith("Sutra ");
                assertThat(v.panels()).as(ref.toString()).allSatisfy(p -> assertThat(p.empty()).as(ref + "/" + p.id()).isFalse());
                assertThat(links(v)).as(ref + " links").allSatisfy(l -> assertThat(l.status()).as(ref + " -> " + l.text()).isNotEqualTo("missing"));
            }
        }
    }

    @Test
    void aLinkFieldMeansOneKindAcrossPacks() {
        ViewModel v = pipeline.view(commands.require("CST CST-DELAYED-COMM"));
        assertThat(links(v)).filteredOn(l -> l.text().equals("NGFS-DELAYED")).singleElement()
                .satisfies(l -> assertThat(l.link().kind()).isEqualTo("climate-scenario"));
    }

    private static java.util.List<PanelData.LinkItem> links(ViewModel v) {
        return v.panels().stream().filter(p -> p.data() instanceof PanelData.Links).flatMap(p -> ((PanelData.Links) p.data()).links().stream()).toList();
    }
}
