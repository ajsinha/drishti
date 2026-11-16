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

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The scenario card of Why it moved: a date (date format, no unit), said as moved or as unchanged. */
class VarWhyScenarioCardTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> panel) {
        return (Map<String, Object>) panel.get("data");
    }

    @Test
    void movedIsSaidFromTo() {
        Map<String, Object> d = data(VarWhyPanels.scenarioCard("2026-06-03", "2026-06-04"));
        assertThat(d.get("unit")).isNull();
        assertThat(d.get("caption")).isEqualTo("moved from 2026-06-03 to 2026-06-04");
        assertThat(((Map<?, ?>) d.get("value")).get("format")).isEqualTo("date");
        assertThat(((Map<?, ?>) d.get("value")).get("text")).isEqualTo("2026-06-04");
    }

    @Test
    void unchangedIsSaidSince() {
        assertThat(data(VarWhyPanels.scenarioCard("2026-06-03", "2026-06-03")).get("caption")).isEqualTo("unchanged since 2026-06-03");
    }
}
