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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every kind of every shipped pack is served with the demo connector off, as the user's cluster runs: by a Delta connector of the pack,
 * enabled by default, whose table {@code drishti.py lake demo} builds from a dataset of {@code data/testdata/testdata.yaml} (its pack,
 * kind and connector) whose files are committed; or by the pack's derived connector (the trading pack's pages). The demo connector
 * never counts. Exempt: {@code us-equities/price-history}, live prices that are internal only and ship no data.
 */
class PacksServedWithoutDemoTest {

    static final Path PACKS = Path.of("..", "config", "packs");
    static final Path TESTDATA = Path.of("..", "data", "testdata");
    static final Set<String> EXEMPT = Set.of("us-equities/price-history");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void everyKindOfEveryPackIsServedByANonDemoConnector() throws IOException {
        JsonNode manifest = YAML.readTree(TESTDATA.resolve("testdata.yaml").toFile());
        Map<String, JsonNode> datasets = new java.util.HashMap<>();
        for (JsonNode ds : manifest.path("datasets")) {
            datasets.put(ds.path("pack").asText() + "/" + ds.path("kind").asText() + "/" + ds.path("connector").asText(), ds);
        }
        List<String> unserved = new ArrayList<>();
        Set<String> packs = new HashSet<>();
        try (Stream<Path> dirs = Files.list(PACKS)) {
            for (Path dir : dirs.filter(d -> Files.isRegularFile(d.resolve("pack.yaml"))).sorted().toList()) {
                String pack = dir.getFileName().toString();
                packs.add(pack);
                JsonNode p = YAML.readTree(dir.resolve("pack.yaml").toFile());
                for (JsonNode k : p.path("kinds")) {
                    String kind = k.asText();
                    if (!EXEMPT.contains(pack + "/" + kind) && !served(pack, kind, p.path("connectors"), datasets)) {
                        unserved.add(pack + "/" + kind);
                    }
                }
            }
        }
        assertThat(packs).contains("core", "trading", "us-equities", "gleif", "world-economy");
        assertThat(unserved).as("kinds no non-demo connector serves (PACK_DEVELOPER_GUIDE: never rely on the demo connector)").isEmpty();
    }

    /** True when a derived or reactive connector, or a Delta connector on by default with committed test data, serves the kind. */
    private static boolean served(String pack, String kind, JsonNode connectors, Map<String, JsonNode> datasets) {
        for (Map.Entry<String, JsonNode> e : connectors.properties()) {
            JsonNode c = e.getValue();
            JsonNode kinds = c.path("kinds");
            boolean lists = kinds.isObject() ? kinds.has(kind) : false;          // a reactive connector's kinds are a mapping
            for (JsonNode k : kinds.isArray() ? kinds : com.fasterxml.jackson.databind.node.MissingNode.getInstance()) {
                lists |= kind.equals(k.asText());
            }
            String plugin = c.path("plugin").asText();
            if (!lists || "demo".equals(plugin)) {
                continue;
            }
            if ("derived".equals(plugin) || "reactive".equals(plugin) || "var-positions".equals(plugin)) {   // computed from other kinds (the
                // positions Drishti derives from the P&L vectors' trades), never from the demo
                return true;
            }
            JsonNode ds = datasets.get(pack + "/" + kind + "/" + e.getKey());
            if ("delta".equals(plugin) && onByDefault(c.path("enabled")) && ds != null && committed(ds, kind)) {
                return true;
            }
        }
        return false;
    }

    /** {@code enabled: ${DRISHTI_X:false}} or {@code false} is off by default; anything else (or nothing) is on. */
    private static boolean onByDefault(JsonNode enabled) {
        String v = enabled.isMissingNode() ? "true" : enabled.asText().trim();
        return !(v.equals("false") || v.endsWith(":false}"));
    }

    /** The dataset's files are in the repository: its snapshot, its folder of days, or the pack samples of the kind. */
    private static boolean committed(JsonNode ds, String kind) {
        if (ds.has("samples")) {
            return Files.isRegularFile(Path.of("..").resolve(ds.path("samples").asText()).resolve(kind + ".jsonl"));
        }
        Path folder = TESTDATA.resolve(ds.path("folder").asText());
        if (ds.has("snapshot")) {
            return Files.isRegularFile(folder.resolve(ds.path("snapshot").asText()));
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith(".jsonl.gz"));
        } catch (IOException e) {
            return false;
        }
    }
}
