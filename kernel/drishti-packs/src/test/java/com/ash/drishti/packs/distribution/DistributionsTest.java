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

    static final Path DIR = Path.of("../../config/distributions");
    static final Path MODULES = Path.of("../../modules");
    static final String PRODUCT_DEFAULTS = "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'";

    static StandardEnvironment boot(Object... site) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put(Distributions.DIR, DIR.toString());
        s.put(Distributions.MODULES_DIR, MODULES.toString());
        s.put("drishti.packs.dir", "../../config/packs");
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
        return Distributions.read(Distributions.file(DIR, name), DIR.resolve(Distributions.KERNEL), MODULES, Path.of("../../config/packs"));
    }

    @Test
    void eachDistributionBootsFromItsFileAlone() {
        for (String n : List.of("all", "risk", "finance", "genomics")) {
            StandardEnvironment env = boot("drishti.distribution", n);
            assertThat(env.getProperty(EditionEnvironmentPostProcessor.INFO + ".id")).isEqualTo("drishti." + n);
            assertThat(env.getProperty("drishti.branding.product")).startsWith("Drishti");
        }
        assertThat(boot("distribution", "finance").getProperty("drishti.branding.product")).isEqualTo("Drishti Finance");   // --distribution=
        assertThat(boot("drishti.distribution", "../../config/distributions/drishti.risk.yaml").getProperty("drishti.branding.product"))
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
        assertThat(read("finance").featuresOff()).contains("var", "exposure", "frtb-sa", "simm", "sensitivities", "mr-cube");
        assertThat(env.getProperty("drishti.lenses")).isEqualTo("markets,world");          // the Markets lens: capital-markets' own
        assertThat(Editions.lensNames(env)).containsExactly("markets", "world");
    }

    @Test
    void theModulesManifestsDriveTheFilterAndARequiredModuleComesAlong() {
        java.util.Map<String, ModuleManifests.Entry> known = ModuleManifests.read(MODULES);
        assertThat(known).containsKeys("banking", "market-data", "capitalmarkets", "economy");
        List<String> order = List.copyOf(known.keySet());
        assertThat(order.indexOf("market-data")).isLessThan(order.indexOf("banking"));
        assertThat(known.get("banking").requires()).containsExactly("market-data");
        assertThat(known.get("banking").packs()).doesNotContain("market-data");
        assertThat(known.get("market-data").packs()).containsExactly("market-data");
        assertThat(known.get("economy").packs()).contains("world-economy", "central-banks", "gdelt-events");
        assertThat(DIR.resolve("modules.yaml")).doesNotExist();                       // the hand mapping is gone: one source of truth
        assertThat(ModuleManifests.withRequired(List.of("banking"), known)).containsExactly("banking", "market-data");
        assertThat(read("risk").packs()).contains("market-data", "trading").doesNotContain("world-economy", "us-equities");
        assertThatThrownBy(() -> Distributions.read(Distributions.file(DIR, "risk"), DIR.resolve(Distributions.KERNEL), Path.of("no-such"),
                Path.of("../../config/packs"))).hasMessageContaining("has no manifest");
    }

    /** Risk carries the trade blotter (the trading module) and the six source modules, listed in its file; Finance has neither. */
    @Test
    void riskCarriesTheTradeBlotterAndTheSixSources() throws Exception {
        List<String> mods = read("risk").modules();
        assertThat(mods).contains("trading", "source-endur", "source-calypso", "source-murex", "source-wss", "source-swapone", "source-ici");
        assertThat(read("risk").spec().path("modules").toString()).contains("\"trading\"");
        assertThat(java.util.stream.StreamSupport.stream(read("risk").spec().path("modules").spliterator(), false).anyMatch(
                m -> "trading".equals(m.path("id").asText()) && m.path("required").asBoolean())).isTrue();
        assertThat(java.nio.file.Files.readString(MODULES.resolve("trading/console/plugin.yaml"))).as("the Risk menu's Trade blotter, from the trading module").contains("Trade blotter", "/live/trade");
        assertThat(read("finance").modules()).doesNotContain("banking", "trading");
    }

    /**
     * A distribution sees a module installed as a .dmod in the module store (no manifest in the modules folder), read by the registry's
     * one package reader: its function words are the distribution's; a module neither in the folder nor packaged is still refused.
     */
    @Test
    void anInstalledDmodIsSeenByADistribution(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("data/modules");
        Path version = java.nio.file.Files.createDirectories(store.resolve("installed/freight/0.1.0"));
        java.nio.file.Files.writeString(store.resolve("installed/active.yaml"), "modules:\n  freight: \"0.1.0\"\n");   // as the module node writes it
        java.nio.file.Files.writeString(version.resolve("module.yaml"), "format: 1\nid: freight\nversion: 0.1.0\ndomain: logistics\n"
                + "functions: [{word: SHIP}]\nconsole: {menus: [freight]}\n");
        Path file = tmp.resolve("drishti.freight.yaml");
        java.nio.file.Files.writeString(file, "format: 1\ndistribution: drishti.freight\nbrand: {product: Drishti Freight}\n"
                + "modules:\n  - { id: freight }\nkernel-packs: []\nlenses: {offered: [research], default: research}\n");
        StandardEnvironment env = boot("drishti.distribution", file.toString(), Distributions.MODULES_DIR, tmp.resolve("no-folder").toString(),
                "drishti.data.dir", tmp.resolve("data").toString());
        Distribution d = Distributions.of(env);
        assertThat(d.modules()).containsExactly("freight");
        assertThat(d.commands()).contains("SHIP");
        assertThat(d.domains()).containsEntry("freight", "logistics");
        java.nio.file.Files.delete(store.resolve("installed/active.yaml"));          // not installed any more: refused, with words
        assertThatThrownBy(() -> Distributions.of(env)).hasMessageContaining("is not a shipped or installed .dmod");
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
        assertThat(Editions.lensNames(env)).containsExactly("risk", "trading", "market");
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
    void theOldKeysAreNotReadAndHealthNamesThem() {
        StandardEnvironment env = boot("drishti.edition", "risk", "drishti.branding.tagline", "Our own line");
        assertThat(Editions.of(env)).isSameAs(Edition.NONE);              // the old edition is not read: only a distribution filters
        assertThat(env.getProperty("drishti.branding.tagline")).as("the distribution file says it").isEqualTo("Any data. Any domain. One grammar.");
        String notes = env.getProperty(EditionEnvironmentPostProcessor.INFO + ".deprecated");
        assertThat(notes).contains("drishti.edition is no longer read").contains("drishti.branding.tagline is no longer read");
        StandardEnvironment both = boot("drishti.edition", "risk", "drishti.distribution", "finance");
        assertThat(Editions.of(both).name()).isEqualTo("drishti.finance");
    }

    @Test
    void theFeatureSwitchesAreKernelYamlsAndAnEngineIsPresetOffOnly() throws Exception {
        assertThat(java.nio.file.Files.exists(Path.of("../../config/lenses/features.yaml"))).as("one home: kernel.yaml's features:").isFalse();
        Map<String, List<String>> server = EditionEnvironmentPostProcessor.featureMapping(DIR, "server");
        assertThat(server).containsEntry("calc", List.of("drishti.calc.enabled")).containsEntry("var", List.of("drishti.engines.var.enabled"));
        assertThat(EditionEnvironmentPostProcessor.featureMapping(DIR, "console")).containsEntry("build", List.of("features.build"));
        Path dir = java.nio.file.Files.createTempDirectory(Path.of("target"), "dist");
        java.nio.file.Files.copy(DIR.resolve(Distributions.KERNEL), dir.resolve(Distributions.KERNEL));
        java.nio.file.Files.writeString(dir.resolve("drishti.mine.yaml"), String.join("\n", "format: 1", "distribution: drishti.mine",
                "brand: { product: Mine }", "modules: [{ id: banking }]", "features: { calc: off, var: on, exposure: off }", ""));
        StandardEnvironment env = boot(Distributions.DIR, dir.toString(), "drishti.distribution", "mine");
        assertThat(env.getProperty("drishti.calc.enabled")).isEqualTo("false");
        assertThat(env.getProperty("drishti.engines.exposure.enabled")).as("off: it does not exist here").isEqualTo("false");
        assertThat(env.getProperty("drishti.engines.var.enabled")).as("on is the engine switch's to say, never the distribution's").isNull();
    }

    @Test
    void genomicsBootsOnTheKernelAlone() {
        Distribution g = read("genomics");
        assertThat(g.packs()).as("none of the kernel's packs: no counterparty, book or desk").isEmpty();
        assertThat(g.wordsOff()).contains("VAR", "EXP");
        assertThat(g.modules()).containsExactly("genomics");                            // its manifest: modules/genomics/src/dmod (the SDK's layout)
        assertThat(g.domains()).containsEntry("genomics", "genomics");
        assertThat(g.lenses()).containsExactly("research");
        StandardEnvironment env = boot("drishti.distribution", "genomics");
        assertThat(Editions.of(env).commandShown("CRV")).isFalse();
        assertThat(Editions.of(env).commandShown("CPTY")).isFalse();
        assertThat(env.getProperty("drishti.packs.enabled")).as("a distribution of no packs loads none").isEmpty();
    }
}
