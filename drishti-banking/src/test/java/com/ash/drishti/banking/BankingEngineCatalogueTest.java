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

import com.ash.drishti.server.engines.EngineCatalogue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The banking module owns its engines (docs/architecture/DOMAIN_MODULES.md): their entries are its jar's META-INF/drishti/engines.yaml,
 * read before the core's catalogue, their files its jar's engines/<file> (the site's config/engines/<file> byte for byte), and the console's
 * copy is its plugin's engines.yaml, the same bytes.
 */
class BankingEngineCatalogueTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @Test
    void theModulesEnginesComeFirstWithTheirNeedsAndPaths() {
        EngineCatalogue c = EngineCatalogue.load();
        assertThat(c.all()).extracting(EngineCatalogue.Entry::id).containsExactly("var", "exposure", "sensitivities", "frtb-sa", "simm",
                "mr-cube", "memory-lake", "dataload", "pivot", "flight", "openblas");
        assertThat(c.neededBy("pivot")).containsExactly("mr-cube");
        assertThat(c.get("frtb-sa").orElseThrow().needs()).containsExactly("var", "sensitivities");
        assertThat(c.get("simm").orElseThrow().needs()).containsExactly("sensitivities");
        assertThat(c.neededBy("exposure")).isEmpty();
        assertThat(c.forPath("/api/v1/risk/frtb/sa").orElseThrow().id()).isEqualTo("frtb-sa");
        assertThat(c.forPath("/api/v1/risk/sensitivities").orElseThrow().id()).isEqualTo("sensitivities");
        assertThat(c.forPath("/api/v1/variance")).isEmpty();
        assertThat(c.forPath("/api/v1/risk/var/explain").orElseThrow().id()).isEqualTo("var");
    }

    @Test
    void itsEngineFilesAreShippedTwiceByteForByteAndTheConsoleCopyIsTheSame() throws Exception {
        Path jar = REPO.resolve("drishti-banking/src/main/resources");
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        var catalogue = yaml.readTree(jar.resolve(EngineCatalogue.MODULE_RESOURCE).toFile()).path("engines");
        assertThat(catalogue.size()).isEqualTo(6);
        for (var n : catalogue) {
            String id = n.path("id").asText();
            String file = n.path("file").asText(id + ".yaml");
            assertThat(Files.readAllBytes(REPO.resolve("config/engines").resolve(file))).as(file).isEqualTo(Files.readAllBytes(jar.resolve("engines").resolve(file)));
            var doc = yaml.readTree(jar.resolve("engines").resolve(file).toFile());
            assertThat(doc.path("drishti").path("engines").path(id).path("enabled").asText()).as(id)
                    .isEqualTo("${" + n.path("env").asText() + ":" + n.path("shipped").asBoolean() + "}");
        }
        assertThat(Files.readAllBytes(REPO.resolve("drishti-console/plugins/banking/engines.yaml")))
                .isEqualTo(Files.readAllBytes(jar.resolve(EngineCatalogue.MODULE_RESOURCE)));
    }
}
