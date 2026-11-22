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
package com.ash.drishti.packs.module;

import com.ash.lake.tck.TestRoots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Banking's settings are described by its manifest and read as {@code drishti.module.banking.*}; the old keys are retired: a site that sets one stops the start, naming the new key. */
class ModuleSettingsTest {

    private static final Path BANKING = Path.of(TestRoots.repo(), "modules", "banking", "module.yaml");

    private static JsonNode manifest() throws Exception {
        return new ObjectMapper(new YAMLFactory()).readTree(BANKING.toFile());
    }

    @Test
    void theManifestDescribesTheSettingsOfTheModuleAndOfItsEngines() throws Exception {
        ModuleSettingsSchema s = ModuleSettingsSchema.of("banking", manifest());
        assertThat(s.items()).extracting(ModuleSettingsSchema.Item::key).contains("var.window", "var.confidence", "var.hierarchy.empty-label",
                "var.system-baskets.seed", "exposure.limit-confidence", "frtb.parameters-dir", "simm.default-version", "sensitivities.cache-minutes",
                "mr-cube.sbm-max-cells");
        assertThat(s.items()).allSatisfy(i -> assertThat(i.retired()).startsWith("drishti."));
    }

    @Test
    void theModulesKeyIsTheOnlyOneRead() throws Exception {
        ModuleSettingsSchema s = ModuleSettingsSchema.of("banking", manifest());
        Map<String, String> env = Map.of("drishti.module.banking.var.confidence", "0.95", "drishti.var.es-confidence", "0.9");
        var by = s.resolve(env::get).stream().collect(java.util.stream.Collectors.toMap(r -> r.item().key(), r -> r));
        assertThat(by.get("var.confidence").source()).isEqualTo("module");
        assertThat(by.get("var.confidence").value()).isEqualTo("0.95");
        assertThat(by.get("var.es-confidence").source()).as("the old key is not read").isEqualTo("default");
        assertThat(by.get("var.window").source()).isEqualTo("default");
    }

    @Test
    void aSiteThatSetsAnOldNameStopsTheStartAndNamesTheNewKey(@org.junit.jupiter.api.io.TempDir Path work) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("Config resource 'file [application.local.yaml]' via location 'x'",
                Map.of("drishti.module.banking.var.window", "500", "drishti.var.hot-dates", "3", "drishti.var.scenario-window", "400")));
        // the banking package booted as the launch boots it, and its result put first as the launch puts it (LaunchRoots runs before this post-processor)
        env.getPropertySources().addFirst(new MapPropertySource("launch roots", new java.util.LinkedHashMap<>(com.ash.drishti.it.BootedPackages.boot(work, "market-data", "banking"))));
        assertThatThrownBy(() -> new ModuleSettingsEnvironmentPostProcessor().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("drishti.var.hot-dates (set in Config resource 'file [application.local.yaml]' via location 'x') is retired: use drishti.module.banking.var.hot-dates")
                .hasMessageContaining("drishti.var.scenario-window").hasMessageContaining("use drishti.module.banking.var.window")
                .hasMessageNotContaining("drishti.module.banking.var.window (set in");
    }

    @Test
    void theNewNamesAloneStartTheServer(@org.junit.jupiter.api.io.TempDir Path work) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("Config resource 'file [application.local.yaml]' via location 'x'",
                Map.of("drishti.module.banking.var.window", "500", "drishti.module.banking.var.hot-dates", "3")));
        env.getPropertySources().addFirst(new MapPropertySource("launch roots", new java.util.LinkedHashMap<>(com.ash.drishti.it.BootedPackages.boot(work, "market-data", "banking"))));
        new ModuleSettingsEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertThat(env.getProperty("drishti.var.scenario-window")).as("nothing gives the module's key to an old name").isNull();
        assertThat(env.getProperty(ModuleSettingsEnvironmentPostProcessor.INFO + ".modules")).contains("banking");
    }

    @Test
    void theManifestDeclaresTheModulesRealParts() throws Exception {
        JsonNode parts = manifest().path("parts");
        assertThat(parts.path("api").path("class").asText()).isEqualTo("light");
        assertThat(parts.path("compute").path("class").asText()).isEqualTo("heavy");
        assertThat(parts.path("compute").path("paths").size()).isGreaterThanOrEqualTo(5);
        assertThat(parts.path("feeds").path("class").asText()).isEqualTo("feed");
    }
}
