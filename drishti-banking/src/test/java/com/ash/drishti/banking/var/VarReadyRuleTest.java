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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.dataload.ReadyCheck;
import com.ash.drishti.dataload.ReadyRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The VaR intake's rules applied before a push: the same {@link VarInputs} the load uses, and which fix the server offers. */
class VarReadyRuleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final VarReadyRule rule = new VarReadyRule(s -> new VarProperties.Input(null, null), Hierarchies::none);

    private static JsonNode position(String id, String system) throws IOException {
        return JSON.readTree("{\"VALUE_TYPE\":\"PV\",\"GRAIN\":\"POSITION\",\"POSITION_ID\":\"" + id + "\",\"SOURCE_SYSTEM\":\"" + system + "\",\"CURRENCY\":\"USD\"}");
    }

    private List<ReadyCheck> run(String mode, List<JsonNode> sample, Map<String, Map<List<String>, Long>> lake) throws IOException {
        return rule.check(new ReadyRule.Input("scenario-pv", LocalDate.parse("2026-11-06"), mode, sample, k -> lake.getOrDefault(k, Map.of())));
    }

    private static List<ReadyCheck> refusals(List<ReadyCheck> c) {
        return c.stream().filter(x -> ReadyCheck.REFUSED.equals(x.status())).toList();
    }

    @Test
    void anotherKindOfDataIsNotItsConcern() throws IOException {
        assertThat(rule.check(new ReadyRule.Input("fx-spot", LocalDate.parse("2026-11-06"), "append", List.of(), k -> Map.of()))).isEmpty();
    }

    @Test
    void aGoodFilePasses() throws IOException {
        assertThat(refusals(run("append", List.of(position("P1", "ENDUR"), position("P2", "ENDUR")), Map.of()))).isEmpty();
    }

    @Test
    void aGrainThatDisagreesWithItsIdIsRefusedInTheIntakesWords() throws IOException {
        JsonNode bad = JSON.readTree("{\"VALUE_TYPE\":\"PV\",\"GRAIN\":\"POSITION\",\"TRADE_ID\":\"T1\",\"SOURCE_SYSTEM\":\"ENDUR\",\"CURRENCY\":\"USD\"}");
        assertThat(refusals(run("append", List.of(bad), Map.of()))).singleElement().satisfies(c -> assertThat(c.text()).contains("DRS-5208", "without a POSITION_ID"));
    }

    @Test
    void anotherGrainInThisTableIsRefusedAndReplaceIsOffered() throws IOException {
        Map<String, Map<List<String>, Long>> lake = Map.of("scenario-pv", Map.of(List.of("ENDUR", "PV", "TRADE"), 498_136L));
        assertThat(refusals(run("append", List.of(position("P1", "ENDUR")), lake))).singleElement().satisfies(c -> {
            assertThat(c.text()).contains("DRS-5210", "ENDUR");
            assertThat(c.field()).isEqualTo("mode");
            assertThat(c.value()).isEqualTo("replace");
        });
        assertThat(refusals(run("replace", List.of(position("P1", "ENDUR")), lake))).as("replace takes that grain away").isEmpty();
    }

    @Test
    void anotherFormInTheOtherTableIsRefusedAndReplaceIsNotOffered() throws IOException {
        Map<String, Map<List<String>, Long>> lake = Map.of("pnl-vector", Map.of(List.of("ENDUR", "PNL", "POSITION"), 10L));
        for (String mode : List.of("append", "replace")) {
            assertThat(refusals(run(mode, List.of(position("P1", "ENDUR")), lake))).singleElement().satisfies(c -> {
                assertThat(c.text()).contains("DRS-5210");
                assertThat(c.field()).isNull();
                assertThat(c.fix()).contains("another table");
            });
        }
    }

    @Test
    void anotherSystemsGrainIsNoClash() throws IOException {
        Map<String, Map<List<String>, Long>> lake = Map.of("scenario-pv", Map.of(List.of("CALYPSO", "PV", "TRADE"), 5L));
        assertThat(refusals(run("append", List.of(position("P1", "ENDUR")), lake))).isEmpty();
    }

    @Test
    void anEmptyHierarchyLevelIsAWarningWithItsCount() throws IOException {
        var spec = new com.ash.drishti.banking.compute.var.HierarchySpec("MGMT", "m", List.of("DESK"), List.of(), null, null, false, null, null, null);
        VarReadyRule r = new VarReadyRule(s -> new VarProperties.Input(null, null), () -> new Hierarchies(List.of(spec), "test"));
        var checks = r.check(new ReadyRule.Input("scenario-pv", LocalDate.parse("2026-11-06"), "append", List.of(position("P1", "ENDUR")), k -> Map.of()));
        assertThat(checks).anyMatch(c -> ReadyCheck.WARNING.equals(c.status()) && c.text().contains("DESK on 1 rows") && c.text().contains("(Blank)"));
    }
}
