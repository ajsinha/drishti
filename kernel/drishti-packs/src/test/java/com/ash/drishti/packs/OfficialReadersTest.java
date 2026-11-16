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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The BI readers of the official results follow the format they are published in (2.9.5): with {@code drishti.var.official.format:
 * iceberg} the {@code var-results} file generated at start says {@code plugin: iceberg} over the official root (and its {@code s3.*}
 * settings); the exposure reader follows {@code drishti.ccr.exposure.official.format} on its own; back to Delta, the file never edited
 * follows again, an edited one is kept. Other connectors are never touched.
 */
class OfficialReadersTest {

    private static final String PACK = "pack: risk\nkinds: [var-official, exposure-official-cpty, pnl-vector]\nroutes:\n"
            + "  var-official: var-results\n  exposure-official-cpty: exposure-results\n  pnl-vector: risk-store\nconnectors:\n"
            + "  var-results:\n    plugin: delta\n    kinds: [var-official]\n    settings:\n      root: ${DRISHTI_DELTA_ROOT:./data/delta}\n"
            + "      domain: risk-results\n      layout:\n        var-official:\n          columns: [HIERARCHY, NODE_ID]\n"
            + "  exposure-results:\n    plugin: delta\n    kinds: [exposure-official-cpty]\n    settings:\n      root: ${DRISHTI_DELTA_ROOT:./data/delta}\n"
            + "      domain: risk-results\n"
            + "  risk-store:\n    plugin: delta\n    kinds: [pnl-vector]\n    settings:\n      root: ./data/delta\n      domain: risk\n";

    private static List<Pack> packs(Path dir) throws Exception {
        Path p = Files.createDirectories(dir.resolve("packs/risk"));
        Files.writeString(p.resolve("pack.yaml"), PACK);
        return new PackLoader().load(dir.resolve("packs"), List.of("risk"));
    }

    private static UnaryOperator<Map<String, Object>> server(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return PackEnvironmentPostProcessor.officialReaders(env);
    }

    private static ConnectorFiles.Definition effective(Map<String, ConnectorLayers.Template> t, ConnectorFiles files, String name) {
        return files.parse(name, ConnectorLayers.templateText(files, name, t.get(name).definition(), ""));
    }

    @Test
    void theVarReaderFollowsTheFormatInMemoryAndNothingIsWritten(@TempDir Path dir) throws Exception {
        List<Pack> packs = packs(dir);
        ConnectorFiles files = new ConnectorFiles(dir.resolve("connectors"));
        Map<String, ConnectorLayers.Template> t = ConnectorLayers.templates(packs, server(Map.of("drishti.var.official.format", "iceberg",
                "drishti.var.official.root", "s3://bank-lake/risk", "drishti.var.official.storage.s3.region", "us-east-1")));
        ConnectorFiles.Definition v = effective(t, files, "var-results");
        assertThat(v.plugin()).isEqualTo("iceberg");
        assertThat(v.settings()).containsEntry("root", "${drishti.var.official.root:${DRISHTI_DELTA_ROOT:./data/delta}}")
                .containsEntry("engine", "native").containsEntry("domain", "risk-results")
                .containsEntry("s3.region", "${drishti.var.official.storage.s3.region}").containsKey("layout.var-official.columns");
        assertThat(effective(t, files, "exposure-results").plugin()).as("the exposure format is its own").isEqualTo("delta");
        assertThat(effective(t, files, "risk-store").plugin()).isEqualTo("delta");
        Map<String, ConnectorLayers.Template> back = ConnectorLayers.templates(packs, server(Map.of()));   // the format back to Delta
        assertThat(effective(back, files, "var-results").plugin()).isEqualTo("delta");
        assertThat(effective(back, files, "var-results").settings()).containsEntry("root", "${DRISHTI_DELTA_ROOT:./data/delta}").doesNotContainKey("engine");
        assertThat(Files.exists(dir.resolve("connectors"))).as("no connector file is written").isFalse();
    }

    @Test
    void theExposureReaderFollowsItsOwnFormatAndASiteOverrideIsMergedOverIt(@TempDir Path dir) throws Exception {
        List<Pack> packs = packs(dir);
        ConnectorFiles files = new ConnectorFiles(dir.resolve("connectors"));
        Map<String, ConnectorLayers.Template> t = ConnectorLayers.templates(packs, server(Map.of("drishti.ccr.exposure.official.format", "iceberg")));
        assertThat(effective(t, files, "exposure-results").plugin()).isEqualTo("iceberg");
        assertThat(effective(t, files, "var-results").plugin()).isEqualTo("delta");
        files.write("var-results", "settings:\n  refresh-seconds: 30\n");
        Map<String, String> problems = new java.util.LinkedHashMap<>();
        ConnectorFiles.Definition v = ConnectorLayers.resolve(t, files, problems).get("var-results");
        assertThat(problems).isEmpty();
        assertThat(v.plugin()).isEqualTo("delta");
        assertThat(v.settings()).containsEntry("refresh-seconds", "30").containsEntry("domain", "risk-results");
    }

    @Test
    void onlyAReadOnlyDeltaReaderOfOfficialKindsIsAdjusted() {
        Map<String, Object> reader = Map.of("plugin", "delta", "kinds", List.of("var-official"), "settings", Map.of("domain", "risk-results"));
        Map<String, Object> other = Map.of("plugin", "delta", "kinds", List.of("pnl-vector"), "settings", Map.of("domain", "risk"));
        Map<String, Object> notReader = Map.of("plugin", "delta", "kinds", List.of("var-official"), "settings", Map.of("domain", "risk"));
        UnaryOperator<Map<String, Object>> ice = server(Map.of("drishti.var.official.format", "ICEBERG"));
        assertThat(ice.apply(reader).get("plugin")).isEqualTo("iceberg");
        assertThat(ice.apply(other)).isSameAs(other);
        assertThat(ice.apply(notReader)).isSameAs(notReader);
        assertThat(server(Map.of("drishti.var.official.format", "delta")).apply(reader)).isSameAs(reader);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReaderInTheLakeFormBecomesIcebergAndKeepsItsLake() {
        // the lake form (docs/guides/DATA_LAKE_GUIDE.md): config/lakes/banking/var-official.yaml takes its root, format and storage from the
        // publisher's settings, so the reader only changes plugin; nothing of the root is written into it
        Map<String, Object> reader = new java.util.LinkedHashMap<>(Map.of("plugin", "delta", "kinds", List.of("var-official"),
                "settings", Map.of("lake", "var-official", "lake-tables", "risk-results.var-official")));
        Map<String, Object> iceberg = server(Map.of("drishti.var.official.format", "iceberg")).apply(reader);
        assertThat(iceberg.get("plugin")).isEqualTo("iceberg");
        assertThat((Map<String, Object>) iceberg.get("settings")).containsEntry("lake", "var-official").containsEntry("engine", "native")
                .doesNotContainKey("root").doesNotContainKey("domain");
        assertThat(server(Map.of("drishti.var.official.format", "delta")).apply(reader)).isSameAs(reader);
        Map<String, Object> exposure = new java.util.LinkedHashMap<>(Map.of("plugin", "delta", "kinds", List.of("exposure-official-cpty"),
                "settings", Map.of("lake", "ccr-official")));
        assertThat(server(Map.of("drishti.ccr.exposure.official.format", "iceberg")).apply(exposure).get("plugin")).isEqualTo("iceberg");
    }
}
