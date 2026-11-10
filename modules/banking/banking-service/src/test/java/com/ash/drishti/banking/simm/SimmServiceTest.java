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
package com.ash.drishti.banking.simm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.banking.compute.simm.Simm;
import com.ash.drishti.banking.compute.simm.SimmChoices;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SIMM on the server over the committed demo data with the TOY parameter file (not ISDA's): the installed versions and the refusal of the
 * shipped empty structure, the hand-built examples, a demo netting set both ways with its provenance, the netting-set list with the
 * stored figures labelled, an analytic scope, a recipe replayed to the cent, the switch off.
 */
class SimmServiceTest {

    static final LocalDate COB = LocalDate.of(2026, 9, 30);
    static final String NS = "NS-ALDERSHOT-LDN";
    static SimmService svc;

    @BeforeAll
    static void setUp() {
        svc = SimmTestData.service();
    }

    @Test
    void theToyVersionIsInstalledAndTheShippedStructureIsRefused(@TempDir Path dir) throws Exception {
        assertThat(svc.defaultVersion()).isEqualTo("TOY-1");
        Files.copy(SimmTestData.toyDir().resolve("simm-toy.yaml"), dir.resolve("toy.yaml"));
        Files.copy(SimmTestData.root().resolve("docs/architecture/capital-parameters/simm.yaml"), dir.resolve("empty.yaml"));
        SimmService two = SimmTestData.service(SimmTestData.props(true, dir));
        assertThat(two.versions()).hasSize(2);
        assertThat(two.versions().stream().filter(v -> !v.ok()).findFirst().orElseThrow().problem()).startsWith("DRS-5333");
        assertThat(two.defaultVersion()).isEqualTo("TOY-1");
        SimmService none = SimmTestData.service(SimmTestData.props(true, dir.resolve("nothing")));
        assertThatThrownBy(() -> none.run(null, NS, COB, null, null, SimmChoices.defaults(), null)).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("DRS-5333 no SIMM version is installed");
        assertThatThrownBy(() -> svc.run(null, NS, COB, null, "2.8+2512", SimmChoices.defaults(), null)).hasMessageContaining("DRS-5333 SIMM 2.8+2512 is not installed");
        // the template's structure is the shipped file
        assertThat(new String(SimmService.class.getClassLoader().getResourceAsStream("playground/simm/template/parameters.yaml").readAllBytes()))
                .isEqualTo(Files.readString(SimmTestData.root().resolve("docs/architecture/capital-parameters/simm.yaml")));
    }

    @Test
    void theHandBuiltExamplesWithTheToyVersion() {
        SimmService.Run r = svc.run(null, SimmService.EXAMPLE, null, null, null, SimmChoices.defaults(), null);
        assertThat(r.collect().charge("RatesFX", "InterestRate", Simm.DELTA).margin).isCloseTo(357_483.43, offset(0.005));
        assertThat(r.collect().charge("RatesFX", "FX", Simm.DELTA).margin).isCloseTo(160_000, offset(0.005));
        assertThat(r.collect().charge("Credit", "CreditQ", Simm.DELTA).margin).isCloseTo(786_129.76, offset(0.005));
        assertThat(r.imPost()).isLessThan(r.imCollect());              // the swaption's curvature falls on one side
        assertThat(r.provenance()).containsKey("Inputs");
        SimmService.Run on = svc.run(null, NS, COB, null, null, SimmChoices.defaults(), null);
        SimmService.Run off = svc.run(null, NS, COB, null, null, SimmChoices.defaults().with("hvr", "off"), null);
        assertThat(com.ash.drishti.banking.compute.simm.SimmCompare.compare(on.collect(), off.collect(), "hvr: on → off").words())
                .isEqualTo("hvr: on → off raises the margin by 5,633,711.74 (2.6%), most of it in Equity Equity vega, bucket 10, at the historical "
                        + "volatility ratio (the vega WS and its concentration factor) step.");
        assertThat(r.imCollect()).isCloseTo(1_231_385.86, offset(0.005));
        assertThat(r.imPost()).isCloseTo(1_223_938.71, offset(0.005));
    }

    @Test
    void aDemoNettingSetBothWays() {
        SimmService.Run r = svc.run(null, NS, COB, null, null, SimmChoices.defaults(), null);
        assertThat(r.kind()).isEqualTo(SimmService.NETTING_SET);
        assertThat(r.records()).isPositive();
        assertThat(r.imCollect()).isPositive();
        assertThat(r.imPost()).isPositive();
        assertThat(r.collect().total()).isCloseTo(r.collect().products().stream().mapToDouble(Simm.ProductIm::simm).sum() + r.collect().addOn().total(),
                offset(1e-6));
        assertThat(r.provenance()).containsKeys("Netting set", "CSA terms", "SIMM version", "Parameter file", "Trades without sensitivities");
        assertThat(String.valueOf(r.provenance().get("SIMM version"))).contains("TOY-1");
        System.out.printf("SIMM %s: %d records, %d risks, collect %.2f, post %.2f, classes %s%n", NS, r.records(), r.collect().risks().size(), r.imCollect(),
                r.imPost(), r.collect().classes().stream().map(c -> c.productClass() + "/" + c.riskClass()).toList());
        // every netting set: computed where it has sensitivities, the stored figure labelled where it has none
        Map<String, Object> list = svc.nettingSets(null, COB, null, null, SimmChoices.defaults());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list.get("rows");
        assertThat(rows).hasSizeGreaterThan(50);
        assertThat(rows).anyMatch(x -> String.valueOf(x.get("status")).startsWith("computed"));
        assertThat(rows).allMatch(x -> String.valueOf(x.get("status")).startsWith("computed") || List.of("stored, not computed", "no sensitivities").contains(String.valueOf(x.get("status"))));
        assertThat(rows).anyMatch(x -> "computed, incomplete".equals(x.get("status")));
        assertThat(list.get("computed")).isEqualTo((int) rows.stream().filter(x -> String.valueOf(x.get("status")).startsWith("computed")).count());
    }

    @Test
    void anAnalyticScopeIsLabelledAndARecipeReplaysToTheCent() {
        SimmService.Run a = svc.run(null, "DESK:DESK-RATES", COB, null, null, SimmChoices.defaults(), null);
        assertThat(a.kind()).isEqualTo(SimmService.ANALYTIC);
        assertThat(a.imCollect()).isPositive();
        SimmRecipes recipes = new SimmRecipes(Path.of(svc.props().recipesDir()), svc);
        SimmService.Run r = svc.run(null, NS, COB, null, null, new SimmChoices("off", null, Map.of()), null);
        SimmRecipes.Recipe saved = recipes.save(r, null);
        SimmRecipes.Replay replay = recipes.replay(saved.id(), null);
        assertThat(replay.differences()).isEmpty();
        assertThat(replay.figuresEqual()).isTrue();
        assertThat(replay.run().imCollect()).isEqualTo(r.imCollect());
    }

    @Test
    void switchedOffItAnswersInWords() {
        SimmService off = SimmTestData.service(SimmTestData.props(false, SimmTestData.toyDir()));
        assertThatThrownBy(() -> off.run(null, NS, COB, null, null, SimmChoices.defaults(), null)).hasMessageContaining("DRS-1020 SIMM is not running on this server");
    }
}
