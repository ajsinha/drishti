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
package com.ash.drishti.packs.edition;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.packs.PackPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** The edition layer (docs/guides/ADMIN_GUIDE.md): the shipped risk edition file, the property layer and its place, the one default pack list. */
class EditionsTest {

    static final String PRODUCT_DEFAULTS = "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'";

    static StandardEnvironment env(Map<String, Object> site, Map<String, Object> product) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(new MapPropertySource("site", site));
        env.getPropertySources().addLast(new MapPropertySource(PRODUCT_DEFAULTS, product));
        return env;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void theDefaultPackListIsTheOneFile() throws Exception {
        String file = Files.readString(Path.of("../../config/packs/default-packs.yaml"));   // by module
        assertThat(Editions.defaultPacks()).isNotEmpty().allSatisfy(p -> assertThat(file).contains(p));
        assertThat(PackPaths.DEFAULT_ENABLED).isEqualTo(String.join(",", Editions.defaultPacks()));
    }

    @Test
    void noEditionAddsNothingAndAllowsEverything() {
        StandardEnvironment env = env(map(), map("drishti.packs.enabled", "${DRISHTI_PACKS:default}"));
        new EditionEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("drishti.edition")).isEmpty();         // Drishti All: only the distribution facts, no filter
        assertThat(env.getProperty(EditionEnvironmentPostProcessor.INFO + ".id")).isEqualTo("drishti.all");
        Edition none = Editions.of(env);
        assertThat(none).isSameAs(Edition.NONE);
        assertThat(none.packAllowed("anything")).isTrue();
        assertThat(none.commandShown("FRTB")).isTrue();
        assertThat(none.wordOn("FORM")).isTrue();
        assertThat(none.datasetShown("trades-basic-trade")).isTrue();
        assertThat(none.adminPageShown("ai")).isTrue();
        assertThat(none.about()).isNull();
        assertThat(PackPaths.enabled(env)).isEqualTo(PackPaths.DEFAULT_ENABLED);
    }

    @Test
    void aSitesOldEditionKeyNoLongerSelectsALensFile() {
        StandardEnvironment env = env(map("drishti.edition", "risk", "DRISHTI_EDITION", "risk"), map("drishti.packs.enabled", "${DRISHTI_PACKS:default}"));
        new EditionEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
        assertThat(Editions.of(env)).isSameAs(Edition.NONE);               // a lens never filters: what exists is the distribution's
        assertThat(PackPaths.enabled(env)).isEqualTo(PackPaths.DEFAULT_ENABLED);
        assertThat(env.getProperty(EditionEnvironmentPostProcessor.INFO + ".deprecated")).contains("drishti.edition is no longer read");
    }

    @Test
    void theServerKnowsOnlyTheLensNames() {
        StandardEnvironment env = env(map("drishti.lenses", "risk, world"), map());
        assertThat(Editions.lensNames(env)).containsExactly("risk", "world");
        assertThat(Editions.lensNames(env(map(), map()))).isEmpty();
    }
}
