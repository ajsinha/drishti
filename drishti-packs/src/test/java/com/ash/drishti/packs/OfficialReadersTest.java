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

    @Test
    void theVarReaderIsGeneratedForIcebergAndFollowsBackToDelta(@TempDir Path dir) throws Exception {
        List<Pack> packs = packs(dir);
        ConnectorFiles files = new ConnectorFiles(dir.resolve("connectors"));
        ConnectorBootstrap.run(packs, files, null, true, server(Map.of("drishti.var.official.format", "iceberg",
                "drishti.var.official.root", "s3://bank-lake/risk", "drishti.var.official.storage.s3.region", "us-east-1")));
        ConnectorFiles.Definition v = files.read("var-results");
        assertThat(v.plugin()).isEqualTo("iceberg");
        assertThat(v.settings()).containsEntry("root", "${drishti.var.official.root:${DRISHTI_DELTA_ROOT:./data/delta}}")
                .containsEntry("engine", "native").containsEntry("domain", "risk-results")
                .containsEntry("s3.region", "${drishti.var.official.storage.s3.region}").containsKey("layout.var-official.columns");
        assertThat(files.read("exposure-results").plugin()).as("the exposure format is its own").isEqualTo("delta");
        assertThat(files.read("risk-store").plugin()).isEqualTo("delta");

        ConnectorBootstrap.Result back = ConnectorBootstrap.run(packs, files, null, true, server(Map.of()));   // the format back to Delta
        assertThat(back.regenerated()).containsExactly("var-results");
        assertThat(files.read("var-results").plugin()).isEqualTo("delta");
        assertThat(files.read("var-results").settings()).containsEntry("root", "${DRISHTI_DELTA_ROOT:./data/delta}").doesNotContainKey("engine");
    }

    @Test
    void theExposureReaderFollowsItsOwnFormatAndAnEditedFileIsKept(@TempDir Path dir) throws Exception {
        List<Pack> packs = packs(dir);
        ConnectorFiles files = new ConnectorFiles(dir.resolve("connectors"));
        ConnectorBootstrap.run(packs, files, null, true, server(Map.of()));
        assertThat(files.read("exposure-results").plugin()).isEqualTo("delta");
        ConnectorBootstrap.Result r = ConnectorBootstrap.run(packs, files, null, true, server(Map.of("drishti.ccr.exposure.official.format", "iceberg")));
        assertThat(r.regenerated()).containsExactly("exposure-results");                  // never edited: it follows the format
        assertThat(files.read("exposure-results").plugin()).isEqualTo("iceberg");
        assertThat(files.read("var-results").plugin()).isEqualTo("delta");

        files.write("var-results", files.text("var-results").replace("domain: risk-results", "domain: risk-results\n  refresh-seconds: 30"));
        ConnectorBootstrap.Result edited = ConnectorBootstrap.run(packs, files, null, true, server(Map.of("drishti.var.official.format", "iceberg")));
        assertThat(edited.regenerated()).doesNotContain("var-results");                   // the site's edit is kept, the change listed
        assertThat(edited.changed()).anyMatch(c -> c.name().equals("var-results"));
        assertThat(files.read("var-results").plugin()).isEqualTo("delta");
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
}
