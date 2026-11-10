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
package com.ash.drishti.packs.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.packs.PackPaths;
import com.ash.drishti.packs.edition.Edition;
import com.ash.drishti.packs.edition.EditionEnvironmentPostProcessor;
import com.ash.drishti.packs.edition.Editions;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** The distribution files (docs/architecture/DISTRIBUTIONS.md): each boots from its file alone, and each shows only its modules. */
class DistributionsTest {

    static final Path DIR = Path.of("../config/distributions");
    static final String PRODUCT_DEFAULTS = "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'";

    static StandardEnvironment boot(Object... site) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put(Distributions.DIR, DIR.toString());
        s.put("drishti.packs.dir", "../config/packs");
        s.put("drishti.lens-dir", "../config/lenses");
        for (int i = 0; i < site.length; i += 2) {
            s.put((String) site[i], site[i + 1]);
        }
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(new MapPropertySource("site", s));
        env.getPropertySources().addLast(new MapPropertySource(PRODUCT_DEFAULTS, Map.of("drishti.packs.enabled", "${DRISHTI_PACKS:default}",
                "drishti.branding.product", "${DRISHTI_PRODUCT:Drishti}", "drishti.lenses", "${DRISHTI_LENSES:risk,world,market,trading}")));
        new EditionEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
        return env;
    }

    static Distribution read(String name) {
        return Distributions.read(Distributions.file(DIR, name), DIR.resolve(Distributions.MODULES), Path.of("../config/packs"));
    }

    @Test
    void eachDistributionBootsFromItsFileAlone() {
        for (String n : List.of("all", "risk", "finance", "genomics")) {
            StandardEnvironment env = boot("drishti.distribution", n);
            assertThat(env.getProperty(EditionEnvironmentPostProcessor.INFO + ".id")).isEqualTo("drishti." + n);
            assertThat(env.getProperty("drishti.branding.product")).startsWith("Drishti");
        }
        assertThat(boot("distribution", "finance").getProperty("drishti.branding.product")).isEqualTo("Drishti Finance");   // --distribution=
        assertThat(boot("drishti.distribution", "../config/distributions/drishti.risk.yaml").getProperty("drishti.branding.product"))
                .isEqualTo("Drishti Risk");                                                                                // a path
        assertThatThrownBy(() -> boot("drishti.distribution", "nosuch")).hasMessageContaining("no distribution file");
    }

    @Test
    void financeHasNoVarCommandsWordsOrEngines() {
        StandardEnvironment env = boot("drishti.distribution", "finance");
        Edition e = Editions.of(env);
        assertThat(e.distribution()).isTrue();
        assertThat(e.title()).isEqualTo("Drishti Finance");
        for (String c : List.of("VRES", "VOFF", "PNLV", "SPV", "EXP", "XPATH", "FRTB", "SACCR", "TRD", "POS")) {
            assertThat(e.commandShown(c)).as(c).isFalse();
        }
        for (String w : List.of("VAR", "EXP", "SMAT", "PLAY", "FRSA", "SIMM", "MCUBE")) {
            assertThat(e.wordOn(w)).as(w).isFalse();
        }
        assertThat(e.commandShown("USEQ")).isTrue();
        assertThat(e.commandShown("ECON")).isTrue();
        assertThat(e.wordOn("PIVOT")).isTrue();
        assertThat(env.getProperty("drishti.engines.var.enabled")).isEqualTo("false");   // the engines Finance leaves out are off on its kernel
        assertThat(PackPaths.enabled(env)).doesNotContain("market-risk").contains("market-data", "us-equities", "world-economy");
        assertThat(read("finance").featuresOff()).contains("var", "exposure");
    }

    @Test
    void riskHasNoEconomyCommandsAndMarketDataIsInBoth() {
        Edition risk = Editions.of(boot("drishti.distribution", "risk"));
        for (String c : List.of("ECON", "MACRO", "NEWS", "USEQ", "FUND", "GDELT")) {
            assertThat(risk.commandShown(c)).as(c).isFalse();
        }
        assertThat(risk.commandShown("VRES")).isTrue();
        assertThat(risk.wordOn("VAR")).isTrue();
        Edition finance = Editions.of(boot("drishti.distribution", "finance"));
        for (String c : List.of("CRV", "FX", "FXV", "EQ", "CDS", "BND")) {
            assertThat(risk.commandShown(c)).as(c).isTrue();
            assertThat(finance.commandShown(c)).as(c).isTrue();
        }
    }

    @Test
    void riskOffersItsLensesAndTheRequestFallsBackToTheDistribution() {
        StandardEnvironment env = boot("drishti.distribution", "risk");
        assertThat(env.getProperty("drishti.lenses")).isEqualTo("risk,trading,market");
        assertThat(Editions.lenses(env)).containsOnlyKeys("risk", "trading", "market");
    }

    @Test
    void allMatchesToday() {
        StandardEnvironment env = boot();
        assertThat(Editions.of(env)).isSameAs(Edition.NONE);
        assertThat(env.getProperty("drishti.branding.product")).isEqualTo("Drishti");
        assertThat(env.getProperty("drishti.lenses")).isEqualTo("risk,world,market,trading");
        assertThat(PackPaths.enabled(env)).isEqualTo(PackPaths.DEFAULT_ENABLED);
        assertThat(read("all").all()).isTrue();
        assertThat(Distributions.builtInAll().all()).isTrue();
    }

    @Test
    void theOldKeysStillWorkWithADeprecationNote() {
        StandardEnvironment env = boot("drishti.edition", "risk", "drishti.branding.tagline", "Our own line");
        Edition e = Editions.of(env);
        assertThat(e.name()).isEqualTo("risk");                          // the old edition, read for one release
        assertThat(e.commandShown("VRES")).isTrue();
        assertThat(env.getProperty("drishti.branding.tagline")).isEqualTo("Our own line");
        String notes = env.getProperty(EditionEnvironmentPostProcessor.INFO + ".deprecated");
        assertThat(notes).contains("drishti.edition is read for one release").contains("drishti.branding.tagline");
        StandardEnvironment both = boot("drishti.edition", "risk", "drishti.distribution", "finance");
        assertThat(Editions.of(both).name()).isEqualTo("drishti.finance");
        assertThat(both.getProperty(EditionEnvironmentPostProcessor.INFO + ".deprecated")).contains("is ignored");
    }

    @Test
    void genomicsBootsOnTheKernelAlone() {
        Distribution g = read("genomics");
        assertThat(g.packs()).containsExactly("core");
        assertThat(g.wordsOff()).contains("VAR", "EXP");
        assertThat(g.domains()).containsEntry("genomics", "genomics");
        assertThat(Editions.of(boot("drishti.distribution", "genomics")).commandShown("CRV")).isFalse();
    }
}
