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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.packs.PackPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** The edition layer (docs/architecture/EDITIONS.md): the shipped risk edition file, the property layer and its place, the one default pack list. */
class EditionsTest {

    static final Path DIR = Path.of("../config/editions");
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
        String file = Files.readString(DIR.resolve("default-packs.yaml"));
        assertThat(Editions.defaultPacks()).isNotEmpty().allSatisfy(p -> assertThat(file).contains(p));
        assertThat(PackPaths.DEFAULT_ENABLED).isEqualTo(String.join(",", Editions.defaultPacks()));
    }

    @Test
    void noEditionAddsNothingAndAllowsEverything() {
        StandardEnvironment env = env(map(), map("drishti.packs.enabled", "${DRISHTI_PACKS:default}"));
        new EditionEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getPropertySources().contains(EditionEnvironmentPostProcessor.SOURCE)).isFalse();
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
    void theRiskEditionIsALayerUnderTheSiteAndAboveTheProductDefaults() {
        StandardEnvironment env = env(map("drishti.edition", "risk", "drishti.editions.dir", DIR.toString(),
                "drishti.edition-spec.commands.also-allow[0]", "FRTB"),
                map("drishti.packs.enabled", "${DRISHTI_PACKS:default}", "drishti.branding.product", "${DRISHTI_PRODUCT:Drishti}"));
        new EditionEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
        List<String> order = env.getPropertySources().stream().map(p -> p.getName()).toList();
        assertThat(order.indexOf(EditionEnvironmentPostProcessor.SOURCE)).isGreaterThan(order.indexOf("site")).isLessThan(order.indexOf(PRODUCT_DEFAULTS));
        assertThat(env.getProperty("drishti.packs.enabled")).isEqualTo("core,market-data,trading,counterparty-risk,market-risk");
        assertThat(env.getProperty("drishti.branding.product")).isEqualTo("Drishti Risk");
        assertThat(env.getProperty("drishti.bi.enabled")).isEqualTo("true");
        assertThat(env.getProperty("drishti.calc.enabled")).isEqualTo("false");
        Edition risk = Editions.of(env);
        assertThat(risk.name()).isEqualTo("risk");
        assertThat(risk.title()).isEqualTo("Drishti Risk");
        assertThat(risk.packAllowed("market-risk")).isTrue();
        assertThat(risk.packAllowed("gleif")).isFalse();
        assertThatThrownBy(() -> risk.requirePack("gleif")).isInstanceOf(DrishtiException.class).hasMessageContaining("allow-only");
        assertThat(risk.commandShown("vres")).isTrue();
        assertThat(risk.commandShown("FRTB")).isTrue();                     // the site's also-allow
        assertThat(risk.commandShown("CVA")).isFalse();
        assertThat(risk.wordOn("FORM")).isFalse();
        assertThat(risk.wordOn("RUPAKA")).isFalse();
        assertThat(risk.wordOn("VAR")).isTrue();
        assertThat(risk.wordOn("EXP")).isTrue();
        assertThat(risk.datasetShown("market-risk-var-latest")).isTrue();
        assertThat(risk.datasetShown("trades-basic-trade")).isFalse();
        assertThat(risk.modelShown("market-risk-var@1")).isTrue();
        assertThat(risk.modelShown("trades-basic")).isFalse();
        assertThat(risk.adminPageShown("users")).isTrue();
        assertThat(risk.adminPageShown("ai")).isFalse();
        assertThat(risk.about()).containsEntry("name", "risk").containsEntry("title", "Drishti Risk");
    }

    @Test
    void theSitesVariableStillWinsOverTheEditionsPacks() {
        StandardEnvironment env = env(map("drishti.edition", "risk", "drishti.editions.dir", DIR.toString(), "DRISHTI_PACKS", "core,trading"),
                map("drishti.packs.enabled", "${DRISHTI_PACKS:default}"));
        new EditionEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("drishti.packs.enabled")).isEqualTo("core,trading");
    }

    @Test
    void aMissingEditionFileStopsTheStartWithWhatToDo() {
        StandardEnvironment env = env(map("drishti.edition", "nope", "drishti.editions.dir", DIR.toString()), map());
        assertThatThrownBy(() -> new EditionEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication()))
                .hasMessageContaining("no edition file").hasMessageContaining("DRISHTI_EDITIONS_DIR");
        StandardEnvironment bad = env(map("drishti.edition", "../etc", "drishti.editions.dir", DIR.toString()), map());
        assertThatThrownBy(() -> new EditionEnvironmentPostProcessor().postProcessEnvironment(bad, new SpringApplication()))
                .hasMessageContaining("not an edition name");
    }

    @Test
    void lensesAreReadFromTheirFilesOnAFullInstanceOnly() {
        StandardEnvironment full = env(map("drishti.lenses", "risk,missing", "drishti.editions.dir", DIR.toString()), map());
        Map<String, Edition> lenses = Editions.lenses(full);
        assertThat(lenses).containsOnlyKeys("risk");
        assertThat(lenses.get("risk").commandShown("FRTB")).isFalse();
        StandardEnvironment edition = env(map("drishti.edition", "risk", "drishti.lenses", "risk", "drishti.editions.dir", DIR.toString()), map());
        assertThat(Editions.lenses(edition)).isEmpty();
    }
}
