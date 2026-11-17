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

import com.ash.drishti.testroots.TestRoots;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The site's engine folder holds the core's engine files and the modules' own, nothing else (the part of EngineCatalogueTest that reads a module). */
class ModuleEngineFilesTest {

    static final Path REPO = Path.of(TestRoots.repo()).toAbsolutePath().normalize();

    @Test
    void theSiteFolderHoldsTheCoresEngineFilesAndTheModulesOwn() throws Exception {
        EngineCatalogue c = EngineCatalogue.load();
        Path site = REPO.resolve("config/engines");
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        List<String> files = new java.util.ArrayList<>(c.all().stream().map(EngineCatalogue.Entry::file).toList());
        // a module's engines' files sit in the same site folder: named by its catalogue in its jar (drishti-banking's)
        yaml.readTree(REPO.resolve("modules/banking/java/banking-service/src/main/resources/" + EngineCatalogue.MODULE_RESOURCE).toFile()).path("engines")
                .forEach(n -> files.add(n.path("file").asText(n.path("id").asText() + ".yaml")));
        try (var listed = Files.list(site)) {
            assertThat(listed.map(f -> f.getFileName().toString()).sorted().toList()).as("a file without an engine")
                    .containsExactlyInAnyOrderElementsOf(files);
        }
    }
}
