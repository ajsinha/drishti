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
package com.ash.drishti.server.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.ConnectorSecrets;
import com.ash.drishti.engine.source.PluginDiscovery;
import com.ash.drishti.engine.source.SettingCatalogue;
import com.ash.drishti.packs.ConnectorFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every example under config/connectors.examples is a valid connector file: it parses, names a shipped plugin, holds no literal credential. */
class ConnectorExamplesTest {

    @Test
    void everyExampleIsValidAndHoldsNoSecret() throws IOException {
        Path dir = Path.of("../config/connectors.examples");
        ConnectorFiles files = new ConnectorFiles(dir);
        SettingCatalogue catalogue = new SettingCatalogue(new PluginDiscovery().discover(null));
        List<Path> all;
        try (Stream<Path> s = Files.walk(dir)) {
            all = s.filter(p -> p.toString().endsWith(".yaml")).toList();
        }
        assertThat(all.size()).isGreaterThan(40);
        for (Path p : all) {
            String name = p.getFileName().toString().replace(".yaml", "");
            ConnectorFiles.Definition d = files.parse(name, Files.readString(p));
            assertThat(catalogue.of(d.plugin())).as(p + " plugin " + d.plugin()).isNotNull();
            d.settings().forEach((k, v) -> {
                if (ConnectorSecrets.secretKey(k) && !v.isBlank()) {
                    assertThat(ConnectorSecrets.reference(v)).as(p + " " + k).isTrue();
                }
                var spec = catalogue.of(d.plugin()).find(k);
                if (catalogue.of(d.plugin()).declared()) {
                    assertThat(spec).as(p + " setting " + k + " is known to the " + d.plugin() + " plugin").isNotNull();
                }
            });
            assertThat(d.settings().get("password")).as(p + " password has no default").satisfiesAnyOf(
                    v -> assertThat(v).isNull(), v -> assertThat(v).doesNotContain(":"));
        }
    }
}
