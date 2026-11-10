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
package com.ash.drishti.server.engines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The engine catalogue's build test (ADMIN_GUIDE.md sections 2 and 3.1): every engine with its file in config/engines and the same bytes
 * inside the jar, the console's copy of the catalogue identical, no cycle of needs, every word a word, the API prefixes routed to the
 * right engine (the longest prefix: the sensitivities' endpoints are not FRTB-SA's).
 */
class EngineCatalogueTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @Test
    void theProductsCatalogueHoldsTheElevenEnginesInOrderWithTheirLinks() {
        EngineCatalogue c = EngineCatalogue.load();                   // the core's engines: the banking module's are in its jar (BankingEngineCatalogueTest)
        assertThat(c.all()).extracting(EngineCatalogue.Entry::id).containsExactly("memory-lake", "dataload", "pivot", "flight", "openblas");
        assertThat(c.get("pivot").orElseThrow().liveOn()).isTrue();
        assertThat(c.get("memory-lake").orElseThrow().liveOn()).isFalse();
        assertThat(c.all().stream().filter(e -> !e.installed()).map(EngineCatalogue.Entry::id)).isEmpty();   // every listed engine ships
        assertThat(c.forPath("/api/v1/risk/var/explain")).isEmpty();
        for (EngineCatalogue.Entry e : c.all()) {
            assertThat(e.list("words")).allMatch(w -> w.matches("[A-Z][A-Z0-9]*( [A-Z0-9]+)?"));
            assertThat(e.switchKey()).isEqualTo("drishti.engines." + e.id() + ".enabled");
        }
    }

    @Test
    void aCycleOfNeedsOrAnUnknownEngineIsRefused() {
        assertThatThrownBy(() -> new EngineCatalogue(List.of(entry("a", "b"), entry("b", "a")))).hasMessageContaining("cycle");
        assertThatThrownBy(() -> new EngineCatalogue(List.of(entry("a", "zz")))).hasMessageContaining("not in the catalogue");
    }

    @Test
    void everyEngineFileIsShippedTwiceByteForByteAndTheConsoleCopyIsTheSame() throws Exception {
        EngineCatalogue c = EngineCatalogue.load();
        Path jar = REPO.resolve("drishti-server/src/main/resources/engines");
        Path site = REPO.resolve("config/engines");
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        for (EngineCatalogue.Entry e : c.all()) {
            assertThat(Files.readAllBytes(site.resolve(e.file()))).as(e.file()).isEqualTo(Files.readAllBytes(jar.resolve(e.file())));
            var doc = yaml.readTree(jar.resolve(e.file()).toFile());
            assertThat(doc.path("drishti").path("engines").path(e.id()).path("enabled").asText()).as(e.id())
                    .isEqualTo("${" + e.env() + ":" + e.shipped() + "}");
        }
        List<String> files = new java.util.ArrayList<>(c.all().stream().map(EngineCatalogue.Entry::file).toList());
        // a module's engines' files sit in the same site folder: named by its catalogue in its jar (drishti-banking's)
        yaml.readTree(REPO.resolve("modules/banking/banking-service/src/main/resources/" + EngineCatalogue.MODULE_RESOURCE).toFile()).path("engines")
                .forEach(n -> files.add(n.path("file").asText(n.path("id").asText() + ".yaml")));
        try (var listed = Files.list(site)) {
            assertThat(listed.map(f -> f.getFileName().toString()).sorted().toList()).as("a file without an engine")
                    .containsExactlyInAnyOrderElementsOf(files);
        }
        assertThat(Files.readAllBytes(REPO.resolve("drishti-console/config/engines.yaml")))
                .isEqualTo(Files.readAllBytes(jar.resolve("catalogue.yaml")));
    }

    static EngineCatalogue.Entry entry(String id, String... needs) {
        return new EngineCatalogue.Entry(id, id.toUpperCase(), "", id + ".yaml", "drishti.engines." + id + ".enabled", "", List.of(), true, true, true,
                Map.of("needs", List.of(needs)));
    }
}
