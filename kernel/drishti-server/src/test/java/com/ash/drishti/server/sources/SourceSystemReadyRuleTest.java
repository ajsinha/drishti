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
package com.ash.drishti.server.sources;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.dataload.ReadyCheck;
import com.ash.drishti.dataload.ReadyRule;
import com.ash.drishti.engine.source.SourceSystems;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SourceSystemReadyRuleTest {

    private static ReadyRule.Input input(String kind, String... systems) {
        ObjectMapper m = new ObjectMapper();
        List<JsonNode> rows = Arrays.stream(systems).map(s -> (JsonNode) m.valueToTree(Map.of("SOURCE_SYSTEM", s))).toList();
        return new ReadyRule.Input(kind, LocalDate.of(2026, 11, 13), "replace", rows, k -> Map.of());
    }

    private static SourceSystems installed(boolean endurOn) {
        SourceSystems s = new SourceSystems();
        s.installed(List.of(new SourceSystems.System("ENDUR", "source-endur", Set.of("trade"), endurOn),
                new SourceSystems.System("MUREX", "source-murex", Set.of("trade"), true)));
        return s;
    }

    @Test
    void aSystemOfAnInstalledModuleIsAllowedWhetherItIsOnOrOff() {
        List<ReadyCheck> out = SourceSystemReadyRule.check(installed(false), input("trade", "ENDUR", "MUREX"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).status()).isEqualTo(ReadyCheck.PASS);
    }

    @Test
    void aSystemNoInstalledModuleBringsIsRefusedAndOtherKindsAreLeftAlone() {
        List<ReadyCheck> out = SourceSystemReadyRule.check(installed(true), input("trade", "ENDUR", "SUMMIT"));
        assertThat(out.get(0).status()).isEqualTo(ReadyCheck.REFUSED);
        assertThat(out.get(0).text()).contains("SUMMIT").contains("not installed");
        assertThat(SourceSystemReadyRule.check(installed(true), input("rate", "SUMMIT"))).isEmpty();
        assertThat(SourceSystemReadyRule.check(new SourceSystems(), input("trade", "SUMMIT"))).isEmpty();
    }
}
