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
package com.ash.drishti.server.dataload;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.resolution.ModuleTemplates.Rule;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The pack switch reaches loading (docs/plans/MODULE_RATIONALISATION.md, gap G3): a rule of a pack that is off is not matched. */
class PackSwitchRulesTest {

    private static Rule rule(String module, String pack) {
        return new Rule(module, Path.of("/m/dataload.yaml"), pack == null ? Map.of("name", "x") : Map.of("name", "x-" + pack, "pack", pack));
    }

    @Test
    void aRuleOfAPackThatIsOffIsDroppedAndTheOthersStay() {
        List<Rule> all = List.of(rule("source-murex", "murex"), rule("source-endur", "endur"), rule("banking", "trading"), rule("kernel", null),
                rule("other", "not-installed-here"));
        List<Rule> kept = DataLoadConfiguration.followPackSwitch(all, () -> List.of("murex", "endur", "trading"), p -> !p.equals("murex"));
        assertThat(kept).extracting(r -> r.rule().get("name")).containsExactly("x-endur", "x-trading", "x", "x-not-installed-here");
        assertThat(DataLoadConfiguration.followPackSwitch(all, null, null)).as("no pack switch known: every rule").isEqualTo(all);
    }
}
