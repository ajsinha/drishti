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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Banking's settings are described by its manifest and read as {@code drishti.module.banking.*}; the old keys are read for one release. */
class ModuleSettingsTest {

    private static final Path BANKING = Path.of("../../modules/banking/dmod/module.yaml");

    private static JsonNode manifest() throws Exception {
        return new ObjectMapper(new YAMLFactory()).readTree(BANKING.toFile());
    }

    @Test
    void theManifestDescribesTheSettingsOfTheModuleAndOfItsEngines() throws Exception {
        ModuleSettingsSchema s = ModuleSettingsSchema.of("banking", manifest());
        assertThat(s.items()).extracting(ModuleSettingsSchema.Item::key).contains("var.window", "var.confidence", "var.hierarchy.empty-label",
                "var.system-baskets.seed", "exposure.limit-confidence", "frtb.parameters-dir", "simm.default-version", "sensitivities.cache-minutes",
                "mr-cube.sbm-max-cells");
        assertThat(s.items()).allSatisfy(i -> assertThat(i.was()).startsWith("drishti."));
    }

    @Test
    void theModulesKeyWinsAndTheOldKeyIsStillRead() throws Exception {
        ModuleSettingsSchema s = ModuleSettingsSchema.of("banking", manifest());
        Map<String, String> env = Map.of("drishti.module.banking.var.confidence", "0.95", "drishti.var.es-confidence", "0.9", "drishti.var.confidence", "0.99");
        assertThat(s.asOldKeys(env::get)).containsEntry("drishti.var.confidence", "0.95").doesNotContainKey("drishti.var.es-confidence");
        var by = s.resolve(env::get).stream().collect(java.util.stream.Collectors.toMap(r -> r.item().key(), r -> r));
        assertThat(by.get("var.confidence").source()).isEqualTo("module");
        assertThat(by.get("var.confidence").value()).isEqualTo("0.95");
        assertThat(by.get("var.es-confidence").source()).isEqualTo("old-key");
        assertThat(by.get("var.window").source()).isEqualTo("default");
    }

    @Test
    void theEnvironmentGivesTheModulesKeyToTheOldKeyAndHealthNamesTheOldKeysASiteSets() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("Config resource 'file [application.local.yaml]' via location 'x'",
                Map.of("drishti.module.banking.var.window", "500", "drishti.var.hot-dates", "3", "drishti.module-manifests-dir", "../../modules")));
        new ModuleSettingsEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertThat(env.getProperty("drishti.var.scenario-window")).as("the module's key reaches the code that reads the old key").isEqualTo("500");
        assertThat(env.getProperty(ModuleSettingsEnvironmentPostProcessor.INFO + ".deprecated"))
                .contains("drishti.var.hot-dates is read for one release: set drishti.module.banking.var.hot-dates")
                .doesNotContain("scenario-window");
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
