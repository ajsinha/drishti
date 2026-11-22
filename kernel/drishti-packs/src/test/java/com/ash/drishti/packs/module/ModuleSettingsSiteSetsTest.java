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

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * A retired name counts as set wherever a site wrote it (RetiredNames.in, the one rule): the site's engine file, application.local.yaml, the
 * command line, system properties, and an environment variable Spring binds to the old key; never the product's own copies, and never an
 * environment variable a file names as a placeholder (DRISHTI_VAR_THREADS feeds the new key through the shipped engine file).
 */
class ModuleSettingsSiteSetsTest {

    private static StandardEnvironment env(Map<String, Object> vars) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new org.springframework.core.env.SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, vars));
        // the shipped engine file, as Spring names it, with its placeholders
        env.getPropertySources().addLast(new MapPropertySource("Config resource 'class path resource [engines/var.yaml]' via location 'optional:classpath:engines/var.yaml'",
                Map.of("drishti.module.banking.var.threads", "${DRISHTI_VAR_THREADS:0}", "drishti.var.scenario-window", "250")));
        ConfigurationPropertySources.attach(env);
        return env;
    }

    @Test
    void anEnvironmentVariableAFileNamesAsAPlaceholderIsNotTheRetiredName() {
        StandardEnvironment env = env(Map.of("DRISHTI_VAR_THREADS", "4"));
        assertThat(env.getProperty("drishti.var.threads")).isEqualTo("4");      // Spring does read it so ...
        assertThat(ModuleSettingsEnvironmentPostProcessor.siteSets(env, "drishti.var.threads")).isNull();   // ... and it feeds the new key: not counted
    }

    @Test
    void anEnvironmentVariableSpringBindsToTheRetiredNameIsCountedInBothSpellings() {
        assertThat(ModuleSettingsEnvironmentPostProcessor.siteSets(env(Map.of("DRISHTI_VAR_RANK_RULE", "interpolated")), "drishti.var.rank-rule"))
                .isEqualTo("environment DRISHTI_VAR_RANK_RULE");
        assertThat(ModuleSettingsEnvironmentPostProcessor.siteSets(env(Map.of("DRISHTI_VAR_RANKRULE", "interpolated")), "drishti.var.rank-rule"))
                .isEqualTo("environment DRISHTI_VAR_RANKRULE");
    }

    @Test
    void theShippedCopyIsNeverTheSiteButTheSitesEngineFileIs() {
        StandardEnvironment env = env(Map.of());
        assertThat(ModuleSettingsEnvironmentPostProcessor.siteSets(env, "drishti.var.scenario-window")).as("the jar's copy").isNull();
        String site = "Config resource 'file [/srv/drishti/config/engines/var.yaml]' via location 'optional:file:/srv/drishti/config/engines/var.yaml'";
        env.getPropertySources().addFirst(new MapPropertySource(site, Map.of("drishti.var.scenario-window", "400")));
        assertThat(ModuleSettingsEnvironmentPostProcessor.siteSets(env, "drishti.var.scenario-window")).isEqualTo(site);
        env.getPropertySources().addFirst(new MapPropertySource(RetiredNames.MODULE_COPY + "[banking] engines/var.yaml", Map.of("drishti.var.confidence", "0.99")));
        assertThat(ModuleSettingsEnvironmentPostProcessor.siteSets(env, "drishti.var.confidence")).as("a module's shipped copy").isNull();
    }

    @Test
    void aKeyWrittenInASiteFileIsStillFound() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("site", Map.of("drishti.var.threads", "4")));
        ConfigurationPropertySources.attach(env);
        assertThat(ModuleSettingsEnvironmentPostProcessor.siteSets(env, "drishti.var.threads")).isEqualTo("site");
    }
}
