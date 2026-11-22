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

/** A retired name counts as set only where a site wrote the key: an environment variable such as DRISHTI_VAR_SYSTEM_BASKETS_SEED is a placeholder's source. */
class ModuleSettingsSiteSetsTest {

    @Test
    void anEnvironmentVariableThatSpringReadsAsTheRetiredNameIsNotTheSiteSettingIt() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("DRISHTI_VAR_THREADS", "4")));
        ConfigurationPropertySources.attach(env);
        assertThat(env.getProperty("drishti.var.threads")).isEqualTo("4");      // Spring does read it so ...
        assertThat(ModuleSettingsEnvironmentPostProcessor.siteSets(env, "drishti.var.threads")).isNull();   // ... and the retired-name check does not count it
    }

    @Test
    void aKeyWrittenInASiteFileIsStillFound() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("site", Map.of("drishti.var.threads", "4")));
        ConfigurationPropertySources.attach(env);
        assertThat(ModuleSettingsEnvironmentPostProcessor.siteSets(env, "drishti.var.threads")).isEqualTo("site");
    }
}
