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
package com.ash.drishti.server.bi;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackLoader;
import com.ash.drishti.packs.PackPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every BI item shipped in {@code config/bi} (datasets, models, reports, boards, notebooks, Python items) reads only what the seven
 * shipped packs serve: each {@code source} kind is a kind of a shipped pack, each named connector is one a shipped pack defines, and
 * each pack-generated dataset ({@code pack.<pack>.<kind>}) names a shipped pack and one of its kinds. An example that still leans on a
 * retired pack (examples/packs) or a renamed one fails here, so it is moved or mended rather than left broken.
 */
class ShippedExamplesPacksTest {

    private static final Path BI = Path.of("..", "config", "bi");
    private static final Path PACKS = Path.of("..", "config", "packs");
    private static final Pattern PACK_DATASET = Pattern.compile("\\bpack\\.([a-z0-9-]+)\\.([a-z0-9-]+)");
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void everyShippedBiItemReadsOnlyWhatTheShippedPacksServe() throws IOException {
        List<Pack> packs = new PackLoader().load(PACKS.toAbsolutePath().normalize(), List.of(PackPaths.DEFAULT_ENABLED.split(",")));
        Map<String, List<String>> kindsByPack = new java.util.HashMap<>();
        Set<String> kinds = new HashSet<>();
        Set<String> connectors = new HashSet<>();
        for (Pack p : packs) {
            kindsByPack.put(p.name(), p.kinds());
            kinds.addAll(p.kinds());
            connectors.addAll(p.connectorRefs());
        }
        List<String> problems = new ArrayList<>();
        int sources = 0;
        try (Stream<Path> files = Files.walk(BI)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".yaml")).sorted().toList()) {
                String text = Files.readString(f);
                String name = BI.relativize(f).toString();
                List<JsonNode> found = new ArrayList<>();
                sourcesOf(yaml.readTree(text), found);
                for (JsonNode s : found) {
                    sources++;
                    if (s.hasNonNull("kind") && !kinds.contains(s.get("kind").asText())) {
                        problems.add(name + ": kind " + s.get("kind").asText() + " is in no shipped pack");
                    }
                    if (s.hasNonNull("connector") && !connectors.contains(s.get("connector").asText())) {
                        problems.add(name + ": connector " + s.get("connector").asText() + " is defined by no shipped pack");
                    }
                    if (s.hasNonNull("pack") && !kindsByPack.containsKey(s.get("pack").asText())) {
                        problems.add(name + ": pack " + s.get("pack").asText() + " is not shipped");
                    }
                }
                Matcher m = PACK_DATASET.matcher(text);
                while (m.find()) {
                    if (!kindsByPack.getOrDefault(m.group(1), List.of()).contains(m.group(2))) {
                        problems.add(name + ": " + m.group() + " is no shipped pack's kind");
                    }
                }
            }
        }
        assertThat(sources).as("data sources found in config/bi").isGreaterThan(10);
        assertThat(problems).as("shipped BI items that read a retired or renamed pack").isEmpty();
    }

    /** Every {@code source:} mapping in the item, at any depth (a dataset's, each table's, a report's own). */
    private static void sourcesOf(JsonNode n, List<JsonNode> out) {
        if (n == null) {
            return;
        }
        if (n.isObject()) {
            n.properties().forEach(e -> {
                if (e.getKey().equals("source") && e.getValue().isObject()) {
                    out.add(e.getValue());
                }
                sourcesOf(e.getValue(), out);
            });
        } else if (n.isArray()) {
            n.forEach(x -> sourcesOf(x, out));
        }
    }
}
