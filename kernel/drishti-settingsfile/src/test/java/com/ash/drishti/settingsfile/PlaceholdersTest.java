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
package com.ash.drishti.settingsfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one placeholder resolver: -D, then the settings files, then the environment, then the caller's settings, then the default. */
class PlaceholdersTest {

    @AfterEach
    void clean() {
        for (String k : new String[] {SettingsFile.PROPERTY, "PATH", "ph.a", "ph.b", "lake.risk.root", "lake.trading.root",
                "pack.market-risk.lake", "pack.counterparty-risk.lake"}) System.clearProperty(k);
        SettingsFile.reset();
    }

    @Test
    void aDefaultWhenNothingNamesTheKeyAndNestedDefaults() {
        assertThat(Placeholders.resolve("${ph.none:/x}/y")).isEqualTo("/x/y");
        assertThat(Placeholders.resolve("${ph.none:${ph.other:/deep}}")).isEqualTo("/deep");
        assertThat(Placeholders.resolve("${ph.none:-shell}")).isEqualTo("shell");
        assertThat(Placeholders.resolve("plain")).isEqualTo("plain");
        assertThat(Placeholders.resolve("${ph.none}")).as("unknown, no default: kept").isEqualTo("${ph.none}");
        assertThatThrownBy(() -> Placeholders.require("${ph.none}", null)).hasMessageContaining("PH_NONE");
    }

    @Test
    void anExplicitPropertyBeatsTheEnvironmentAndAViewReplacesTheProcess() {
        assertThat(System.getenv("PATH")).isNotNull();
        assertThat(Placeholders.resolve("${PATH:none}")).isEqualTo(System.getenv("PATH"));
        System.setProperty("PATH", "/from/property");
        assertThat(Placeholders.resolve("${PATH:none}")).isEqualTo("/from/property");
        assertThat(Placeholders.resolve("${ph.a:d}", k -> k.equals("ph.a") ? "view" : null)).isEqualTo("view");
        assertThat(Placeholders.resolve("${lake.x.root:d}", k -> k.equals("LAKE_X_ROOT") ? "/env/form" : null)).isEqualTo("/env/form");
    }

    @Test
    void theSettingsFileSitsBetweenAnExplicitPropertyAndTheEnvironment(@TempDir Path dir) throws Exception {
        Path ini = dir.resolve("x.ini");
        Files.writeString(ini, "[global]\nPATH=/from/ini\nph.a=/from/ini/a\n");
        System.setProperty("ph.a", "/explicit");
        System.setProperty(SettingsFile.PROPERTY, ini.toString());
        SettingsFile.applyFromSystemProperty();
        assertThat(Placeholders.resolve("${PATH}")).as("the file beats the environment").isEqualTo("/from/ini");
        assertThat(Placeholders.resolve("${ph.a}")).as("-D beats the file").isEqualTo("/explicit");
    }

    @Test
    void twoPacksOnTwoLakesNeverMoveEachOther(@TempDir Path dir) throws Exception {
        Path ini = dir.resolve("lakes.ini");
        Files.writeString(ini, "[lake.risk]\nroot=/lakes/risk\n[lake.trading]\nroot=/lakes/trading\n[pack.market-risk]\nlake=risk\n");
        System.setProperty(SettingsFile.PROPERTY, ini.toString());
        SettingsFile.applyFromSystemProperty();
        // a shipped template: its own namespaced key, then the named lake's root
        Map<String, String> pack = Map.of("market-risk", "trading", "counterparty-risk", "trading");
        for (String p : pack.keySet()) {
            String lake = Placeholders.resolve("${pack." + p + ".lake:" + pack.get(p) + "}");
            String root = Placeholders.resolve("${lake." + lake + ".root:/data/delta/" + lake + "}");
            assertThat(root).isEqualTo(p.equals("market-risk") ? "/lakes/risk" : "/lakes/trading");
        }
    }
}
