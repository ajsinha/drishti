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

import com.ash.drishti.packs.bindings.Bindings;
import com.ash.drishti.packs.bindings.BindingsFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The bindings upgrade reads the site's connector files: a connector switched off (by its file or the properties) binds nothing, and a
 * connector defined only in properties is never written into the site's file (the next start may define other connectors).
 */
class UpgradeConnectorsTodayTest {

    @Test
    void aConnectorSwitchedOffOrOnlyInThePropertiesBindsNothing(@TempDir Path dir) throws Exception {
        Path conn = Files.createDirectories(dir.resolve("connectors"));
        Files.writeString(conn.resolve("finance-lake.yaml"), "plugin: delta\nkinds: [counterparty, trade]\n");
        Files.writeString(conn.resolve("risk-store.yaml"), "plugin: delta\nkinds: [scenario-pv]\n");
        Map<String, ConnectorFiles.Definition> files = new ConnectorFiles(conn, dir.resolve("data")).readAll(new HashMap<>());
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "drishti.sources.connectors.finance-lake.enabled", "false",
                "drishti.sources.connectors.ccr-lake.plugin", "delta",
                "drishti.sources.connectors.ccr-lake.kinds[0]", "netting-set",
                "drishti.sources.connectors.ccr-lake.kinds[1]", "counterparty",
                "drishti.sources.connectors.ccr-lake.settings.refresh-seconds", "1")));

        Map<String, ConnectorFiles.Definition> today = PackEnvironmentPostProcessor.connectorsToday(env, files);
        assertThat(today).as("a connector of the properties is not the site's file").containsOnlyKeys("risk-store");

        Bindings b = BindingsFile.besideConnectors(conn).upgradeFrom(today).orElseThrow();
        assertThat(b.of("counterparty")).as("only the switched-off connector's file listed it").isEmpty();
        assertThat(b.of("trade")).isEmpty();
        assertThat(b.of("scenario-pv").orElseThrow().read()).containsExactly("risk-store");
    }
}
