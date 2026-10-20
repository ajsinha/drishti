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
package com.ash.drishti.server.design;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.engine.history.HistorySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The shipped packs' {@code history:} blocks (HISTORY_QUERIES.md section 6.3) have no problem, and every column, chart field and series
 * they name is a field of the kind's sample documents, so no shipped history shows an empty column by a typo.
 */
class ShippedHistoryBlocksTest {

    private static final Path PACKS = Path.of("..", "config", "packs");

    @Test
    void everyShippedBlockIsCleanAndNamesRealFields() throws IOException {
        List<String> found = new ArrayList<>();
        int blocks = 0;
        try (Stream<Path> dirs = Files.list(PACKS)) {
            for (Path dir : dirs.sorted().toList()) {
                Path manifest = dir.resolve("pack.yaml");
                if (!Files.isRegularFile(manifest)) {
                    continue;
                }
                Map<String, Object> man = new Yaml().load(Files.readString(manifest));
                if (!(man.get("history") instanceof Map<?, ?> history)) {
                    continue;
                }
                List<String> problems = HistoryDefinitions.check(man, 366);
                problems.forEach(p -> found.add(dir.getFileName() + ": " + p));
                for (var e : history.entrySet()) {
                    String kind = String.valueOf(e.getKey());
                    HistorySpec spec = HistorySpec.parse(kind, e.getValue(), 366, new ArrayList<>());
                    blocks++;
                    Path sample = dir.resolve("samples").resolve(kind + ".jsonl");
                    if (!spec.enabled() || !Files.isRegularFile(sample)) {
                        continue;
                    }
                    JsonNode doc = new ObjectMapper().readTree(Files.readAllLines(sample).get(0));
                    List<String> named = new ArrayList<>();
                    spec.columns().stream().filter(c -> c.path() != null).forEach(c -> named.add(c.path()));
                    spec.chart().stream().filter(c -> c.path() != null).forEach(c -> named.add(c.path()));
                    if (spec.parts() != null) {
                        named.addAll(spec.parts());
                    }
                    if (spec.series() != null) {
                        named.add(spec.series().path());
                    }
                    for (String path : named) {
                        if (doc.path(path).isMissingNode()) {
                            found.add(dir.getFileName() + ": history." + kind + " names " + path + ", which its sample does not have");
                        }
                    }
                }
            }
        }
        assertThat(found).isEmpty();
        assertThat(blocks).isGreaterThanOrEqualTo(15);
    }
}
