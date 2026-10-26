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
 * The shipped packs (the default drishti.packs.enabled) load together, and every example command they advertise opens a view built
 * by the pack's Sutra with every panel filled and no missing link. news-and-markets is the exception: its pages read two on-demand
 * internet connectors that are off as shipped, so its examples only have to open by their Sutra.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=" + com.ash.drishti.packs.PackPaths.DEFAULT_ENABLED})
class DomainPacksTest {

    @Autowired ViewPipeline pipeline;
    @Autowired com.ash.drishti.engine.command.CommandParser commands;

    /** Panels the exposure engine fills from the lake's paths (2.9.0): empty in the demo, which has no lake; ExposureApiTest covers them. */
    private static final java.util.Set<String> ENGINE_PANELS = java.util.Set.of("netting-set/exposure",
            // CORR's correlations (2.9.2): the reactive correlation-engine answers after the view is drawn (the panels fill in place)
            "correlation-matrix/average", "correlation-matrix/pairs", "correlation-matrix/factors", "correlation-matrix/stamp", "equity/beta");

    @Test
    void everyAdvertisedExampleOpensAFullView() throws Exception {
        int opened = 0;
        for (String pack : com.ash.drishti.packs.PackPaths.DEFAULT_ENABLED.split(",")) {
            JsonNode manifest = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(
                    Files.readString(Path.of("../config/packs", pack, "pack.yaml")));
            for (JsonNode ex : manifest.path("console").path("examples")) {
                EntityRef ref = commands.require(ex.get(0).asText());
                if (ref.kind().equals("news") || ref.kind().equals("quote")) {
                    continue;                                      // served only by the internet connectors, off as shipped
                }
                if (!demoServes(pack, ref.kind())) {
                    continue;                                      // lake only (the VaR kinds): test_var_explorer_browser.py opens them, demo off
                }
                ViewModel v = pipeline.view(ref);
                opened++;
                assertThat(v.provenance().layout()).as(ref.toString()).startsWith("Sutra ");
                if (pack.equals("news-and-markets")) {
                    continue;                                      // live headlines and quotes: off as shipped
                }
                assertThat(v.panels()).as(ref.toString())
                        .filteredOn(p -> !ENGINE_PANELS.contains(ref.kind() + "/" + p.id()))
                        .allSatisfy(p -> assertThat(p.empty()).as(ref + "/" + p.id()).isFalse());
                assertThat(links(v)).as(ref + " links").allSatisfy(l -> assertThat(l.status()).as(ref + " -> " + l.text()).isNotEqualTo("missing"));
            }
        }
        assertThat(opened).as("examples opened").isGreaterThan(20);
    }

    /** A core counterparty links across packs: its country of risk to the world-economy pack's economy, its agreement to counterparty-risk. */
    @Test
    void aCounterpartyLinksToItsEconomyAndItsAgreementInOtherPacks() {
        ViewModel v = pipeline.view(commands.require("CPTY CP-NORTHBRIDGE"));
        assertThat(links(v)).filteredOn(l -> l.link() != null && "economy".equals(l.link().kind())).isNotEmpty()
                .allSatisfy(l -> assertThat(l.status()).isNotEqualTo("missing"));
    }

    /** The pack's samples catalogue lists the kind, so this test's demo connector serves it (the VaR kinds keep reference samples only). */
    private static boolean demoServes(String pack, String kind) throws java.io.IOException {
        Path catalog = Path.of("../config/packs", pack, "samples", "catalog.json");
        if (!Files.isRegularFile(catalog)) {
            return false;
        }
        for (JsonNode e : new ObjectMapper().readTree(catalog.toFile())) {
            if (kind.equals(e.path("kind").asText())) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<PanelData.LinkItem> links(ViewModel v) {
        return v.panels().stream().filter(p -> p.data() instanceof PanelData.Links).flatMap(p -> ((PanelData.Links) p.data()).links().stream()).toList();
    }
}
