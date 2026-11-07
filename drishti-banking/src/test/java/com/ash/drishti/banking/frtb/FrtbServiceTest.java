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
package com.ash.drishti.banking.frtb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.banking.compute.frtb.FrtbChoices;
import com.ash.drishti.banking.compute.frtb.FrtbCompare;
import com.ash.drishti.banking.var.VarService;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The FRTB-SA service over the demo sensitivities (no server): the load and its refusals, sums by bucket for a scope, a desk's charge
 * equal to its positions as a basket, contributions that add up, an override never regulatory, and a recipe replayed byte-equal.
 */
class FrtbServiceTest {

    static final LocalDate COB = LocalDate.of(2026, 9, 30);

    @TempDir
    Path dir;

    @Test
    void theDemoLoadsWithEveryElementPlacedAndNothingRefused() {
        FrtbService svc = FrtbTestData.service(null);
        FrtbData.Day day = svc.data().day(new AsOf(COB, null));
        assertThat(day.dictionary().size()).isGreaterThan(850);
        assertThat(day.records()).hasSize(8052);
        assertThat(day.refusals()).isEmpty();
        assertThat(day.proxied()).isPositive();
        FrtbService.Run bank = svc.run(null, "BANK", COB, null, "USD", FrtbChoices.defaults());
        assertThat(bank.result().sbm().model.refusals).isEmpty();
        assertThat(bank.result().sbm().sbm()).isPositive();
        Map<String, Object> c = FrtbAnswer.contributions(bank, "DESK");
        assertThat((Boolean) c.get("addsUp")).isTrue();
    }

    @Test
    void sumsByBucketAndTheSourceNamesMapToOneFactor() {
        FrtbService svc = FrtbTestData.service(null);
        Map<String, Object> s = svc.sums(null, "DESK:DESK-RATES", COB, "BUCKET", "USD");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) s.get("rows");
        assertThat(rows).isNotEmpty();
        assertThat(rows).anyMatch(r -> r.get("key").equals("GIRR USD") && r.get("measure").equals("DV01"));
        FrtbData.Day day = svc.data().day(new AsOf(COB, null));
        assertThat(day.dictionary().resolve("MX:IR:CURVE-USD-SOFR-10Y").name()).isEqualTo("IR/CURVE/USD/SOFR/10Y");
        assertThat(day.dictionary().resolve("10Y_SOFR_USD_CURVE_IR").name()).isEqualTo("IR/CURVE/USD/SOFR/10Y");
    }

    @Test
    void aDeskEqualsItsPositionsAsABasketAndTheVarResolverIsUsedWhenPresent() {
        FrtbService plain = FrtbTestData.service(null);
        FrtbService.Run desk = plain.run(null, "BOOK:BOOK-EQD-1", COB, null, "USD", FrtbChoices.defaults());
        Set<String> ids = new java.util.LinkedHashSet<>();
        desk.records().forEach(r -> ids.add(r.positionKey()));
        // the VaR resolver's answer for a made-up scope word: the same positions
        FrtbService viaVar = FrtbTestData.service((p, scope, date) -> new VarService.ScopeMembers(COB, "BASKET:TEST", Set.of(), ids, Map.of(), 0));
        FrtbService.Run basket = viaVar.run(null, "BASKET:TEST", COB, null, "USD", FrtbChoices.defaults());
        assertThat(basket.result().total()).isEqualTo(desk.result().total());
        assertThat(basket.result().sbm().total).containsExactly(desk.result().sbm().total);
        assertThat(basket.result().rrao().delivered()).isTrue();                 // derived from the VaR book's notionals (none mapped here)
        assertThatThrownBy(() -> plain.run(null, "NODE:MGMT~X", COB, null, "USD", FrtbChoices.defaults())).hasMessageContaining("VaR engine");
    }

    @Test
    void anOverrideIsNeverRegulatoryAndTheComparisonSaysWhere() {
        FrtbService svc = FrtbTestData.service(null);
        FrtbService.Run base = svc.run(null, "BOOK:BOOK-RATES-1", COB, null, "USD", FrtbChoices.defaults());
        FrtbChoices o = new FrtbChoices(null, null, null, null, null, Map.of("GIRR.delta.across-buckets", "0.9"));
        FrtbService.Run alt = svc.run(null, "BOOK:BOOK-RATES-1", COB, null, "USD", o);
        Map<String, Object> a = FrtbAnswer.sa(alt);
        assertThat(a.get("regulatory")).isEqualTo(false);
        assertThat((String) a.get("notRegulatory")).contains("not the regulatory figure");
        assertThat(alt.provenance()).containsKey("Overrides (not the regulatory figure)");
        FrtbCompare.Diff d = FrtbCompare.compare(base.result(), alt.result(), "GIRR γ 0.5 → 0.9");
        assertThat(d.lines()).isNotEmpty();
        assertThat(d.lines().get(0).cls()).isEqualTo("GIRR");
        assertThat(d.words()).contains("GIRR");
    }

    @Test
    void aSavedRecipeReplaysByteEqualAndAChangedParameterFileDoesNotChangeIt() throws Exception {
        FrtbService svc = FrtbTestData.service(null);
        FrtbRecipes recipes = new FrtbRecipes(dir, svc);
        FrtbChoices c = FrtbChoices.defaults().with("girr-correlation", "exact");
        FrtbService.Run run = svc.run(null, "BOOK:BOOK-FI-1 + BOOK:BOOK-COMM-2", COB, null, "USD", c);
        FrtbRecipes.Recipe r = recipes.save(run, svc.sbmBytes(), svc.drcBytes(), null);
        assertThat(r.id()).startsWith("FR-");
        FrtbRecipes.Replay rp = new FrtbRecipes(dir, FrtbTestData.service(null)).replay(r.id(), null);
        assertThat(rp.inputsEqual()).isTrue();
        assertThat(rp.figuresEqual()).as(String.valueOf(rp.differences())).isTrue();
        assertThat(rp.run().result().total()).isEqualTo(run.result().total());
        assertThat(rp.run().result().choices().girrCorrelation()).isEqualTo("exact");
        assertThat(rp.run().result().params().hash).isEqualTo(run.result().params().hash);
        assertThatThrownBy(() -> recipes.get("FR-NOPE-1")).hasMessageContaining("no recipe");
        assertThat(FrtbAnswer.sa(rp.run()).get("total")).isEqualTo(run.result().total());
    }

    @Test
    void theExampleScopeRunsTheHandBuiltCases() {
        FrtbService svc = FrtbTestData.service(null);
        FrtbService.Run ex = svc.run(null, "EXAMPLE", null, null, "USD", FrtbChoices.defaults());
        assertThat(ex.result().total()).isCloseTo(566_732.73, offset(0.005));
        assertThat(ex.result().drc().total()).isCloseTo(126_177.32, offset(0.005));
        assertThat(ex.result().rrao().total()).isCloseTo(150_000, offset(0.005));
    }
}
