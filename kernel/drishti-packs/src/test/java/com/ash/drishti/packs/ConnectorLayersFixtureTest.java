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
package com.ash.drishti.packs;

import com.ash.lake.tck.TestRoots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The connector layers against the shared fixture ({@code src/test/resources/connector-layers/cases.yaml}): the Python resolver
 * ({@code tools/lake/layers.py}) reads the same cases and must give the same effective connector. Also: nothing is written.
 */
class ConnectorLayersFixtureTest {

    static final Path CASES = Path.of(TestRoots.module(), "src", "test", "resources", "connector-layers", "cases.yaml");

    @TempDir
    Path dir;

    @Test
    @SuppressWarnings("unchecked")
    void everyCaseGivesTheEffectiveConnectorTheFixtureSays() throws Exception {
        Map<String, Object> doc = new ObjectMapper(new YAMLFactory()).readValue(CASES.toFile(), new TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> cases = (List<Map<String, Object>>) doc.get("cases");
        assertThat(cases).hasSizeGreaterThan(8);
        ConnectorFiles files = new ConnectorFiles(dir.resolve("connectors"), dir.resolve("data"));
        for (Map<String, Object> c : cases) {
            String name = String.valueOf(c.get("name"));
            String why = String.valueOf(c.get("why"));
            String text = String.valueOf(c.get("file"));
            Map<String, Object> template = c.get("template") == null ? null
                    : (Map<String, Object>) ((Map<String, Object>) doc.get("templates")).get(String.valueOf(c.get("template")));
            if (Boolean.TRUE.equals(c.get("error"))) {
                assertThatThrownBy(() -> ConnectorLayers.effective(files, name, text, template)).as(why).isInstanceOf(ConnectorFiles.InvalidFile.class);
                continue;
            }
            ConnectorLayers.Effective e = ConnectorLayers.effective(files, name, text, template);
            assertThat(e.layer()).as(why).isEqualTo(c.get("layer"));
            assertThat(e.superseded()).as(why).isEqualTo(c.get("superseded"));
            assertThat(e.definition().plugin()).as(why).isEqualTo(c.get("plugin"));
            assertThat(e.definition().enabled()).as(why).isEqualTo(String.valueOf(c.get("enabled")));
            Map<String, String> want = new LinkedHashMap<>();
            ((Map<String, Object>) c.get("settings")).forEach((k, v) -> want.put(k, String.valueOf(v)));
            assertThat(e.definition().settings()).as(why).isEqualTo(want);
            if (c.get("origins") instanceof Map<?, ?> o) {
                o.forEach((k, v) -> assertThat(e.origins().get(String.valueOf(k))).as(why + ": " + k).isEqualTo(v));
            }
        }
        assertThat(Files.exists(dir.resolve("connectors"))).as("the resolver writes nothing").isFalse();
        assertThat(Files.exists(dir.resolve("data"))).as("the resolver writes nothing").isFalse();
    }

    @Test
    void anOverrideHoldsOnlyTheKeysThatDiffer() throws Exception {
        ConnectorFiles files = new ConnectorFiles(dir.resolve("connectors"), dir.resolve("data"));
        Map<String, Object> t = Map.of("plugin", "delta", "settings", Map.of("lake", "demo", "lake-tables", "trading.trade_endur"));
        ConnectorFiles.Definition wanted = files.parse("trade-endur", "plugin: delta\nenabled: false\nsettings:\n  lake: demo\n  lake-tables: trading.t2\n");
        assertThat(ConnectorLayers.overrideOf(files, "trade-endur", t, wanted))
                .isEqualTo(Map.of("enabled", false, "settings", Map.of("lake-tables", "trading.t2")));
        ConnectorFiles.Definition same = files.parse("trade-endur", "plugin: delta\nsettings:\n  lake: demo\n  lake-tables: trading.trade_endur\n");
        assertThat(ConnectorLayers.overrideOf(files, "trade-endur", t, same)).isEmpty();
    }
}
