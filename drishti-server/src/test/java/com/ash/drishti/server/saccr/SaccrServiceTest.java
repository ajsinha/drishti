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
package com.ash.drishti.server.saccr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.compute.saccr.Saccr;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The SA-CCR service over the committed demo data (2026-09-30): every netting set's EAD equal to the reference implementation's
 * (tools/packgen/ccr/saccrmath.py, drishti-compute's reference.json) to the cent, read the way the server reads them (the classification
 * rows, the live members' MTM, the CSAs, the collateral); a counterparty's EAD the sum of its sets'; the incomplete set and the sets
 * without derivatives; one answer for the three views; the refusals.
 */
class SaccrServiceTest {

    static SaccrService svc;
    static JsonNode ref;
    static final LocalDate COB = LocalDate.of(2026, 9, 30);

    @BeforeAll
    static void setUp() throws Exception {
        svc = SaccrTestData.service();
        ref = new ObjectMapper().readTree(SaccrTestData.root().resolve("drishti-compute/src/test/resources/saccr/reference.json").toFile());
    }

    @Test
    void everyDemoNettingSetEqualsTheReferenceToTheCent() {
        SaccrService.Run bank = svc.run(null, "BANK", COB, Saccr.Choices.REGULATORY, false);
        int checked = 0;
        var it = ref.get("sets").fields();
        while (it.hasNext()) {
            var e = it.next();
            SaccrService.SetRun s = bank.set(e.getKey());
            assertThat(s).as(e.getKey()).isNotNull();
            assertThat(s.ead()).as(e.getKey()).isCloseTo(e.getValue().get("EAD").asDouble(), within(0.005));
            if (s.result() != null) {
                assertThat(s.result().v()).as(e.getKey() + " V").isCloseTo(e.getValue().get("header").get("V").asDouble(), within(0.005));
                assertThat(s.result().capped()).isEqualTo(e.getValue().get("CAPPED").asBoolean());
                checked++;
            } else {
                assertThat(s.status()).isEqualTo("no derivatives");
            }
            assertThat(s.unclassified()).hasSize(e.getValue().get("unclassified").size());
        }
        assertThat(checked).isGreaterThan(30);
        double sum = bank.sets().stream().mapToDouble(SaccrService.SetRun::ead).sum();
        assertThat(bank.ead()).isCloseTo(sum, within(1e-3));
    }

    @Test
    void aCounterpartysEadIsTheSumOfItsSets() {
        SaccrService.Run cp = svc.run(null, "CPTY:CP-ALDERSHOT", COB, Saccr.Choices.REGULATORY, false);
        assertThat(cp.sets()).hasSize(4);
        double sum = 0;
        for (SaccrService.SetRun s : cp.sets()) {
            sum += svc.run(null, "NSET:" + s.id(), COB, Saccr.Choices.REGULATORY, false).ead();
        }
        assertThat(cp.ead()).isCloseTo(sum, within(1e-6));
        SaccrService.SetRun fra = cp.set("NS-ALDERSHOT-FRA");
        assertThat(fra.status()).isEqualTo("no derivatives");
        assertThat(fra.excluded()).isPositive();
    }

    @Test
    void theIncompleteSetIsFlaggedAndStrictRefusesIt() {
        SaccrService.Run r = svc.run(null, "NS-AURORA-LDN", COB, Saccr.Choices.REGULATORY, false);
        assertThat(r.sets().get(0).status()).isEqualTo("incomplete");
        assertThat(r.sets().get(0).unclassified()).hasSize(1);
        assertThatThrownBy(() -> svc.run(null, "NS-AURORA-LDN", COB, Saccr.Choices.REGULATORY, true)).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("DRS-5411");
    }

    @Test
    void oneAnswerFeedsTheThreeViews() {
        SaccrService.Run r = svc.run(null, "CPTY:CP-ALDERSHOT", COB, Saccr.Choices.REGULATORY, false);
        Map<String, Object> a = SaccrAnswer.scope(r, "NS-ALDERSHOT-NY", svc);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sets = (List<Map<String, Object>>) a.get("sets");
        assertThat(sets).hasSize(4);
        @SuppressWarnings("unchecked")
        Map<String, Object> sel = (Map<String, Object>) a.get("selected");
        assertThat(sel.get("nettingSet")).isEqualTo("NS-ALDERSHOT-NY");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) sel.get("waterfall");
        double ead = (Double) sel.get("EAD");
        assertThat((Double) steps.get(steps.size() - 1).get("to")).isCloseTo(ead, within(1e-6));
        double pfe = (Double) sel.get("PFE");
        double rc = (Double) sel.get("RC");
        double classes = steps.stream().filter(x -> "class".equals(x.get("role"))).mapToDouble(x -> (Double) x.get("value")).sum();
        assertThat(rc + classes).isCloseTo(rc + pfe, within(1e-3));
        assertThat(sel.get("hedgingSets")).asList().isNotEmpty();
        assertThat(sel.get("formula")).asList().hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void theHandBuiltCasesAreTheExampleScope() {
        SaccrService.Run ex = svc.run(null, "EXAMPLE", null, Saccr.Choices.REGULATORY, false);
        assertThat(ex.set("EX-S1").ead()).isCloseTo(4_776_789.04, within(0.01));
        assertThat(svc.run(null, "EXAMPLE:S5", null, Saccr.Choices.REGULATORY, false).sets().get(0).result().capped()).isTrue();
    }

    @Test
    void refusals() {
        assertThatThrownBy(() -> svc.run(null, "NS-NOWHERE", COB, Saccr.Choices.REGULATORY, false)).hasMessageContaining("DRS-5412");
        assertThatThrownBy(() -> svc.run(null, "DESK:DESK-RATES", COB, Saccr.Choices.REGULATORY, false)).hasMessageContaining("DRS-5410");
        assertThatThrownBy(() -> svc.run(null, "", COB, Saccr.Choices.REGULATORY, false)).hasMessageContaining("DRS-5410");
        assertThatThrownBy(() -> svc.run(null, "EXAMPLE:S99", null, Saccr.Choices.REGULATORY, false)).hasMessageContaining("DRS-5410");
        assertThat(Path.of(svc.paramsFile()).toString()).contains("saccr-bcbs.yaml");
    }
}
